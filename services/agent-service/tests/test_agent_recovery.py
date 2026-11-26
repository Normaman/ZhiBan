"""Behavioral checks for revisable understanding and bounded read-only recovery."""

import json
from dataclasses import replace
from unittest.mock import AsyncMock

import pytest
from copilot.agent.tool_executor import ToolExecutor
from copilot.agent.understanding import analyze
from copilot.business_errors import BusinessToolError
from copilot.clients.llm import LLMResponse
from copilot.graph import DraftGraph
from test_model_agent import call, client_with, context, settings
from test_semantic_social import reviewed, social
from test_understanding import response, understood


def draft(text, ids):
    return response({"outcome": "draft", "content": text, "evidence_ids": ids})


def stock_connector():
    connector = AsyncMock()
    connector.products.return_value = [
        {
            "goods_id": "current",
            "goods_name": "合成商品",
            "quantity": 7,
            "price": "10",
            "fetched_at": "fixture-now",
        }
    ]
    return connector


async def test_semantic_analysis_is_not_overwritten_by_stock_or_authenticity_regex():
    for question in ["库存有几张", "这个是真的吗"]:
        value = understood(["unknown"], quantity_meaning="unclear", needs_clarification=True)
        result = await analyze(context(question), client_with(response(value)))
        assert result["intents"] == ["unknown"]
        assert result["quantity_meaning"] == "unclear" and result["needs_clarification"]


async def test_address_change_request_remains_a_mandatory_semantic_handoff(tmp_path):
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), AsyncMock())
    graph.agent.client = client_with(
        response(
            understood(
                ["shipping"],
                quantity_meaning="none",
                requires_order=True,
                risk_flags=["address_change"],
            )
        )
    )
    result = await graph.generate(context("请把订单收货地址换成新的"))
    assert result["outcome"] == "needs_human" and result["risk_codes"] == ["MANDATORY_HANDOFF"]
    assert result["understanding"]["risk_flags"] == ["address_change"]
    assert result["model_calls"] == 1 and not result["tool_trace"]
    graph.connector.order_detail.assert_not_called()
    graph.connector.send.assert_not_called()


async def test_policy_question_with_risk_word_is_understood_before_handoff(tmp_path):
    payload = context("质量问题的退货规则是什么？")
    payload["knowledge"] = [
        {
            "source_id": "returns",
            "source_type": "shop_policy",
            "topics": ["policy"],
            "text": "符合店铺条件时可在订单页申请售后，由工作人员审核。",
        }
    ]
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), AsyncMock())
    graph.agent.client = client_with(
        response(understood(["after_sales_policy"], quantity_meaning="none")),
        LLMResponse(None, [call("search_customer_service_knowledge", {"topic": "policy"})]),
        draft("符合店铺条件时，可在订单页申请售后，由工作人员审核。", ["e1"]),
        response({"supported": True, "issues": []}),
    )
    result = await graph.generate(payload)
    assert result["outcome"] == "draft" and result["model_calls"] == 4
    graph.connector.send.assert_not_called()


@pytest.mark.parametrize("initial_social", [False, True])
async def test_wrong_initial_intent_is_replanned_and_missing_inventory_is_queried(
    tmp_path, initial_social
):
    payload = context("这件现在还剩多少？")
    payload["product_context"] = {"goods_id": "current"}
    payload["knowledge"] = [
        {
            "source_id": "units",
            "source_type": "product",
            "goods_id": "current",
            "topics": ["spec"],
            "text": "每件包含两张。",
        }
    ]
    connector = stock_connector()
    if initial_social:
        first = [
            response(social()),
            draft("好的。", []),
            reviewed(supported=False, question_is_social=False, issues=["missing_answer"]),
        ]
        stock_id = "e1"
    else:
        first = [
            response(understood(["product"], quantity_meaning="unit_count")),
            LLMResponse(None, [call("get_product_knowledge", {})]),
            draft("每件包含两张。", ["e1"]),
            response({"supported": False, "issues": ["missing_answer"]}),
        ]
        stock_id = "e2"
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), connector)
    graph.agent.client = client_with(
        *first,
        response(understood(["inventory"])),
        LLMResponse(None, [call("get_shop_products", {})]),
        draft("当前库存为7件。", [stock_id]),
        response({"supported": True, "issues": []}),
    )
    result = await graph.generate(payload)
    assert result["outcome"] == "draft" and result["content"] == "当前库存为7件。"
    assert result["understanding"]["intents"] == ["inventory"]
    assert result["verification"]["recoveries"][0]["issues"] == ["missing_answer"]
    assert result["citations"][0]["source_type"] == "platform_product"
    connector.products.assert_awaited_once()
    connector.send.assert_not_called()


