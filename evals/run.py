"""Offline evidence and routing evaluations. No PDD or model credentials required."""

import asyncio
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "services/agent-service"))
from copilot.graph import DraftGraph  # noqa: E402 - standalone script adds the service package path
from copilot.settings import (
    Settings,  # noqa: E402 - standalone script adds the service package path
)

from evals.fixtures import FixtureConnector  # noqa: E402


async def main():
    cases = [json.loads(line) for line in (ROOT / "evals/cases.jsonl").read_text().splitlines()]
    results = []
    settings = Settings(
        token="offline-evaluation-token-32-characters",
        llm_url="",
        llm_key="",
        llm_model="",
    )
    for case in cases:
        fixture = "inventory" if case["id"] in {"inventory", "recommendation"} else "default"
        graph = DraftGraph(settings, FixtureConnector(fixture))
        context = {
            "id": "eval-" + case["id"],
            "message_id": case["id"],
            "conversation_revision": 1,
            "knowledge_version": "eval-v1",
            "message": {"message_type": "text", "content": case["question"]},
            "knowledge": [
                {
                    "source_id": "shipping",
                    "source_type": "shop_policy",
                    "topics": ["shipping"],
                    "text": "现货付款后 48 小时内发出。",
                },
                {
                    "source_id": "policy",
                    "source_type": "shop_policy",
                    "topics": ["policy"],
                    "text": "符合条件的商品支持七天无理由退货，具体申请需人工核实。",
                },
                {
                    "source_id": "product",
                    "source_type": "product",
                    "topics": ["spec"],
                    "text": "杯子容量 500mL，材质 304 不锈钢。",
                },
            ],
        }
        if case["id"] in {"inventory", "recommendation"}:
            context["product_context"] = {
                "goods_id": "900000001",
                "title": "法国纸币甲",
                "source_message_id": "eval-product-card",
            }
        result = await graph.generate(context)
        passed = result["outcome"] == case["outcome"] and (
            "source_type" not in case
            or any(c["source_type"] == case["source_type"] for c in result["citations"])
        )
        results.append({"id": case["id"], "passed": passed, "outcome": result["outcome"]})
    print(
        json.dumps(
            {
                "passed": sum(r["passed"] for r in results),
                "total": len(results),
                "results": results,
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    if not all(r["passed"] for r in results):
        raise SystemExit(1)


if __name__ == "__main__":
    asyncio.run(main())
