"""The evaluation harness must reject fabricated success and preserve isolation."""

import hashlib
import json
import sys
from copy import deepcopy
from dataclasses import replace
from html.parser import HTMLParser
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from copilot.clients.llm import LLMClient, LLMResponse

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))

from evals.benchmark import (  # noqa: E402
    Budget,
    MeasuredClient,
    evaluate,
    main,
    parser,
    snapshot_sources,
)
from evals.build_dataset import build  # noqa: E402
from evals.dataset import fingerprint, load  # noqa: E402
from evals.fixtures import VERSION, FixtureConnector, context_for  # noqa: E402
from evals.review import build as build_review  # noqa: E402
from evals.scoring import compare, score, summary  # noqa: E402


def case(identifier="inventory-01"):
    return next(c for c in load(ROOT / "evals/agent_cases.jsonl") if c.id == identifier)


def response():
    return {
        "outcome": "draft",
        "content": "目前有21件库存。",
        "citations": [],
        "understanding": {"intents": ["inventory"]},
        "risk_codes": [],
        "verification": {"checks": [{"supported": True}]},
        "tool_trace": [{"name": "get_shop_products", "status": "ok"}],
    }


def row(identifier="inventory-01", **extra):
    selected = case(identifier)
    result = response()
    result["outcome"] = selected.expected.outcomes[0]
    return {
        "case_id": selected.id,
        "scenario": selected.scenario,
        "repeat": 1,
        "expected": selected.expected.model_dump(),
        "result": result,
        "result_fingerprint": "fingerprint",
        "score": {"passed": True, "checks": [], "retrieval_recall_at5": None},
        "elapsed_seconds": 1,
        "model_requests": [],
        "retrievals": [],
        **extra,
    }


def review_inputs(tmp_path, content="合成回复"):
    selected = case()
    record = row(question=selected.question, tools=[])
    record["result"]["content"] = content
    record["result_fingerprint"] = fingerprint(record["result"])
    dataset = ROOT / "evals/agent_cases.jsonl"
    report = {
        "metadata": {
            "status": "completed",
            "retrieval": "bm25",
            "scorer_hash": "fixture-scorer",
            "dataset_hash": hashlib.sha256(dataset.read_bytes()).hexdigest(),
            "fixtures_hash": hashlib.sha256((ROOT / "evals/fixtures.py").read_bytes()).hexdigest(),
        },
        "results": [record],
    }
    before, after = tmp_path / "before.json", tmp_path / "after.json"
    before.write_text(json.dumps(report))
    after.write_text(json.dumps(report))
    return before, after, dataset


def test_review_page_keeps_model_output_as_data_and_does_not_fill_human_labels(tmp_path):
    text = "</script><script>window.untrusted_execution=true</script>"
    before, after, dataset = review_inputs(tmp_path, text)
    output = tmp_path / "review.html"
    counts = build_review(before, after, output, dataset)

    class Scripts(HTMLParser):
        def __init__(self):
            super().__init__()
            self.script_count = 0

        def handle_starttag(self, tag, attrs):
            if tag == "script":
                self.script_count += 1

    parser = Scripts()
    html = output.read_text()
    parser.feed(html)
    assert parser.script_count == 2 and text not in html
    assert "factually_correct:null,grounded:null,usable_without_edit:null" in html
    assert counts["pairs"] == 1 and counts["sampled_passes"] == 1


@pytest.mark.parametrize("change", ["result", "scorer", "question"])
def test_review_page_rejects_changed_results_or_incomparable_inputs(tmp_path, change):
    before, after, dataset = review_inputs(tmp_path)
    report = json.loads(after.read_text())
    if change == "result":
        report["results"][0]["result"]["content"] = "被替换的回答"
        expected = "REVIEW_RESULT_CHANGED"
    elif change == "scorer":
        report["metadata"]["scorer_hash"] = "different-scorer"
        expected = "REVIEW_REPORTS_NOT_COMPARABLE"
    else:
        report["results"][0]["question"] = "替换成另一个问题"
        expected = "REVIEW_INPUT_CHANGED"
    after.write_text(json.dumps(report))
    output = tmp_path / "review.html"
    with pytest.raises(ValueError, match=expected):
        build_review(before, after, output, dataset)
    assert not output.exists()


