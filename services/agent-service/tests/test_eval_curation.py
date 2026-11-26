"""Dataset maintenance must preserve facts, provenance and the original benchmark."""

import base64
import io
import json
import sys
import zipfile
from copy import deepcopy
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))

from evals.case_data import ScenarioData, connector_for, context_for, editable_data  # noqa: E402
from evals.curation import Conflict, Workspace, sha  # noqa: E402
from evals.dataset import Case, fingerprint, load, validate_cases  # noqa: E402
from evals.fixtures import context_for as legacy_context  # noqa: E402
from evals.scoring import score, summary  # noqa: E402
from evals.workbench import create_app  # noqa: E402


@pytest.fixture
def workspace(tmp_path):
    cases = load(ROOT / "evals/agent_cases.jsonl")[:2]
    dataset = tmp_path / "base.jsonl"
    dataset.write_text("\n".join(c.model_dump_json() for c in cases))
    rows = []
    for c in cases:
        result = {
            "outcome": "draft",
            "content": "目前有21件库存。",
            "citations": [],
            "understanding": {"intents": ["inventory"]},
            "tool_trace": [],
        }
        rows.append(
            {
                "case_id": c.id,
                "scenario": c.scenario,
                "split": c.split,
                "repeat": 1,
                "question": c.question,
                "expected": c.expected.model_dump(),
                "result": result,
                "result_fingerprint": fingerprint(result),
                "tools": [],
                "retrievals": [],
                "model_requests": [],
                "elapsed_seconds": 1,
                "score": score(c, result, [], []),
            }
        )
    snapshot = {"evals/fixtures.py": (ROOT / "evals/fixtures.py").read_text()}
    (tmp_path / "code-snapshot.json").write_text(json.dumps(snapshot))
    report = {
        "results": rows,
        "summary": summary(rows),
        "metadata": {
            "status": "completed",
            "code_hash": fingerprint(snapshot),
            "source_snapshot": "code-snapshot.json",
            "dataset_hash": sha(dataset),
            "retrieval": "bm25",
            "scorer_hash": sha(ROOT / "evals/scoring.py"),
            "fixtures_hash": sha(ROOT / "evals/fixtures.py"),
        },
    }
    before, after = tmp_path / "before.json", tmp_path / "after.json"
    before.write_text(json.dumps(report))
    after.write_text(json.dumps(report))
    return Workspace(tmp_path / "work", dataset, before, after)


def first(workspace):
    return deepcopy(workspace.state["cases"][0])


def test_edit_persists_with_cas_reason_and_original_files_unchanged(workspace):
    original = sha(workspace.dataset)
    hashes = deepcopy(workspace.report_hashes)
    value = first(workspace)
    value["reference_answer"] = "查询当前商品库存，不引用购买数量。"
    with pytest.raises(ValueError, match="原因"):
        workspace.save_case(0, value, "")
    assert workspace.state["revision"] == 0
    assert workspace.save_case(0, value, "人工核对库存口径") == 1
    with pytest.raises(Conflict):
        workspace.save_case(0, value, "过期页面")
    restored = Workspace(
        workspace.root,
        workspace.dataset,
        workspace.root.parent / "before.json",
        workspace.root.parent / "after.json",
    )
    assert first(restored)["reference_answer"] == value["reference_answer"]
    assert restored.state["journal"][0]["reason"] == "人工核对库存口径"
    assert sha(workspace.dataset) == original and restored.report_hashes == hashes


@pytest.mark.parametrize("change", ["split", "group", "regex", "fixture", "source"])
def test_invalid_dataset_changes_are_atomic(workspace, change):
    value = first(workspace)
    if change == "split":
        value["split"] = "test"
    elif change == "group":
        value["provenance"]["group_id"] = "conversation-1"
        workspace.save_case(0, value, "标记同源")
        value = deepcopy(workspace.state["cases"][1])
        value.update(scenario="different", split="test")
        value["provenance"]["group_id"] = "conversation-1"
    elif change == "regex":
        value["expected"]["contains"] = ["["]
    elif change == "fixture":
        value["fixture"] = "custom"
    else:
        value["provenance"]["kind"] = "anonymized_real"
    revision = workspace.state["revision"]
    with pytest.raises(ValueError):
        workspace.save_case(revision, value, "无效修改")
    assert workspace.state["revision"] == revision


