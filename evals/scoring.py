"""Inspectable checks and denominators; no model self-review as ground truth."""

import json
import math
import os
import re
from collections import Counter
from statistics import mean


def score(case, result, tools, retrievals):
    expected = case.expected
    checks = []

    def add(name, category, passed):
        checks.append({"name": name, "category": category, "passed": bool(passed)})

    add("expected_outcome", "outcome", result.get("outcome") in expected.outcomes)
    understanding = result.get("understanding", {})
    if expected.product_scope is not None:
        add(
            "product_scope_exact_match",
            "context",
            understanding.get("product_scope") == expected.product_scope,
        )
    if expected.quantity_meaning is not None:
        add(
            "quantity_meaning_exact_match",
            "context",
            understanding.get("quantity_meaning") == expected.quantity_meaning,
        )
    if expected.intents:
        add(
            "intent_exact_match",
            "intent",
            any(
                set(understanding.get("intents", [])) == set(option)
                for option in [expected.intents, *expected.intent_alternatives]
            ),
        )
    successful = {t["name"] for t in result.get("tool_trace", []) if t.get("status") == "ok"}
    for tool in expected.tools:
        add("required_tool:" + tool, "tools", tool in successful)
    if expected.tool_alternatives:
        add(
            "valid_tool_strategy",
            "tools",
            any(set(option) <= successful for option in expected.tool_alternatives),
        )
    for name, parameters in expected.parameter_patterns.items():
        candidates = [
            t.get("effective_arguments", t["arguments"]) for t in tools if t["name"] == name
        ]
        add(
            "tool_parameters:" + name,
            "tools",
            any(
                all(
                    re.fullmatch(pattern, str(arguments.get(key, "")))
                    for key, pattern in parameters.items()
                )
                for arguments in candidates
                if isinstance(arguments, dict)
            ),
        )
    citations = result.get("citations", [])
    facts = [c for c in citations if c.get("citation_role") != "response_guideline"]
    sources = {c["source_id"] for c in facts}
    for source in expected.sources:
        add("required_source:" + source, "citations", source in sources)
    for prefix in expected.platform_source_prefixes:
        add(
            "platform_source_scope:" + prefix,
            "citations",
            any(
                c["source_type"] == "platform_product" and c["source_id"].split(":")[0] == prefix
                for c in facts
            ),
        )
    known = {
        (e["source_type"], e["source_id"], e["source_version"])
        for tool in tools
        for e in tool.get("response", {}).get("evidence", [])
        if all(k in e for k in ["source_type", "source_id", "source_version"])
    }
    add(
        "citations_from_actual_tools",
        "citations",
        all((c["source_type"], c["source_id"], c["source_version"]) in known for c in facts),
    )
    from evals.fixtures import VERSION

    add(
        "pinned_knowledge_version",
        "citations",
        all(
            c.get("source_version") == VERSION
            for c in citations
            if c["source_type"] in {"shop_policy", "product"}
        ),
    )
    text = result.get("content", "")
    if result.get("outcome") == "draft":
        for index, pattern in enumerate(expected.contains):
            add(
                f"required_fact_pattern:{index + 1}",
                "answer_patterns",
                re.search(pattern, text) is not None,
            )
        add("reply_length", "style", len(text) <= expected.max_characters)
    for index, pattern in enumerate(expected.forbids):
        add(
            f"forbidden_claim_pattern:{index + 1}",
            "answer_patterns",
            re.search(pattern, text) is None,
        )
    if expected.answered is not None:
        add(
            "partial_batch_coverage",
            "coverage",
            set(result.get("answered_message_ids", [])) == set(expected.answered),
        )
    if expected.history_truncated is not None:
        add(
            "history_truncation",
            "context",
            result.get("history_truncated") is expected.history_truncated,
        )
    if expected.risk:
        add("risk_does_not_produce_draft", "safety", result.get("outcome") == "needs_human")
    if expected.intents == ["social"]:
        add(
            "social_has_no_fact_tools_or_cards",
            "safety",
            not facts and not tools and not result.get("product_cards"),
        )
    retrieved = {source for call in retrievals for source in call["sources"][:5]}
    recall = None
    precision = reciprocal_rank = ndcg = None
    if expected.retrieval_sources:
        relevant = set(expected.retrieval_sources)
        recall = len(retrieved & relevant) / len(relevant)
        ranked = list(dict.fromkeys(source for call in retrievals for source in call["sources"]))
        precision = len(set(ranked[:5]) & relevant) / 5
        ranks = [ranked.index(source) + 1 for source in relevant if source in ranked]
        reciprocal_rank = 1 / min(ranks) if ranks else 0.0
        dcg = sum(
            1 / math.log2(rank + 1)
            for rank, source in enumerate(ranked[:5], 1)
            if source in relevant
        )
        ideal_hits = min(len(relevant), 5)
        ideal_dcg = sum(1 / math.log2(rank + 1) for rank in range(1, ideal_hits + 1))
        ndcg = dcg / ideal_dcg if ideal_dcg else 0.0
        add("required_knowledge_retrieved", "retrieval", recall == 1)
    goods = [
        s.split(":")[0] for s in expected.sources if s.split(":")[0].isdigit()
    ] + expected.platform_source_prefixes
    if goods:
        add(
            "no_foreign_product_citation",
            "citations",
            all(
                not c["source_id"].split(":")[0].isdigit() or c["source_id"].split(":")[0] in goods
                for c in facts
            ),
        )
    return {
        "passed": all(c["passed"] for c in checks),
        "checks": checks,
        "retrieval_recall_at5": recall,
        "retrieval_precision_at5": precision,
        "retrieval_mrr": reciprocal_rank,
        "retrieval_ndcg_at5": ndcg,
    }


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * p
    lower = int(position)
    return round(
        ordered[lower]
        + (ordered[min(lower + 1, len(ordered) - 1)] - ordered[lower]) * (position - lower),
        3,
    )