def test_dataset_is_reproducible_and_paraphrase_families_do_not_leak():
    cases = load(ROOT / "evals/agent_cases.jsonl")
    assert len(cases) == 216 and len({c.scenario for c in cases}) == 27
    assert [c.model_dump() for c in cases] == build()
    assert {c.scenario for c in cases if c.split == "dev"}.isdisjoint(
        {c.scenario for c in cases if c.split == "test"}
    )
    for c in cases:
        context = context_for(c)
        assert "expected" not in context and context["shop_id"] == "synthetic-shop"
        assert context["knowledge_version"] == VERSION
        assert not hasattr(FixtureConnector(c.fixture), "send")


def test_source_snapshot_copies_code_and_uses_runtime_only_as_a_link(tmp_path):
    snapshot_sources(tmp_path)
    original = ROOT / "services/agent-service/copilot/graph.py"
    copied = tmp_path / "services/agent-service/copilot/graph.py"
    assert copied.read_text() == original.read_text() and not copied.is_symlink()
    assert (tmp_path / ".runtime").is_symlink()
    assert (tmp_path / "evals/agent_cases.jsonl").is_file()
    copied.write_text("# isolated source edit")
    assert original.read_text() != copied.read_text()


def test_dataset_rejects_family_split_leakage_and_invalid_patterns(tmp_path):
    a = case().model_dump()
    b = {**a, "id": "different", "split": "test"}
    path = tmp_path / "cases.jsonl"
    path.write_text(json.dumps(a) + "\n" + json.dumps(b))
    with pytest.raises(ValueError, match="SCENARIO_SPLIT_LEAKAGE"):
        load(path)
    a["expected"]["contains"] = ["["]
    path.write_text(json.dumps(a))
    with pytest.raises(Exception):
        load(path)


def test_model_saying_supported_is_not_an_evaluation_pass():
    result = response()
    result["content"] = "现有99件。"
    result["citations"] = [
        {
            "source_type": "platform_product",
            "source_id": "900000001",
            "source_version": "forged",
            "evidence_text": "99件",
        }
    ]
    graded = score(case(), result, [], [])
    assert not graded["passed"]
    failed = {c["name"] for c in graded["checks"] if not c["passed"]}
    assert {"required_fact_pattern:1", "citations_from_actual_tools"} <= failed


def test_negative_authenticity_qualification_is_not_an_affirmative_certification():
    result = response()
    result["content"] = "本店所售纸币保真，此为商家说明，并非平台鉴定，不额外提供鉴定证书。"
    graded = score(case("published_faq-01"), result, [], [])
    assert all(c["passed"] for c in graded["checks"] if c["name"].startswith("forbidden_claim"))
    result["content"] = "经过平台鉴定，提供鉴定证书。"
    graded = score(case("published_faq-01"), result, [], [])
    assert any(not c["passed"] for c in graded["checks"] if c["name"].startswith("forbidden_claim"))


async def test_unknown_inventory_fixture_has_no_known_sku_fallback():
    connector = FixtureConnector("unknown_stock")
    assert (await connector.products())[1]["quantity"] is None
    detail = await connector.product_detail("900000002")
    assert all(s["quantity"] is None for s in detail["skus"])


def test_wrong_product_wrong_version_and_partial_coverage_are_checked():
    c = case("partial_batch-01")
    result = response()
    result["content"] = "有7件。"
    result["answered_message_ids"] = ["q1", "q2"]
    result["citations"] = [
        {
            "source_type": "product",
            "source_id": "unit-b",
            "source_version": "old",
            "evidence_text": "2张",
        }
    ]
    graded = score(c, result, [], [])
    failed = {c["name"] for c in graded["checks"] if not c["passed"]}
    assert "partial_batch_coverage" in failed and "pinned_knowledge_version" in failed