async def test_custom_facts_are_readonly_and_gold_never_enters_context(workspace):
    original = Case.model_validate(first(workspace))
    assert context_for(original) == legacy_context(original)
    data = await editable_data(original)
    data.products[0]["quantity"] = 37
    raw = original.model_dump(mode="json")
    raw.update(
        id="custom-stock",
        scenario="custom-stock",
        fixture="custom",
        input=data.model_dump(mode="json"),
        reference_answer="GOLD_ONLY_SECRET",
    )
    raw["provenance"]["reference"] = "PRIVATE_SOURCE_NOTE"
    case = Case.model_validate(raw)
    validate_cases([case])
    context = context_for(case)
    assert "GOLD_ONLY_SECRET" not in json.dumps(context)
    assert "PRIVATE_SOURCE_NOTE" not in json.dumps(context)
    connector = connector_for(case)
    assert (await connector.products())[0]["quantity"] == 37
    assert not hasattr(connector, "send") and not hasattr(connector, "send_message")
    products = await connector.products()
    products[0]["quantity"] = 999
    assert (await connector.products())[0]["quantity"] == 37
    with pytest.raises(ValueError):
        ScenarioData.model_validate({"products": [{"goods_id": "x", "quantity": -1}]})


async def test_custom_order_requires_buyer_binding(workspace):
    raw = first(workspace)
    raw.update(fixture="cancelled")
    data = await editable_data(Case.model_validate(raw))
    raw.update(fixture="custom", input=data.model_dump(mode="json"))
    connector = connector_for(Case.model_validate(raw))
    order = data.orders[0]
    assert (await connector.order_detail(order["order_id"], data.context.buyer_ref))[
        "order_id"
    ] == order["order_id"]
    with pytest.raises(Exception, match="ORDER_BUYER_MISMATCH"):
        await connector.order_detail(order["order_id"], "another-buyer")
    raw["input"]["orders"][0]["buyer_ref"] = "another-buyer"
    with pytest.raises(ValueError, match="买家"):
        Case.model_validate(raw)


def test_adjudication_is_bound_and_keeps_original_scores(workspace):
    row = next(iter(workspace.rows["after"].values()))
    original = deepcopy(row["score"])
    overrides = {c["name"]: True for c in row["score"]["checks"] if not c["passed"]}
    with pytest.raises(Conflict):
        workspace.save_adjudication(
            0,
            "after",
            row["case_id"],
            1,
            "wrong",
            workspace.report_hashes["after"],
            overrides,
            "核对",
            "scorer_error",
        )
    with pytest.raises(ValueError):
        workspace.save_adjudication(
            0,
            "after",
            row["case_id"],
            1,
            row["result_fingerprint"],
            workspace.report_hashes["after"],
            {"invented": True},
            "核对",
            "scorer_error",
        )
    workspace.save_adjudication(
        0,
        "after",
        row["case_id"],
        1,
        row["result_fingerprint"],
        workspace.report_hashes["after"],
        overrides,
        "已核对工具结果与否定措辞",
        "scorer_error",
    )
    assert row["score"] == original
    assert workspace.corrected_stats("after")["corrected_pass"] == 1
    assert workspace.corrected_stats("before")["corrected_pass"] == 0


def test_export_import_is_versioned_validated_and_idempotent(workspace):
    export = workspace.export(0, "v1", "固定基线")
    directory = workspace.root / "versions/v1"
    assert sha(directory / "cases.jsonl") == export["dataset_sha256"]
    assert len(load(directory / "cases.jsonl")) == 2
    with pytest.raises(FileExistsError):
        workspace.export(0, "v1", "不覆盖")
    value = first(workspace)
    value.update(id="new-case", scenario="new-scenario")
    preview = workspace.preview_import([value])
    assert preview["new"] == 1
    workspace.import_cases(0, [value], preview["digest"], "补充新问题")
    preview = workspace.preview_import([value])
    assert preview["unchanged"] == 1
    revision = workspace.state["revision"]
    workspace.import_cases(revision, [value], preview["digest"], "重复导入")
    assert workspace.state["revision"] == revision and len(workspace.active()) == 3
    value["question"] = "换了问题"
    with pytest.raises(Conflict):
        workspace.import_cases(revision, [value], preview["digest"], "预览失效")


