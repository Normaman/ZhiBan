import json
from dataclasses import replace
from unittest.mock import AsyncMock

import pytest
from copilot.agent.tool_executor import ToolExecutor
from copilot.agent.understanding import analyze, prohibited_reply, risk_flags
from copilot.clients.llm import LLMResponse
from copilot.graph import DraftGraph
from test_model_agent import call, client_with, context, settings


def understood(intents=None, **extra):
    return {
        "intents": intents or ["inventory"],
        "quantity_meaning": "inventory",
        "requires_order": False,
        "risk_flags": [],
        "needs_clarification": False,
        **extra,
    }


def response(value):
    return LLMResponse(json.dumps(value, ensure_ascii=False), [])


@pytest.mark.parametrize(
    "question",
    ["退款多久到账", "退货运费谁出", "7天无理由有什么条件", "怎么申请售后", "能不能换货"],
)
def test_general_policy_questions_do_not_trigger_operation_handoff(question):
    assert risk_flags(question) == []


@pytest.mark.parametrize(
    "question",
    [
        "直接给我退款",
        "我要投诉",
        "请修改收货地址",
        "不给赔偿就找12315",
        "这个收到就坏了",
        "给你差评",
    ],
)
def test_risky_actions_and_disputes_trigger_human_review(question):
    assert risk_flags(question)


@pytest.mark.parametrize(
    "text,code",
    [
        ("加我微信私下付款", "OFF_PLATFORM_SOLICITATION"),
        ("五星好评返现5元", "REVIEW_INCENTIVE"),
        ("我这边给你同意退款", "UNAUTHORIZED_PROMISE"),
    ],
)
def test_prohibited_promises_and_solicitation_are_blocked(text, code):
    assert code in prohibited_reply(text)
    assert not prohibited_reply("符合店铺规则时可在订单页申请售后，是否通过由工作人员审核。")


async def test_nlu_uses_only_question_and_prior_buyer_and_human_turns():
    payload = context("那还剩多少？")
    payload["history"] = [
        {"id": "old", "direction": "outbound", "content": "您咨询的是商品甲。"},
        {
            "id": payload["message_id"],
            "direction": "inbound",
            "content": payload["message"]["content"],
        },
        {"id": "future", "direction": "inbound", "content": "之后的另一件商品"},
    ]
    client = client_with(response(understood()))
    result = await analyze(payload, client)
    assert result["intents"] == ["inventory"]
    sent = json.loads(client.chat.call_args.args[0][1]["content"])
    assert sent["history"] == [{"direction": "outbound", "content": "您咨询的是商品甲。"}]


async def test_order_card_metadata_is_not_a_buyer_refund_request():
    client = client_with()
    payload = context('{"order_id":"example","afterSalesStatus":"退款"}')
    payload["message"]["message_type"] = "order_info"
    result = await analyze(payload, client)
    assert result["needs_clarification"] and result["risk_flags"] == []
    client.chat.assert_not_called()


async def test_order_metadata_does_not_trigger_safety_gate_or_generation(tmp_path):
    payload = context('{"order_id":"example","label":"退款投诉状态"}')
    payload["message"]["message_type"] = "order_info"
    payload["history"] = [
        {
            "direction": "inbound",
            "message_type": "order_info",
            "content": payload["message"]["content"],
        }
    ]
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), None)
    graph.agent.client = client_with()
    result = await graph.generate(payload)
    assert result["risk_codes"] == ["INTENT_UNCLEAR"]
    assert result["understanding"]["source"] == "platform_metadata"
    graph.agent.client.chat.assert_not_called()


async def test_unclear_understanding_can_request_clarification_but_invalid_schema_stops(tmp_path):
    s = replace(settings(tmp_path), nlu_enabled=True)
    cases = [
        (
            [
                response(
                    understood(["unknown"], quantity_meaning="unclear", needs_clarification=True)
                ),
                response(
                    {
                        "outcome": "insufficient_evidence",
                        "content": "请明确咨询事项",
                        "evidence_ids": [],
                    }
                ),
            ],
            "NO_VERIFIED_EVIDENCE",
        ),
        ([LLMResponse("不是JSON", [])], "INTENT_ANALYSIS_FAILED"),
    ]
    for replies, code in cases:
        graph = DraftGraph(s, None)
        graph.agent.client = client_with(*replies)
        result = await graph.generate(context("这个多少？"))
        assert result["outcome"] != "draft" and result["risk_codes"] == [code]
        assert result["citations"] == [] and graph.agent.client.chat.await_count == len(replies)


async def test_model_classified_risk_stops_before_tools(tmp_path):
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), None)
    graph.agent.client = client_with(
        response(
            understood(
                ["after_sales_action"], quantity_meaning="none", risk_flags=["direct_refund"]
            )
        )
    )
    result = await graph.generate(context("钱立刻原路给我打回来"))
    assert result["risk_codes"] == ["MANDATORY_HANDOFF"]
    assert result["tool_trace"] == []


@pytest.mark.parametrize("meaning,quantity_prompt", [("none", False), ("unclear", False)])
async def test_clarification_does_not_assume_every_unknown_message_is_a_quantity_question(
    tmp_path, meaning, quantity_prompt
):
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), AsyncMock())
    graph.agent.client = client_with(
        response(understood(["unknown"], quantity_meaning=meaning, needs_clarification=True)),
        response({"outcome": "insufficient_evidence", "content": "请明确问题", "evidence_ids": []}),
    )
    result = await graph.generate(context("这个怎么说"))
    assert result["risk_codes"] == ["NO_VERIFIED_EVIDENCE"]
    assert ("库存" in result["content"]) == quantity_prompt
    assert result["tool_trace"] == [] and result["model_calls"] == 2