def test_retrieval_recall_uses_returned_top_five_not_final_citations():
    c = case("published_faq-01")
    result = response()
    result["content"] = "本店所售纸币保真。"
    result["citations"] = [
        {"source_type": "shop_policy", "source_id": "authenticity", "source_version": VERSION}
    ]
    graded = score(
        c, result, [], [{"sources": ["irrelevant"] * 5 + ["authenticity"], "mode": "bm25"}]
    )
    assert graded["retrieval_recall_at5"] == 0


def test_alternative_valid_tool_strategy_and_wrong_arguments_are_distinguished():
    c = case("product_price-01")
    result = response()
    result["content"] = "蓝色普通拼单价10元。"
    result["understanding"]["intents"] = ["price"]
    result["tool_trace"] = [{"name": "get_product_skus", "status": "ok"}]
    result["citations"] = [
        {
            "source_type": "platform_product",
            "source_id": "900000002:sku:blue",
            "source_version": "now",
        }
    ]
    tools = [
        {"name": "get_product_skus", "arguments": {}, "response": {"evidence": result["citations"]}}
    ]
    assert score(c, result, tools, [])["passed"]
    c = case("freight_paid-01")
    wrong = [{"name": "get_product_shipping", "arguments": {"province": "浙江"}, "response": {}}]
    checks = score(c, response(), wrong, [])["checks"]
    assert next(c for c in checks if c["name"].startswith("tool_parameters"))["passed"] is False


async def test_rescore_imports_reviews_without_any_model_or_platform_calls(tmp_path, monkeypatch):
    r = row()
    report = {"metadata": {"status": "completed"}, "results": [r]}
    source = tmp_path / "source.json"
    source.write_text(json.dumps(report))
    reviews = tmp_path / "reviews.jsonl"
    reviews.write_text(
        json.dumps(
            {
                "case_id": r["case_id"],
                "repeat": 1,
                "result_fingerprint": r["result_fingerprint"],
                "factually_correct": True,
                "grounded": True,
                "usable_without_edit": False,
            }
        )
    )
    network = AsyncMock(side_effect=AssertionError("No model request allowed"))
    monkeypatch.setattr(LLMClient, "chat", network)
    output = tmp_path / "reviewed"
    args = parser().parse_args(
        ["--rescore", str(source), "--reviews", str(reviews), "--output", str(output)]
    )
    assert await main(args) == 0
    saved = json.loads((output / "report.json").read_text())
    assert saved["summary"]["reviewed_cases"] == 1 and network.await_count == 0
    assert json.loads(source.read_text()) == report


def test_missing_usage_and_unreviewed_cases_do_not_become_zero_cost_or_perfect_quality():
    rows = [row(model_requests=[{"usage": None}])]
    s = summary(rows, input_price=1, output_price=2)
    assert s["estimated_cost"] is None and not s["usage_complete"]
    assert s["human_factually_correct"]["rate"] is None
    assert s["unsafe_draft"]["rate"] is None and s["unsafe_draft"]["denominator"] == 0


def test_usage_cost_refusals_and_safety_have_explicit_denominators():
    ordinary = row(
        model_requests=[{"usage": {"prompt_tokens": 1_000_000, "completion_tokens": 500_000}}]
    )
    ordinary["result"]["outcome"] = "needs_human"
    ordinary["result"]["risk_codes"] = ["DRAFT_NOT_SUPPORTED"]
    risk = row("direct_refund-01")
    risk["result"]["outcome"] = "draft"
    s = summary([ordinary, risk], input_price=1, output_price=2)
    assert s["estimated_cost"] == 2
    assert s["false_refusal"] == {"numerator": 1, "denominator": 1, "rate": 1}
    assert s["unsafe_draft"] == {"numerator": 1, "denominator": 1, "rate": 1}
    assert s["verifier_rejection_on_answerable"]["rate"] == 1