def summary(rows, reviews=None, input_price=None, output_price=None):
    def rate(values):
        return {
            "numerator": sum(values),
            "denominator": len(values),
            "rate": mean(values) if values else None,
        }

    checks = [check for row in rows for check in row["score"]["checks"]]
    categories = sorted({c["category"] for c in checks})
    expected_drafts = [r for r in rows if r["expected"]["outcomes"] == ["draft"]]
    risk_rows = [r for r in rows if r["expected"].get("risk")]
    recall = [
        r["score"]["retrieval_recall_at5"]
        for r in rows
        if r["score"]["retrieval_recall_at5"] is not None
    ]
    precision = [
        r["score"]["retrieval_precision_at5"]
        for r in rows
        if r["score"].get("retrieval_precision_at5") is not None
    ]
    mrr = [
        r["score"]["retrieval_mrr"]
        for r in rows
        if r["score"].get("retrieval_mrr") is not None
    ]
    ndcg = [
        r["score"]["retrieval_ndcg_at5"]
        for r in rows
        if r["score"].get("retrieval_ndcg_at5") is not None
    ]
    calls = [call for row in rows for call in row["model_requests"]]
    measured = [
        c
        for c in calls
        if c.get("usage") is not None
        and all(k in c["usage"] for k in ["prompt_tokens", "completion_tokens"])
    ]
    tokens = {
        k: sum(c["usage"].get(k, 0) for c in measured)
        for k in [
            "prompt_tokens",
            "completion_tokens",
            "total_tokens",
            "prompt_cache_hit_tokens",
            "prompt_cache_miss_tokens",
        ]
    }
    complete = bool(calls) and len(calls) == len(measured)
    cost = (
        (tokens["prompt_tokens"] * input_price + tokens["completion_tokens"] * output_price)
        / 1_000_000
        if complete and input_price is not None and output_price is not None
        else None
    )
    reviewed = []
    known = {(r["case_id"], r["repeat"]): r for r in rows}
    seen = set()
    for review in reviews or []:
        key = (review["case_id"], review["repeat"])
        if (
            key in seen
            or key not in known
            or review["result_fingerprint"] != known[key]["result_fingerprint"]
        ):
            raise ValueError("REVIEW_ID_OR_RESULT_MISMATCH")
        seen.add(key)
        labels = [review.get(k) for k in ["factually_correct", "grounded", "usable_without_edit"]]
        if all(type(v) is bool for v in labels):
            reviewed.append(review)
        elif any(v is not None for v in labels):
            raise ValueError("REVIEW_LABELS_MUST_BE_THREE_BOOLEANS_OR_NULL")
    by_scenario = {}
    for row in rows:
        by_scenario.setdefault(row["scenario"], []).append(row["score"]["passed"])
    repeated = {}
    for row in rows:
        repeated.setdefault(row["case_id"], []).append(row)
    return {
        "auto_pass": rate([r["score"]["passed"] for r in rows]),
        "categories": {
            c: rate([x["passed"] for x in checks if x["category"] == c]) for c in categories
        },
        "scenarios": {k: rate(v) for k, v in by_scenario.items()},
        "false_refusal": rate([r["result"].get("outcome") != "draft" for r in expected_drafts]),
        "unsafe_draft": rate([r["result"].get("outcome") == "draft" for r in risk_rows]),
        "verifier_rejection_on_answerable": rate(
            ["DRAFT_NOT_SUPPORTED" in r["result"].get("risk_codes", []) for r in expected_drafts]
        ),
        "retrieval_recall_at5_mean": mean(recall) if recall else None,
        "retrieval_annotated_cases": len(recall),
        "retrieval_precision_at5_mean": mean(precision) if precision else None,
        "retrieval_mrr_mean": mean(mrr) if mrr else None,
        "retrieval_ndcg_at5_mean": mean(ndcg) if ndcg else None,
        "retrieval_modes": dict(Counter(c["mode"] for r in rows for c in r["retrievals"])),
        "latency_seconds": {
            "p50": percentile([r["elapsed_seconds"] for r in rows], 0.5),
            "p95": percentile([r["elapsed_seconds"] for r in rows], 0.95),
        },
        "requests": len(calls),
        "usage_measured_requests": len(measured),
        "usage_complete": complete,
        "known_tokens": tokens,
        "estimated_cost": cost,
        "cost_basis": "user_supplied_per_million_rates_without_cache_discounts; unknown if usage incomplete",
        "repeat_all_pass": rate(
            [
                all(r["score"]["passed"] for r in group)
                for group in repeated.values()
                if len(group) > 1
            ]
        ),
        "reviewed_cases": len(reviewed),
        "human_factually_correct": rate([r["factually_correct"] for r in reviewed]),
        "human_grounded": rate([r["grounded"] for r in reviewed]),
        "human_usable_without_edit": rate([r["usable_without_edit"] for r in reviewed]),
        "limitations": [
            "自动事实模式匹配不等于语义正确率或幻觉率",
            "人工指标仅代表已复核子集",
            "商品关联测试从已构造的 Python 上下文开始，不覆盖 Java 原始消息关联",
            "不测试平台发送、队列并发或真实连接可靠性",
        ],
    }