async def test_policy_nlu_routes_only_relevant_tools_and_preserves_grounding(tmp_path):
    payload = context("退货运费由谁承担？")
    payload["knowledge"] = [
        {
            "source_type": "shop_policy",
            "source_id": "policy",
            "topics": ["policy"],
            "text": "虚构测试规则：非质量原因退货的运费由买家承担。",
        }
    ]
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), None)
    graph.agent.client = client_with(
        response(understood(["after_sales_policy"], quantity_meaning="none")),
        LLMResponse(None, [call("search_customer_service_knowledge", {"topic": "policy"})]),
        response(
            {
                "outcome": "draft",
                "content": "非质量原因退货，运费由买家承担。",
                "evidence_ids": ["e1"],
            }
        ),
        response({"supported": True, "issues": []}),
    )
    result = await graph.generate(payload)
    assert result["outcome"] == "draft" and result["model_calls"] == 4
    schemas = graph.agent.client.chat.call_args_list[1].args[1]
    assert {s["function"]["name"] for s in schemas} >= {
        "search_customer_service_knowledge",
        "transfer_conversation",
    }
    assert result["understanding"]["intents"] == ["after_sales_policy"]


async def test_unlisted_tool_is_rejected_even_when_model_calls_it():
    executor = ToolExecutor(
        {"understanding": understood(["after_sales_policy"], quantity_meaning="none")}, AsyncMock()
    )
    result = await executor.execute(call("send_message", {}))
    assert json.loads(result["content"])["error"] == "TOOL_NOT_ALLOWED"


async def test_authenticity_question_with_linked_product_retrieves_faq_instead_of_being_unclear(
    tmp_path,
):
    payload = context("这个是真的吗")
    payload["product_context"] = {"goods_id": "goods-current", "title": "当前商品"}
    payload["knowledge"] = [
        {
            "source_type": "shop_policy",
            "source_id": "faq",
            "topics": ["faq"],
            "text": "已核实店铺说明：本店商品保真。",
        }
    ]
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), AsyncMock())
    graph.agent.client = client_with(
        response(understood(["product"], quantity_meaning="none")),
        LLMResponse(None, [call("search_customer_service_knowledge", {"topic": "faq"})]),
        response({"outcome": "draft", "content": "本店商品保真。", "evidence_ids": ["e1"]}),
        response({"supported": True, "issues": []}),
    )
    result = await graph.generate(payload)
    assert result["understanding"]["intents"] == ["product"]
    assert not result["understanding"]["needs_clarification"]
    assert result["outcome"] == "draft" and result["citations"][0]["source_id"] == "faq"


async def test_authenticity_without_faq_does_not_fabricate_a_guarantee(tmp_path):
    payload = context("这个是真的吗")
    payload.update(product_context={"goods_id": "goods-current"}, knowledge=[])
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), AsyncMock())
    graph.agent.client = client_with(
        response(understood(["product"], quantity_meaning="none")),
        LLMResponse(None, [call("search_customer_service_knowledge", {"topic": "faq"})]),
        response({"outcome": "insufficient_evidence", "content": "需要核实", "evidence_ids": []}),
    )
    result = await graph.generate(payload)
    assert result["outcome"] == "insufficient_evidence" and result["citations"] == []


async def test_order_tool_requires_verified_context_and_cannot_select_another_order():
    executor = ToolExecutor(
        {
            "order_context": {
                "order_id": "owned-order",
                "goods_name": "商品",
                "purchased_quantity": 2,
                "fetched_at": "now",
                "missing_fields": ["logistics_status"],
            }
        },
        None,
    )
    result = await executor.execute(call("get_order_details", {}))
    evidence = json.loads(result["content"])["evidence"][0]
    assert evidence["source_type"] == "platform_order" and evidence["source_id"] == "owned-order"
    rejected = await executor.execute(call("get_order_details", {"order_id": "someone-else"}))
    assert json.loads(rejected["content"])["error"] == "INVALID_TOOL_ARGUMENTS"


async def test_missing_logistics_requires_human_after_reading_available_order_facts(tmp_path):
    payload = context("这个订单物流到哪里了？")
    payload.update(buyer_ref="buyer", order_reference={"order_id": "test-order"})
    connector = AsyncMock()
    connector.order_detail.return_value = {
        "order_id": "test-order",
        "buyer_ref": "buyer",
        "purchased_quantity": 1,
        "fetched_at": "now",
        "missing_fields": ["logistics_status"],
    }
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), connector)
    graph.agent.client = client_with(
        response(understood(["logistics"], quantity_meaning="none", requires_order=True)),
        LLMResponse(None, [call("get_order_details", {})]),
        response({"outcome": "needs_human", "content": "物流信息未取得", "evidence_ids": []}),
    )
    result = await graph.generate(payload)
    assert result["outcome"] == "needs_human" and result["citations"] == []
    connector.order_detail.assert_awaited_once_with("test-order", "buyer")
    connector.send.assert_not_called()


async def test_output_filter_blocks_prohibited_draft_even_if_model_verifier_passes(tmp_path):
    graph = DraftGraph(settings(tmp_path), None)
    graph.agent.generate = AsyncMock(
        return_value={
            "outcome": "draft",
            "content": "好评返现5元。",
            "citations": [
                {"source_type": "shop_policy", "source_id": "bad", "source_version": "v"}
            ],
            "engine": "llm_agent",
        }
    )
    result = await graph.generate(context("有什么优惠？"))
    assert result["outcome"] == "needs_human"
    assert result["risk_codes"] == ["REVIEW_INCENTIVE"]
    assert result["citations"] == []