def test_human_labels_must_match_the_exact_result():
    r = row()
    review = {
        "case_id": r["case_id"],
        "repeat": 1,
        "result_fingerprint": "wrong",
        "factually_correct": True,
        "grounded": True,
        "usable_without_edit": False,
    }
    with pytest.raises(ValueError, match="REVIEW_ID_OR_RESULT_MISMATCH"):
        summary([r], [review])
    review["result_fingerprint"] = r["result_fingerprint"]
    s = summary([r], [review])
    assert s["reviewed_cases"] == 1 and s["human_usable_without_edit"]["rate"] == 0


def test_comparison_refuses_changed_datasets_and_reports_paired_regressions():
    current = {
        "metadata": {
            "dataset_hash": "a",
            "fixtures_hash": "b",
            "scorer_hash": "c",
            "retrieval": "bm25",
        },
        "results": [row()],
    }
    baseline = deepcopy(current)
    current["results"][0]["score"]["passed"] = False
    assert compare(current, baseline)["regressions"] == ["inventory-01"]
    baseline["metadata"]["dataset_hash"] = "changed"
    with pytest.raises(ValueError, match="BASELINE_INPUTS_DIFFER"):
        compare(current, baseline)


async def test_global_call_budget_is_enforced_before_network(monkeypatch, tmp_path):
    from test_model_agent import settings

    underlying = AsyncMock(
        return_value=LLMResponse("{}", [], {"prompt_tokens": 2, "completion_tokens": 1})
    )
    monkeypatch.setattr(LLMClient, "chat", underlying)
    budget = Budget(1)
    client = MeasuredClient(settings(tmp_path), budget)
    await client.chat([])
    with pytest.raises(ValueError, match="BUDGET_EXHAUSTED"):
        await client.chat([])
    assert underlying.await_count == 1 and budget.exhausted
    assert client.requests[0]["usage"]["prompt_tokens"] == 2


async def test_real_graph_runs_on_fixture_and_keeps_gold_out_of_prompts(monkeypatch, tmp_path):
    from test_model_agent import call, settings
    from test_understanding import understood

    prompts = []
    responses = iter(
        [
            LLMResponse(json.dumps(understood(["inventory"], quantity_meaning="inventory")), []),
            LLMResponse(None, [call("get_shop_products", {})]),
            LLMResponse(
                '{"outcome":"draft","content":"目前有21件库存。","evidence_ids":["e1"]}', []
            ),
            LLMResponse('{"supported":true,"issues":[]}', []),
        ]
    )

    async def chat(self, messages, *args, **kwargs):
        prompts.append(messages)
        return next(responses)

    monkeypatch.setattr(LLMClient, "chat", chat)
    result = await evaluate(case(), replace(settings(tmp_path), nlu_enabled=True), Budget(10), 1)
    assert result["score"]["passed"] and result["result"]["outcome"] == "draft"
    assert result["connector_calls"] == [{"method": "products"}]
    assert all(
        "required_fact_pattern" not in json.dumps(p) and '"expected"' not in json.dumps(p)
        for p in prompts
    )


async def test_usage_parser_does_not_replay_metadata_or_store_untrusted_usage_fields(
    monkeypatch, tmp_path
):
    from test_model_agent import settings

    response = SimpleNamespace(
        content=b"response",
        raise_for_status=lambda: None,
        json=lambda: {
            "choices": [{"message": {"content": "ok"}}],
            "usage": {
                "prompt_tokens": 12,
                "completion_tokens": 4,
                "total_tokens": 16,
                "prompt_cache_hit_tokens": True,
                "secret": "must-not-save",
                "prompt_cache_miss_tokens": -1,
            },
        },
    )
    client = AsyncMock()
    client.post.return_value = response
    client.__aenter__.return_value = client
    monkeypatch.setattr("copilot.clients.llm.httpx.AsyncClient", lambda **kwargs: client)
    result = await LLMClient(settings(tmp_path)).chat([])
    assert result.usage == {"prompt_tokens": 12, "completion_tokens": 4, "total_tokens": 16}
    assert result.message() == {"role": "assistant", "content": "ok"}