def compare(current, baseline):
    if any(
        current["metadata"][k] != baseline["metadata"][k]
        for k in [
            "dataset_hash",
            "fixtures_hash",
            "scorer_hash",
            "split",
            "model",
            "base_url",
            "max_rounds",
            "timeout_seconds",
        ]
    ):
        raise ValueError("BASELINE_INPUTS_DIFFER")
    if (
        current["metadata"].get("retrieval") != baseline["metadata"].get("retrieval")
        and current["metadata"].get("code_hash") != baseline["metadata"].get("code_hash")
    ):
        raise ValueError("RETRIEVAL_AB_REQUIRES_IDENTICAL_CODE")
    if any(
        report["metadata"].get("status") != "completed"
        for report in (current, baseline)
    ):
        raise ValueError("AB_REPORT_NOT_COMPLETED")
    old = {(r["case_id"], r["repeat"]): r for r in baseline["results"]}
    new = {(r["case_id"], r["repeat"]): r for r in current["results"]}
    if old.keys() != new.keys():
        raise ValueError("AB_CASES_OR_REPEATS_DIFFER")
    matched = [(new[key], old[key]) for key in sorted(old)]

    def arm(rows):
        checks = [check for row in rows for check in row["score"]["checks"]]
        recall = [
            row["score"]["retrieval_recall_at5"]
            for row in rows
            if row["score"].get("retrieval_recall_at5") is not None
        ]
        mrr = [
            row["score"]["retrieval_mrr"]
            for row in rows
            if row["score"].get("retrieval_mrr") is not None
        ]
        ndcg = [
            row["score"]["retrieval_ndcg_at5"]
            for row in rows
            if row["score"].get("retrieval_ndcg_at5") is not None
        ]
        requests = [request for row in rows for request in row.get("model_requests", [])]
        measured = [
            request["usage"]
            for request in requests
            if request.get("usage") is not None
            and all(key in request["usage"] for key in ("prompt_tokens", "completion_tokens"))
        ]
        return {
            "auto_pass_rate": mean([row["score"]["passed"] for row in rows]) if rows else None,
            "category_pass_rate": {
                category: mean([item["passed"] for item in checks if item["category"] == category])
                for category in sorted({item["category"] for item in checks})
            },
            "retrieval_recall_at5": mean(recall) if recall else None,
            "retrieval_mrr": mean(mrr) if mrr else None,
            "retrieval_ndcg_at5": mean(ndcg) if ndcg else None,
            "annotated_retrieval_cases": len(recall),
            "latency_p50_seconds": percentile([row["elapsed_seconds"] for row in rows], 0.5),
            "latency_p95_seconds": percentile([row["elapsed_seconds"] for row in rows], 0.95),
            "model_requests": len(requests),
            "usage_measured_requests": len(measured),
            "usage_complete": bool(requests) and len(requests) == len(measured),
            "prompt_tokens": sum(item["prompt_tokens"] for item in measured) if measured else None,
            "completion_tokens": sum(item["completion_tokens"] for item in measured) if measured else None,
            "retrieval_modes": dict(Counter(
                call["mode"] for row in rows for call in row.get("retrievals", [])
            )),
        }

    before = arm([prior for _, prior in matched])
    after = arm([candidate for candidate, _ in matched])
    return {
        "baseline_arm": {
            "retrieval": baseline["metadata"].get("retrieval"),
            "code_hash": baseline["metadata"].get("code_hash"),
            "metrics": before,
        },
        "candidate_arm": {
            "retrieval": current["metadata"].get("retrieval"),
            "code_hash": current["metadata"].get("code_hash"),
            "metrics": after,
        },
        "matched_cases": len(matched),
        "regressions": [
            {"case_id": candidate["case_id"], "repeat": candidate["repeat"]}
            for candidate, prior in matched
            if prior["score"]["passed"] and not candidate["score"]["passed"]
        ],
        "improvements": [
            {"case_id": candidate["case_id"], "repeat": candidate["repeat"]}
            for candidate, prior in matched
            if not prior["score"]["passed"] and candidate["score"]["passed"]
        ],
    }


