import asyncio
import json
from dataclasses import replace
from unittest.mock import AsyncMock

import pytest
from copilot.agent.tool_executor import ToolExecutor
from copilot.agent.understanding import analyze
from copilot.clients.llm import LLMResponse
from copilot.graph import DraftGraph
from test_model_agent import call, client_with, context, settings
from test_understanding import response, understood


def basic():
    return {
        "order_id": "test-order",
        "buyer_ref": "buyer",
        "goods_name": "测试商品",
        "spec": "甲",
        "purchased_quantity": 1,
        "order_status": "unknown",
        "status_text": None,
        "missing_fields": ["order_status", "created_at", "logistics_status"],
    }


def detail(label, state):
    return {
        **basic(),
        "order_status": state,
        "status_text": label,
        "missing_fields": ["created_at", "logistics_status"],
        "fetched_at": "now",
    }


@pytest.mark.parametrize(
    "question,requires_order",
    [("可以快点帮我发货吗", True), ("帮我催单", True), ("一般多久发货", False)],
)
async def test_order_requirement_is_preserved_from_semantic_analysis(question, requires_order):
    payload = context(question)
    payload["order_reference"] = {"order_id": "test-order"}
    result = await analyze(
        payload,
        client_with(
            response(
                understood(["shipping"], quantity_meaning="none", requires_order=requires_order)
            )
        ),
    )
    assert result["requires_order"] == requires_order


@pytest.mark.parametrize(
    "state,label,reply",
    [
        ("cancelled", "已取消", "这笔订单已取消，无法安排发货。"),
        ("unpaid", "待支付", "这笔订单尚未付款，暂时无法安排发货。"),
        ("shipped", "已发货，待签收", "这笔订单已发货，当前待签收。"),
    ],
)
async def test_shipping_request_uses_specific_order_state_and_keeps_review(
    state, label, reply, tmp_path
):
    payload = context("可以快点帮我发货吗")
    payload.update(buyer_ref="buyer", order_reference={"order_id": "test-order"})
    connector = AsyncMock()
    connector.order_detail.return_value = detail(label, state)
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), connector)
    graph.agent.client = client_with(
        response(understood(["shipping"], quantity_meaning="none", requires_order=True)),
        LLMResponse(None, [call("get_order_details", {})]),
        response({"outcome": "draft", "content": reply, "evidence_ids": ["e1"]}),
        response({"supported": True, "issues": []}),
    )
    result = await graph.generate(payload)
    assert result["outcome"] == "draft" and result["content"] == reply
    assert result["order_context"]["order_status"] == state
    assert result["citations"][0]["source_type"] == "platform_order"
    assert label in result["citations"][0]["evidence_text"]
    allowed = graph.agent.client.chat.call_args_list[1].args[1]
    assert {s["function"]["name"] for s in allowed} >= {
        "get_order_details",
        "transfer_conversation",
    }
    connector.send.assert_not_called()


async def test_missing_status_does_not_preempt_the_read_only_tool_loop(tmp_path):
    for label in ["待发货", "新状态"]:
        payload = context("可以快点帮我发货吗")
        payload.update(buyer_ref="buyer", order_reference={"order_id": "test-order"})
        graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), AsyncMock())
        graph.connector.order_detail.return_value = detail(
            label, "awaiting_shipment" if label == "待发货" else "unknown"
        )
        graph.agent.client = client_with(
            response(understood(["shipping"], quantity_meaning="none", requires_order=True))
        )
        result = await graph.understand({"context": payload})
        assert not result.get("outcome")
        graph.connector.order_detail.assert_not_called()
        executor = ToolExecutor(result["context"], graph.connector)
        assert {"get_order_details", "get_product_shipping"} <= executor.registry.keys()


async def test_order_query_timeout_can_retry_and_uses_platform_query_timeout():
    payload = context("这笔订单现在是什么状态？")
    payload.update(buyer_ref="buyer", order_reference={"order_id": "test-order"})
    connector = AsyncMock()
    connector.order_detail.side_effect = [asyncio.TimeoutError(), detail("待发货", "awaiting_shipment")]
    executor = ToolExecutor(payload, connector)

    first = await executor.execute(call("get_order_details", {}, "order-timeout"))
    second = await executor.execute(call("get_order_details", {}, "order-retry"))

    assert json.loads(first["content"]) == {"error": "TOOL_TIMEOUT", "retryable": True}
    assert json.loads(second["content"])["evidence"][0]["source_type"] == "platform_order"
    assert [entry["status"] for entry in executor.trace] == ["timeout", "ok"]
    assert connector.order_detail.await_count == 2
    assert executor.registry["get_order_details"].timeout_seconds == 55

