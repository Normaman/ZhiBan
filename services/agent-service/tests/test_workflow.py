import asyncio
from unittest.mock import AsyncMock

import httpx
import pytest
from copilot.contracts import RunRequest
from copilot.graph import DraftGraph
from copilot.runtime import Runtime
from copilot.settings import Settings
from test_fakes import FakeBusinessClient as FixtureBusinessClient
from test_fakes import FakeExecutionAuthority


def settings(tmp_path):
    return Settings(token="test-internal-token-with-32-characters", shop_id="fixture-shop")


def context(text):
    return {
        "id": "run-1",
        "message_id": "message-1",
        "conversation_revision": 1,
        "knowledge_version": "kb-v1",
        "message": {"content": text, "message_type": "text"},
        "history": [],
        "knowledge": [
            {
                "source_type": "shop_policy",
                "source_id": "shipping",
                "topics": ["shipping"],
                "text": "现货付款后 48 小时内发出。",
            },
            {
                "source_type": "product",
                "source_id": "spec",
                "topics": ["spec"],
                "text": "保温杯容量 500mL。",
            },
        ],
    }


async def test_distinct_runs_progress_concurrently_and_duplicates_share_result(tmp_path):
    runtime = Runtime(settings(tmp_path))
    authority = FakeExecutionAuthority()
    runtime.core.post = AsyncMock(side_effect=authority.post)
    entered = set()
    both_started = asyncio.Event()
    release = asyncio.Event()

    async def fetch_context(path):
        if path.endswith("/model-config"):
            return httpx.Response(
                200, json={"managed": False}, request=httpx.Request("GET", "http://core/")
            )
        run_id = path.split("/")[-2]
        payload = dict(
            context("什么时候发货？"),
            id=run_id,
            shop_id="fixture-shop",
            conversation_id="conversation-1",
        )
        return httpx.Response(200, json=payload, request=httpx.Request("GET", "http://core/"))

    async def generate(payload):
        entered.add(payload["id"])
        if len(entered) == 2:
            both_started.set()
        await release.wait()
        return {"outcome": "draft", "run_id": payload["id"]}

    runtime.core.get = AsyncMock(side_effect=fetch_context)
    runtime.graph.generate = AsyncMock(side_effect=generate)

    def request(run_id):
        return RunRequest(
            run_id=run_id,
            shop_id="fixture-shop",
            conversation_id="conversation-1",
            message_id="message-1",
            knowledge_version="kb-v1",
        )

    tasks = [
        asyncio.create_task(runtime.generate(request(run))) for run in ["run-1", "run-1", "run-2"]
    ]
    try:
        await asyncio.wait_for(both_started.wait(), timeout=3)
        release.set()
        results = await asyncio.gather(*tasks)
        assert results[0] == results[1]
        assert runtime.graph.generate.await_count == 2
    finally:
        release.set()
        await asyncio.gather(*tasks, return_exceptions=True)
        await runtime.core.aclose()


@pytest.mark.parametrize(
    "text",
    [
        "我要退款",
        "我要投诉",
        "帮我改地址",
        "要求赔偿",
        "please issue a refund",
        "我要退 款",
        "我要投\u200b诉",
    ],
)
async def test_high_risk_always_needs_human(tmp_path, text):
    s = settings(tmp_path)
    connector = FixtureBusinessClient(s)
    result = await DraftGraph(s, connector).generate(context(text))
    assert result["outcome"] == "needs_human"


async def test_draft_is_grounded_and_pins_version(tmp_path):
    s = settings(tmp_path)
    result = await DraftGraph(s, FixtureBusinessClient(s)).generate(context("什么时候发货？"))
    assert result["outcome"] == "draft"
    assert result["citations"][0]["source_version"] == "kb-v1"
    assert "48 小时" in result["content"]
    empty = context("什么时候发货？")
    empty["knowledge"] = []
    assert (await DraftGraph(s, FixtureBusinessClient(s)).generate(empty))[
        "outcome"
    ] == "insufficient_evidence"


async def test_inventory_comes_from_current_connector_query(tmp_path):
    s = settings(tmp_path)
    result = await DraftGraph(s, FixtureBusinessClient(s)).generate(context("有库存吗？"))
    assert result["citations"][0]["source_type"] == "platform_product"
    assert result["citations"][0]["fetched_at"]
    assert "32" in result["content"]


async def test_rule_retrieval_binds_product_specs_to_the_current_product(tmp_path):
    s = settings(tmp_path)
    payload = context("这款的材质是什么")
    payload["product_context"] = {"goods_id": "goods-b"}
    payload["knowledge"] = [
        {
            "source_id": "spec-a",
            "source_type": "product",
            "topics": ["spec"],
            "goods_id": "goods-a",
            "text": "商品甲参数",
        },
        {
            "source_id": "spec-b",
            "source_type": "product",
            "topics": ["spec"],
            "goods_id": "goods-b",
            "text": "商品乙参数",
        },
    ]
    result = await DraftGraph(s, FixtureBusinessClient(s)).generate(payload)
    assert result["outcome"] == "draft"
    assert result["citations"][0]["source_id"] == "spec-b"


async def test_runtime_only_uses_internal_core_business_api(tmp_path):
    s = settings(tmp_path)
    runtime = Runtime(s)
    assert runtime.business is not None
    assert not hasattr(runtime, "connector")
    assert not hasattr(runtime, "send")
    await runtime.core.aclose()



def test_internal_api_exposes_no_platform_send_route_and_requires_authentication(tmp_path):
    from copilot.app import create_app
    from fastapi.testclient import TestClient

    s = settings(tmp_path)
    with TestClient(create_app(s)) as client:
        assert client.post("/internal/v1/pdd/replies", json={"content": "hello"}).status_code == 404
        assert client.get("/metrics").status_code == 401
        assert client.get("/health").status_code == 200
    assert not hasattr(s, "credential_key")