async def test_missing_order_fields_do_not_block_known_order_status(tmp_path):
    payload = context("这笔订单现在是什么状态？")
    payload.update(buyer_ref="buyer", order_reference={"order_id": "owned-order"})
    connector = AsyncMock()
    connector.order_detail.return_value = {
        "order_id": "owned-order",
        "buyer_ref": "buyer",
        "fetched_at": "fixture-now",
        "order_status": "cancelled",
        "status_text": "已取消",
        "missing_fields": ["logistics_status", "signed"],
    }
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), connector)
    graph.agent.client = client_with(
        response(understood(["logistics"], quantity_meaning="none", requires_order=True)),
        LLMResponse(None, [call("get_order_details", {})]),
        draft("这笔订单已取消。", ["e1"]),
        response({"supported": True, "issues": []}),
    )
    result = await graph.generate(payload)
    assert result["outcome"] == "draft" and "已取消" in result["content"]
    connector.order_detail.assert_awaited_once_with("owned-order", "buyer")
    connector.send.assert_not_called()


async def test_identical_queries_reuse_results_but_parameter_changes_can_make_progress():
    payload = context("当前库存是多少？")
    payload["product_context"] = {"goods_id": "current"}
    connector = stock_connector()
    executor = ToolExecutor(payload, connector)
    first = await executor.execute(call("get_shop_products", {}, "one"))
    second = await executor.execute(call("get_shop_products", {}, "two"))
    assert json.loads(second["content"])["reused"] is True
    assert json.loads(first["content"])["evidence"] == json.loads(second["content"])["evidence"]
    connector.products.assert_awaited_once()
    await executor.execute(call("get_shop_products", {"goods_id": "current"}, "three"))
    assert connector.products.await_count == 2


async def test_expired_platform_session_stops_without_replanning_or_repeated_queries(tmp_path):
    connector = stock_connector()
    connector.products.side_effect = BusinessToolError("PLATFORM_SESSION_EXPIRED")
    payload = context("还有库存吗？")
    payload["product_context"] = {"goods_id": "current"}
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), connector)
    graph.agent.client = client_with(
        response(understood()),
        LLMResponse(None, [call("get_shop_products", {})]),
    )
    result = await graph.generate(payload)
    assert result["risk_codes"] == ["PLATFORM_SESSION_EXPIRED"]
    assert result["model_calls"] == 2 and result["outcome"] == "needs_human"
    connector.products.assert_awaited_once()


async def test_order_query_uses_trusted_reference_and_rejects_wrong_buyer():
    connector = AsyncMock()
    connector.order_detail.return_value = {
        "order_id": "owned",
        "buyer_ref": "different",
        "fetched_at": "now",
    }
    executor = ToolExecutor(
        {"order_reference": {"order_id": "owned"}, "buyer_ref": "buyer"}, connector
    )
    result = await executor.execute(call("get_order_details", {}))
    assert json.loads(result["content"])["error"] == "ORDER_BUYER_MISMATCH"
    assert not executor.evidence and executor.blocked_error == "ORDER_BUYER_MISMATCH"
    connector.order_detail.assert_awaited_once_with("owned", "buyer")


async def test_ambiguous_product_cannot_be_selected_by_a_model_supplied_id():
    connector = stock_connector()
    executor = ToolExecutor({"product_context": {"ambiguous": True}}, connector)
    result = await executor.execute(call("get_product_skus", {"goods_id": "guessed"}))
    assert json.loads(result["content"])["error"] == "PRODUCT_CONTEXT_AMBIGUOUS"
    connector.product_detail.assert_not_called()


async def test_read_only_tools_remain_available_after_wrong_initial_classification():
    executor = ToolExecutor({"understanding": understood(["after_sales_policy"])}, AsyncMock())
    assert {
        "get_shop_products",
        "get_product_skus",
        "get_product_knowledge",
    } <= executor.registry.keys()
    assert "get_order_details" not in executor.registry
    result = await executor.execute(call("send_message", {"content": "不应发送"}))
    assert json.loads(result["content"])["error"] == "TOOL_NOT_ALLOWED"


async def test_repeated_wrong_answer_stops_at_budget_and_does_not_refetch_same_stock(tmp_path):
    payload = context("还剩多少库存？")
    payload["product_context"] = {"goods_id": "current"}
    connector = stock_connector()
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), connector)
    wrong = draft("还有99件。", ["e1"])
    rejected = response({"supported": False, "issues": ["stock_value"]})
    graph.agent.client = client_with(
        response(understood()),
        LLMResponse(None, [call("get_shop_products", {}, "first")]),
        wrong,
        rejected,
        response(understood()),
        LLMResponse(None, [call("get_shop_products", {}, "again")]),
        wrong,
        rejected,
        wrong,
        rejected,
    )
    result = await graph.generate(payload)
    assert result["outcome"] == "needs_human" and not result["citations"]
    assert result["risk_codes"] == ["DRAFT_NOT_SUPPORTED"]
    assert result["model_calls"] == 10
    assert len(result["verification"]["recoveries"]) == 1
    connector.products.assert_awaited_once()
    connector.send.assert_not_called()