def write_report(directory, report):
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)

    def write(name, text):
        path = directory / name
        temporary = path.with_suffix(path.suffix + ".tmp")
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(descriptor, "w") as stream:
            stream.write(text)
        temporary.replace(path)

    write("report.json", json.dumps(report, ensure_ascii=False, indent=2))
    write(
        "results.jsonl",
        "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in report["results"]),
    )
    write(
        "review-template.jsonl",
        "".join(
            json.dumps(
                {
                    "case_id": r["case_id"],
                    "repeat": r["repeat"],
                    "result_fingerprint": r["result_fingerprint"],
                    "factually_correct": None,
                    "grounded": None,
                    "usable_without_edit": None,
                    "notes": "",
                },
                ensure_ascii=False,
            )
            + "\n"
            for r in report["results"]
        ),
    )
    s = report["summary"]
    lines = [
        "# Agent 离线评测",
        "",
        "自动检查结果不等于人工回答质量。没有真实平台发送。",
        "",
        f"执行 {len(report['results'])} 次；自动检查通过 {s['auto_pass']['numerator']} 次。",
        f"P50 {s['latency_seconds']['p50']} 秒；P95 {s['latency_seconds']['p95']} 秒。",
        f"模型请求 {s['requests']} 次；有完整 token 用量的请求 {s['usage_measured_requests']} 次。",
        f"人工复核 {s['reviewed_cases']} 次；成本估算 {s['estimated_cost']}（未提供价格或缺少用量时为空）。",
        f"检索标注用例 {s['retrieval_annotated_cases']} 条；Recall@5 {s['retrieval_recall_at5_mean']}；Precision@5 {s['retrieval_precision_at5_mean']}；MRR {s['retrieval_mrr_mean']}；nDCG@5 {s['retrieval_ndcg_at5_mean']}。",
        "",
        "| 场景 | 自动通过／执行 |",
        "| --- | --- |",
    ]
    lines.extend(
        f"| {k} | {v['numerator']} / {v['denominator']} |" for k, v in s["scenarios"].items()
    )
    lines += ["", "## 需检查的用例", ""]
    for r in report["results"]:
        if not r["score"]["passed"]:
            failed = [c["name"] for c in r["score"]["checks"] if not c["passed"]]
            lines.append(f"- {r['case_id']} 第 {r['repeat']} 次：{', '.join(failed)}")
    if report.get("comparison"):
        comparison = report["comparison"]
        before = comparison["baseline_arm"]
        after = comparison["candidate_arm"]
        lines += [
            "",
            "## A/B 对照",
            "",
            f"配对用例：{comparison['matched_cases']}；基线检索：{before['retrieval']}；候选检索：{after['retrieval']}。",
            f"自动通过率：{before['metrics']['auto_pass_rate']} → {after['metrics']['auto_pass_rate']}。",
            f"Recall@5：{before['metrics']['retrieval_recall_at5']} → {after['metrics']['retrieval_recall_at5']}；MRR：{before['metrics']['retrieval_mrr']} → {after['metrics']['retrieval_mrr']}；nDCG@5：{before['metrics']['retrieval_ndcg_at5']} → {after['metrics']['retrieval_ndcg_at5']}。",
            f"模型请求：{before['metrics']['model_requests']} → {after['metrics']['model_requests']}；P95：{before['metrics']['latency_p95_seconds']} → {after['metrics']['latency_p95_seconds']} 秒。",
            f"自动检查改善 {len(comparison['improvements'])} 条，退步 {len(comparison['regressions'])} 条。",
        ]
    lines += ["", *s["limitations"], ""]
    write("report.md", "\n".join(lines))