def test_archive_and_restore_do_not_delete_original(workspace):
    value = first(workspace)
    workspace.archive(0, value["id"], "冗余问法")
    assert len(workspace.active()) == 1 and len(workspace.base) == 2
    workspace.save_case(1, value, "恢复核对")
    assert len(workspace.active()) == 2 and not workspace.state["archived"]


def test_regrade_reuses_only_identical_inputs_on_both_sides(workspace):
    value = first(workspace)
    value["expected"]["contains"] = ["不存在的要点"]
    workspace.save_case(0, value, "修订检查项")
    workspace.export(1, "gold-only", "仅修改标注")
    result = workspace.regrade("gold-only")
    assert result["additional_model_requests"] == 0
    for version in ["before", "after"]:
        assert result["reports"][version]["cached_rows"] == 2
        report = json.loads(
            (workspace.root / "regrades" / result["run_id"] / version / "report.json").read_text()
        )
        assert report["results"][0]["prior_score"] != report["results"][0]["score"]
        assert report["metadata"]["dataset_version"] == "gold-only"
    value["question"] = "新的库存问题"
    workspace.save_case(1, value, "改变实际输入")
    workspace.export(2, "changed-input", "输入变更")
    result = workspace.regrade("changed-input")
    for report in result["reports"].values():
        assert report["cached_rows"] == 1 and len(report["pending"]) == 1
        assert report["status"] == "requires_rerun"


def test_workspace_rejects_tampered_result(workspace):
    path = workspace.root.parent / "after.json"
    report = json.loads(path.read_text())
    report["results"][0]["result"]["content"] = "替换输出"
    path.write_text(json.dumps(report))
    with pytest.raises(ValueError, match="指纹"):
        Workspace(workspace.root, workspace.dataset, workspace.root.parent / "before.json", path)


def test_local_api_validates_origin_archive_and_legacy_reviews(workspace):
    client = TestClient(create_app(workspace, token="test-token"), base_url="http://localhost")
    headers = {"X-Curation-Token": "test-token"}
    assert client.get("/review.html").status_code == 200
    assert client.get("/api/workspace").json()["revision"] == 0
    assert client.post("/api/datasets/export", json={}).status_code == 403
    assert (
        client.post(
            "/api/datasets/export", json={}, headers={**headers, "Origin": "http://evil.example"}
        ).status_code
        == 403
    )
    assert client.post("/api/datasets/export", json={}, headers=headers).status_code == 422
    assert client.get("/api/workspace", headers={"Host": "evil.example"}).status_code == 400
    template = client.post("/api/template", json={"case": first(workspace)}, headers=headers)
    assert template.status_code == 200 and template.json()["products"]
    export = client.post(
        "/api/datasets/export",
        json={"revision": 0, "version": "v1", "reason": "固定版本"},
        headers=headers,
    )
    assert export.status_code == 200
    packet = client.get("/api/datasets/v1/download")
    archive = zipfile.ZipFile(io.BytesIO(packet.content))
    assert len(archive.namelist()) == 5
    restored = client.post(
        "/api/import/archive",
        json={"archive_base64": base64.b64encode(packet.content).decode()},
        headers=headers,
    )
    assert restored.status_code == 200 and len(restored.json()["cases"]) == 2
    broken = io.BytesIO()
    with zipfile.ZipFile(broken, "w") as archive:
        archive.writestr("cases.jsonl", "{}")
        archive.writestr("manifest.json", '{"dataset_sha256":"wrong"}')
    assert (
        client.post(
            "/api/import/archive",
            json={"archive_base64": base64.b64encode(broken.getvalue()).decode()},
            headers=headers,
        ).status_code
        == 422
    )
    identity = first(workspace)["id"]
    body = {
        "revision": 0,
        "ratings": {
            f"after:{identity}:1": {
                "factually_correct": True,
                "grounded": False,
                "usable_without_edit": False,
                "notes": "人工核对",
            }
        },
    }
    assert (
        client.post("/api/reviews/import-legacy", json=body, headers=headers).json()["imported"]
        == 1
    )
    assert client.get("/api/reviews/after/download").text.count("人工核对") == 1
    body["revision"] = 1
    assert (
        client.post("/api/reviews/import-legacy", json=body, headers=headers).json()["imported"]
        == 0
    )
    assert workspace.state["revision"] == 1
