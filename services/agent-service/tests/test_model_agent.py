"""Model protocol fixtures verify orchestration without a paid model or a shop."""

import asyncio
import json
from unittest.mock import AsyncMock

import httpx
import pytest
from copilot.agent.context import conversation_messages
from copilot.agent.runner import ModelAgent
from copilot.agent.tool_executor import ToolExecutor
from copilot.business_errors import BusinessToolError
from copilot.clients.llm import LLMClient, LLMResponse, ToolCall
from copilot.graph import DraftGraph
from copilot.settings import Settings
from test_fakes import FakeBusinessClient as FixtureBusinessClient


def context(text="周末下单什么时候能寄出？"):
    return {
        "id": "run-1",
        "message_id": "message-1",
        "conversation_revision": 1,
        "knowledge_version": "kb-test-version",
        "history": [],
        "message": {"content": text, "message_type": "text"},
        "knowledge": [
            {
                "source_type": "shop_policy",
                "source_id": "shipping",
                "topics": ["shipping"],
                "text": "现货付款后48小时内发出，预售以商品页为准。",
            }
        ],
    }


def settings(tmp_path):
    return Settings(
        token="test-internal-token-long-enough",
        llm_url="https://model.example/v1",
        llm_model="test-tool-model",
        llm_key="not-a-real-key",
        nlu_enabled=False,  # These fixtures isolate generation; NLU has separate contract tests.
    )


def call(name, args, id="call-1"):
    return ToolCall.model_validate(
        {"id": id, "function": {"name": name, "arguments": json.dumps(args)}}
    )


def answer(ids=None):
    return LLMResponse(
        json.dumps(
            {
                "outcome": "draft",
                "content": "您好，现货付款后48小时内发出，预售以商品页为准。",
                "evidence_ids": ids if ids is not None else ["e1"],
            }
        ),
        [],
    )


def client_with(*responses):
    client = AsyncMock()
    client.chat.side_effect = responses
    return client


@pytest.mark.parametrize("failed_query", [False, True])
async def test_sales_unit_cannot_answer_inventory_even_if_model_would_accept_it(
    tmp_path, failed_query
):
    payload = context("这个有几张")
    payload["product_context"] = {"goods_id": "goods-b"}
    payload["knowledge"] = [
        {
            "source_type": "product",
            "source_id": "unit-b",
            "goods_id": "goods-b",
            "topics": ["spec"],
            "text": "每件包含1张纸币。",
        }
    ]
    connector = AsyncMock()
    connector.products.side_effect = BusinessToolError("PRODUCT_QUERY_FAILED")
    responses = []
    if failed_query:
        responses.append(LLMResponse(None, [call("get_shop_products", {})]))
    responses.extend(
        [
            LLMResponse(None, [call("get_product_knowledge", {})]),
            LLMResponse('{"outcome":"draft","content":"每件1张。","evidence_ids":["e1"]}', []),
            LLMResponse('{"supported":true,"issues":[]}', []),
        ]
    )
    client = client_with(*responses)
    result = await ModelAgent(settings(tmp_path), connector, client).generate(payload)
    assert result["outcome"] == "insufficient_evidence"
    assert result["risk_codes"] == ["LIVE_INVENTORY_REQUIRED"]
    assert result["citations"] == []
    assert client.chat.await_count == len(responses) - 1


@pytest.mark.parametrize("quantity", [0, 7])
async def test_quantity_inventory_uses_current_product_and_passes_intent_to_verifier(
    tmp_path, quantity
):
    payload = context("这个有几张")
    payload["product_context"] = {"goods_id": "goods-b"}
    connector = AsyncMock()
    connector.products.return_value = [
        {"goods_id": "goods-a", "goods_name": "商品甲", "quantity": 99, "fetched_at": "live-test"},
        {
            "goods_id": "goods-b",
            "goods_name": "商品乙",
            "quantity": quantity,
            "fetched_at": "live-test",
        },
    ]
    client = client_with(
        LLMResponse(None, [call("get_shop_products", {"query": "这个有几张"})]),
        LLMResponse(
            json.dumps(
                {"outcome": "draft", "content": f"目前库存{quantity}件。", "evidence_ids": ["e1"]}
            ),
            [],
        ),
        LLMResponse('{"supported":true,"issues":[]}', []),
    )
    result = await ModelAgent(settings(tmp_path), connector, client).generate(payload)
    assert result["outcome"] == "draft"
    assert result["citations"][0]["source_id"] == "goods-b"
    assert (
        json.loads(client.chat.call_args_list[-1].args[0][-1]["content"])["question_intent"]
        == "inventory"
    )


@pytest.mark.parametrize("repair_id", ["e1", "e3"])
async def test_omitted_live_inventory_gets_one_grounded_repair_not_a_fake_missing_query(
    tmp_path, repair_id
):
    payload = context("这个有几张")
    payload["product_context"] = {"goods_id": "goods-b"}
    payload["knowledge"] = [
        {
            "source_type": "product",
            "source_id": "unit-b",
            "goods_id": "goods-b",
            "topics": ["spec"],
            "text": "每件包含1张纸币。",
        }
    ]
    connector = AsyncMock()
    connector.products.return_value = [
        {"goods_id": "goods-b", "goods_name": "测试纸币", "quantity": 7, "fetched_at": "now"}
    ]
    model = ModelAgent(
        settings(tmp_path),
        connector,
        client=client_with(
            LLMResponse(
                None, [call("get_shop_products", {}), call("get_product_knowledge", {}, "units")]
            ),
            LLMResponse('{"outcome":"draft","content":"每件1张。","evidence_ids":["e2"]}', []),
            LLMResponse(
                json.dumps(
                    {"outcome": "draft", "content": "当前库存7件。", "evidence_ids": [repair_id]}
                ),
                [],
            ),
            LLMResponse('{"supported":true,"issues":[]}', []),
        ),
    )
    result = await model.generate(payload)
    if repair_id == "e1":
        assert result["outcome"] == "draft" and "7" in result["content"]
        assert result["verification"]["checks"] == [
            {"supported": False, "issues": ["citation_missing"]},
            {"supported": True, "issues": []},
        ]
        assert result["verification"]["repair_attempted"]
        assert model.client.chat.await_count == 4
    else:
        assert result["outcome"] == "needs_human"
        assert result["risk_codes"] == ["INVALID_MODEL_CITATIONS"]
        assert model.client.chat.await_count == 3


async def test_unknown_platform_stock_is_not_inventory_evidence(tmp_path):
    payload = context("这个有几张")
    payload["product_context"] = {"goods_id": "goods-b"}
    connector = AsyncMock()
    connector.products.return_value = [
        {"goods_id": "goods-b", "goods_name": "商品乙", "quantity": None, "fetched_at": "live-test"}
    ]
    client = client_with(
        LLMResponse(None, [call("get_shop_products", {})]),
        LLMResponse('{"outcome":"draft","content":"有1件。","evidence_ids":["e1"]}', []),
    )
    result = await ModelAgent(settings(tmp_path), connector, client).generate(payload)
    assert result["outcome"] == "insufficient_evidence"
    assert result["risk_codes"] == ["LIVE_INVENTORY_REQUIRED"]


async def test_prose_before_json_gets_one_format_retry_and_still_requires_verification(tmp_path):
    initial = answer()
    client = client_with(
        LLMResponse(None, [call("search_customer_service_knowledge", {"topic": "shipping"})]),
        LLMResponse("面向买家的回答\n" + initial.content, []),
        initial,
        LLMResponse('{"supported":true,"issues":[]}', []),
    )
    result = await ModelAgent(settings(tmp_path), None, client).generate(context())
    assert result["outcome"] == "draft"
    assert result["model_calls"] == 4
    assert result["verification"]["format_repair_attempted"] is True
    assert result["verification"]["checks"] == [{"supported": True, "issues": []}]
    assert client.chat.call_args_list[2].kwargs["use_tools"] is False
    assert result["citations"][0]["source_id"] == "shipping"


async def test_repeated_invalid_format_stops_after_one_retry(tmp_path):
    client = client_with(LLMResponse("格式错误", []), LLMResponse("仍然不是 JSON", []))
    result = await ModelAgent(settings(tmp_path), None, client).generate(context())
    assert result["outcome"] == "needs_human"
    assert result["risk_codes"] == ["MODEL_RESPONSE_INVALID"]
    assert result["model_calls"] == 2
    assert result["citations"] == []


async def test_format_retry_cannot_introduce_unknown_evidence(tmp_path):
    client = client_with(
        LLMResponse(None, [call("search_customer_service_knowledge", {"topic": "shipping"})]),
        LLMResponse("格式错误", []),
        answer(["invented"]),
        answer(["invented"]),
    )
    result = await ModelAgent(settings(tmp_path), None, client).generate(context())
    assert result["outcome"] == "needs_human"
    assert result["risk_codes"] == ["INVALID_MODEL_CITATIONS"]
    assert result["verification"]["checks"] == []


async def test_format_retry_after_step_limit_keeps_tool_protocol_complete(tmp_path):
    s = settings(tmp_path)
    s.llm_max_rounds = 1
    client = client_with(
        LLMResponse(None, [call("search_customer_service_knowledge", {"topic": "shipping"})]),
        LLMResponse("格式错误", []),
        LLMResponse('{"outcome":"insufficient_evidence","content":"需核实","evidence_ids":[]}', []),
    )
    result = await ModelAgent(s, None, client).generate(context())
    assert result["outcome"] == "insufficient_evidence"
    transcript = client.chat.call_args_list[2].args[0]
    calls = [call["id"] for m in transcript for call in m.get("tool_calls", [])]
    results = [m["tool_call_id"] for m in transcript if m["role"] == "tool"]
    assert calls == results == ["call-1"]
    assert result["citations"] == []


@pytest.mark.parametrize(
    "code,expected",
    [
        ("PRODUCT_QUERY_REJECTED_3000000", "PRODUCT_QUERY_REJECTED_3000000"),
        ("PRODUCT_QUERY_FAILED", "PRODUCT_QUERY_FAILED"),
        ("private-cookie-and-error-body", "TOOL_EXECUTION_FAILED"),
    ],
)
async def test_failed_product_tool_exposes_only_safe_codes_and_records_failure(code, expected):
    connector = AsyncMock()
    connector.products.side_effect = BusinessToolError(code)
    executor = ToolExecutor(context(), connector)
    result = await executor.execute(call("get_shop_products", {}))
    assert json.loads(result["content"]) == {"error": expected}
    assert executor.trace[0]["status"] == "failed"
    assert executor.evidence == {}


async def test_multi_round_tool_results_return_to_model_and_generate_natural_draft(tmp_path):
    s = settings(tmp_path)
    connector = FixtureBusinessClient(s)
    graph = DraftGraph(s, connector)
    client = client_with(
        LLMResponse(None, [call("get_shop_products", {})]),
        LLMResponse(
            None, [call("search_customer_service_knowledge", {"topic": "shipping"}, "call-2")]
        ),
        answer(["e2"]),
        LLMResponse('{"supported":true}', []),
    )
    graph.agent.client = client
    result = await graph.generate(context("家里急着用，能赶紧寄给我吗？"))
    assert result["engine"] == "llm_agent"
    assert result["outcome"] == "draft"
    assert result["content"].startswith("您好，现货")
    assert result["model_calls"] == 4
    assert [t["name"] for t in result["tool_trace"]] == [
        "get_shop_products",
        "search_customer_service_knowledge",
    ]
    assert result["citations"][0]["source_version"] == "kb-test-version"
    transcript = client.chat.call_args_list[2].args[0]
    assert [m["tool_call_id"] for m in transcript if m["role"] == "tool"] == ["call-1", "call-2"]
    assert json.loads(transcript[-1]["content"])["evidence"][0]["evidence_id"] == "e2"
    assert all(
        tool["function"]["name"] != "send_goods_link"
        for tool in client.chat.call_args_list[0].args[1]
    )


async def test_current_product_stock_uses_platform_source_with_empty_knowledge_and_preserves_zero(
    tmp_path,
):
    payload = context("这款还有货吗")
    payload["knowledge"] = []
    payload["product_context"] = {
        "goods_id": "goods-b",
        "title": "测试商品乙",
        "source_message_id": "source-fixture",
    }
    connector = AsyncMock()
    connector.products.return_value = [
        {
            "goods_id": "goods-a",
            "goods_name": "测试商品甲",
            "quantity": 50,
            "fetched_at": "fixture-time",
        },
        {
            "goods_id": "goods-b",
            "goods_name": "测试商品乙",
            "quantity": 0,
            "fetched_at": "fixture-time",
        },
    ]
    client = client_with(
        LLMResponse(None, [call("get_shop_products", {})]),
        LLMResponse(
            json.dumps(
                {
                    "outcome": "draft",
                    "content": "测试商品乙的平台商品级库存为0，请人工核实后回复。",
                    "evidence_ids": ["e1"],
                }
            ),
            [],
        ),
        LLMResponse('{"supported":true}', []),
    )
    result = await ModelAgent(settings(tmp_path), connector, client).generate(payload)
    assert result["outcome"] == "draft"
    assert result["citations"][0]["source_id"] == "goods-b"
    assert "库存：0" in result["citations"][0]["evidence_text"]
    assert result["missing_requirements"] == []
    assert "goods-b" in client.chat.call_args_list[0].args[0][-1]["content"]


async def test_ambiguous_stock_asks_for_product_instead_of_telling_user_to_build_inventory_knowledge(
    tmp_path,
):
    connector = AsyncMock()
    connector.products.return_value = [
        {
            "goods_id": str(i),
            "goods_name": f"测试商品{i}",
            "quantity": 5,
            "fetched_at": "fixture-time",
        }
        for i in range(2)
    ]
    client = client_with(
        LLMResponse(None, [call("get_shop_products", {})]),
        LLMResponse(
            '{"outcome":"insufficient_evidence","content":"untrusted unsupported answer","evidence_ids":[]}',
            [],
        ),
    )
    result = await ModelAgent(settings(tmp_path), connector, client).generate(context("库存多少"))
    assert result["outcome"] == "insufficient_evidence"
    assert "商品链接" in result["content"]
    assert "知识库" not in result["content"]
    assert result["missing_requirements"] == ["product_selection"]


async def test_product_scope_can_be_explicitly_overridden_and_refined_without_stale_missing_reason():
    payload = context()
    payload["product_context"] = {"goods_id": "goods-a"}
    connector = AsyncMock()
    connector.products.return_value = [
        {
            "goods_id": "goods-a",
            "goods_name": "商品甲",
            "quantity": 3,
            "fetched_at": "fixture-time",
        },
        {
            "goods_id": "goods-b",
            "goods_name": "商品乙",
            "quantity": 7,
            "fetched_at": "fixture-time",
        },
    ]
    executor = ToolExecutor(payload, connector)
    await executor.execute(call("get_shop_products", {"scope": "catalog"}))
    assert "product_selection" in executor.missing
    result = await executor.execute(call("get_shop_products", {"goods_id": "goods-b"}))
    assert json.loads(result["content"])["evidence"][0]["source_id"] == "goods-b"
    assert "product_selection" not in executor.missing


async def test_product_knowledge_filters_scope_and_multiple_facts_of_one_item_are_not_multiple_products():
    payload = context()
    payload["product_context"] = {"goods_id": "goods-a"}
    payload["knowledge"] = [
        {
            "source_type": "product",
            "source_id": str(i),
            "goods_id": goods,
            "text": "已核实的测试参数",
        }
        for i, goods in enumerate(["goods-a", "goods-a", "goods-b"])
    ]
    executor = ToolExecutor(payload, AsyncMock())
    result = await executor.execute(call("get_product_knowledge", {}))
    value = json.loads(result["content"])
    assert len(value["evidence"]) == 2
    assert value["multiple_products"] is False
    assert all(item["source_id"] in ["0", "1"] for item in value["evidence"])


@pytest.mark.parametrize("text", ["我要退款", "帮我改地址", "我要投诉", "要求赔偿"])
async def test_risk_gate_runs_before_any_model_call(tmp_path, text):
    s = settings(tmp_path)
    graph = DraftGraph(s, FixtureBusinessClient(s))
    graph.agent.client = AsyncMock()
    result = await graph.generate(context(text))
    assert result["outcome"] == "needs_human"
    graph.agent.client.chat.assert_not_awaited()


@pytest.mark.parametrize("ids", [[], ["invented-source"]])
async def test_missing_or_fabricated_citations_cannot_become_a_draft(tmp_path, ids):
    s = settings(tmp_path)
    agent = ModelAgent(s, FixtureBusinessClient(s), client_with(answer(ids), answer(ids)))
    result = await agent.generate(context())
    assert result["outcome"] == "needs_human"
    assert result["risk_codes"] == ["INVALID_MODEL_CITATIONS"]


async def test_unsupported_draft_is_rejected_by_check_node(tmp_path):
    s = settings(tmp_path)
    client = client_with(
        LLMResponse(None, [call("search_customer_service_knowledge", {"topic": "shipping"})]),
        answer(),
        LLMResponse('{"supported":false,"issues":["unsupported_promise"]}', []),
        answer(),
        LLMResponse('{"supported":false,"issues":["unsupported_promise"]}', []),
    )
    result = await ModelAgent(s, FixtureBusinessClient(s), client).generate(context())
    assert result["outcome"] == "needs_human"
    assert result["risk_codes"] == ["DRAFT_NOT_SUPPORTED"]

    assert result["model_calls"] == 5
    assert result["verification"]["repair_attempted"] is True
    assert result["verification"]["checks"][-1]["supported"] is False


async def test_model_cannot_inject_authority_or_call_a_sender(tmp_path):
    s = settings(tmp_path)
    connector = FixtureBusinessClient(s)
    executor = ToolExecutor(context(), connector)
    results = await executor.execute_parallel(
        [
            call("get_shop_products", {"shop_id": "another-shop"}),
            call("send_goods_link", {"goods_id": "test-goods-1"}, "call-2"),
        ]
    )
    assert [json.loads(r["content"])["error"] for r in results] == [
        "INVALID_TOOL_ARGUMENTS",
        "TOOL_NOT_ALLOWED",
    ]
    assert executor.evidence == {}


async def test_model_can_correct_tool_arguments_in_a_later_round(tmp_path):
    s = settings(tmp_path)
    invalid = call("search_customer_service_knowledge", {"topic": "not-supported"})
    valid = call("search_customer_service_knowledge", {"topic": "shipping"}, "call-2")
    client = client_with(
        LLMResponse(None, [invalid]),
        LLMResponse(None, [valid]),
        answer(),
        LLMResponse('{"supported":true}', []),
    )
    result = await ModelAgent(s, FixtureBusinessClient(s), client).generate(context())
    assert result["outcome"] == "draft"
    assert [t["status"] for t in result["tool_trace"]] == ["invalid_arguments", "ok"]


async def test_step_bound_does_not_execute_unlimited_tools(tmp_path):
    s = settings(tmp_path)
    s.llm_max_rounds = 1
    client = client_with(
        LLMResponse(None, [call("get_shop_products", {})]),
        LLMResponse(None, [call("get_shop_products", {}, "call-2")]),
    )
    result = await ModelAgent(s, FixtureBusinessClient(s), client).generate(context())
    assert result["outcome"] == "needs_human"
    assert result["risk_codes"] == ["AGENT_STEP_LIMIT"]
    assert result["tool_trace"] == []
    assert client.chat.call_args_list[-1].kwargs == {"use_tools": False}


async def test_handoff_tool_only_marks_draft_and_stops_loop(tmp_path):
    s = settings(tmp_path)
    client = client_with(
        LLMResponse(None, [call("transfer_conversation", {"reason": "商品不明确"})])
    )
    result = await ModelAgent(s, FixtureBusinessClient(s), client).generate(context())
    assert result["outcome"] == "needs_human"
    assert client.chat.await_count == 1
    assert result["tool_trace"][0]["name"] == "transfer_conversation"


async def test_total_timeout_and_provider_errors_produce_explicit_handoff(tmp_path):
    s = settings(tmp_path)
    s.agent_timeout = 0.02

    async def wait_forever(*args, **kwargs):
        await asyncio.Event().wait()

    client = AsyncMock()
    client.chat.side_effect = wait_forever
    result = await ModelAgent(s, FixtureBusinessClient(s), client).generate(context())
    assert result["risk_codes"] == ["MODEL_TIMEOUT"]
    client.chat.side_effect = RuntimeError("provider echoed secret-not-for-logs")
    result = await ModelAgent(s, FixtureBusinessClient(s), client).generate(context())
    assert "secret-not-for-logs" not in json.dumps(result)
    assert result["outcome"] == "needs_human"


@pytest.mark.parametrize("base_url", ["https://model.example/v1", "https://api.deepseek.com"])
async def test_transport_sends_function_schema_and_normalizes_tool_calls(
    tmp_path, monkeypatch, base_url
):
    s = settings(tmp_path)
    s.llm_url = base_url
    executor = ToolExecutor(context(), FixtureBusinessClient(s))

    def respond(request):
        payload = json.loads(request.content)
        assert str(request.url) == base_url + "/chat/completions"
        if base_url == "https://api.deepseek.com":
            assert payload["thinking"] == {"type": "disabled"}
        else:
            assert "thinking" not in payload
        assert payload["tool_choice"] == "auto"
        assert {t["function"]["name"] for t in payload["tools"]} == {
            "get_shop_products",
            "get_product_skus",
            "get_product_shipping",
            "get_product_knowledge",
            "search_customer_service_knowledge",
            "transfer_conversation",
        }
        assert request.headers["Authorization"] == "Bearer not-a-real-key"
        return httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": None,
                            "tool_calls": [call("get_shop_products", {}).model_dump()],
                        },
                        "finish_reason": "tool_calls",
                    }
                ]
            },
        )

    original = httpx.AsyncClient
    monkeypatch.setattr(
        "copilot.clients.llm.httpx.AsyncClient",
        lambda **kwargs: original(transport=httpx.MockTransport(respond), **kwargs),
    )
    response = await LLMClient(s).chat([{"role": "user", "content": "有货吗"}], executor.schemas())
    assert response.tool_calls[0].function.name == "get_shop_products"


def test_history_is_bounded_and_current_message_is_not_duplicated():
    ctx = context()
    ctx["history"] = [
        {"id": str(i), "direction": "inbound", "content": f"old-{i}"} for i in range(20)
    ]
    ctx["history"].append(
        {"id": "message-1", "direction": "inbound", "content": ctx["message"]["content"]}
    )
    messages, truncated = conversation_messages(ctx)
    assert truncated
    assert messages[1]["content"] == "old-8"
    assert messages[-2]["content"] == "old-19"
    assert sum(m["content"] == ctx["message"]["content"] for m in messages) == 1


def test_regenerated_question_keeps_later_human_reply_after_the_question():
    ctx = context()
    ctx["history"] = [
        {"id": ctx["message_id"], "direction": "inbound", "content": ctx["message"]["content"]},
        {"id": "human-reply", "direction": "outbound", "content": "人工已确认：正在核实这件商品。"},
    ]
    messages, truncated = conversation_messages(ctx)
    assert not truncated
    assert messages[1]["content"] == ctx["message"]["content"]
    assert messages[2] == {"role": "assistant", "content": "人工已确认：正在核实这件商品。"}
    assert "已由客服答复的内容不要重复回答" in messages[-1]["content"]
    assert sum(m["content"] == ctx["message"]["content"] for m in messages) == 1


@pytest.mark.parametrize(
    "url",
    [
        "http://model.example/v1",
        "https://user:password@model.example/v1",
        "https://model.example/v1?key=secret",
    ],
)
def test_model_configuration_rejects_unsafe_endpoint(tmp_path, url):
    s = settings(tmp_path)
    s.llm_url = url
    with pytest.raises(ValueError):
        s.validate()


def test_partial_model_configuration_is_not_silently_disabled(tmp_path):
    s = settings(tmp_path)
    s.llm_key = ""
    with pytest.raises(ValueError):
        s.validate()


async def test_batch_passes_every_pending_question_to_generation_and_verifier(tmp_path):
    config = settings(tmp_path)
    graph = DraftGraph(config, FixtureBusinessClient(config))
    c = context("最后一问")
    c["pending_questions"] = [
        {"message_id": "a", "message_type": "text", "content": "什么时候发货？"},
        {"message_id": "b", "message_type": "text", "content": "预售什么时候发？"},
    ]
    client = AsyncMock()

    async def respond(messages, tools=None, **kwargs):
        if kwargs.get("use_tools") is False:
            return LLMResponse('{"supported":true}', [])
        if messages[-1]["role"] == "tool":
            return answer()
        return LLMResponse(None, [call("search_customer_service_knowledge", {"topic": "shipping"})])

    client.chat.side_effect = respond
    graph.agent.client = client
    result = await graph.generate(c)
    assert result["outcome"] == "draft"
    assert result["answered_message_ids"] == ["a", "b"]
    checks = [
        json.loads(call.args[0][-1]["content"])["question"]
        for call in client.chat.call_args_list
        if call.kwargs.get("use_tools") is False
    ]
    assert len(checks) == 2
    assert any("什么时候发货？" in q and "预售" not in q for q in checks)
    assert any("预售什么时候发？" in q for q in checks)


@pytest.mark.parametrize("kind", ["risk", "overflow", "unsupported", "no_model"])
async def test_pending_questions_are_not_silently_dropped(tmp_path, kind):
    config = settings(tmp_path)
    if kind == "no_model":
        config.llm_key = ""
    graph = DraftGraph(config, FixtureBusinessClient(config))
    graph.agent.client = client_with(
        LLMResponse(
            json.dumps({"outcome": "needs_human", "content": "需要人工核实", "evidence_ids": []}),
            [],
        )
    )
    c = context("库存多少？")
    c["pending_questions"] = [
        {
            "message_id": "old",
            "message_type": "text",
            "content": "退款" if kind == "risk" else "什么时候发货？",
        },
        {
            "message_id": "new",
            "message_type": "image" if kind == "unsupported" else "text",
            "content": "库存多少？",
        },
    ]
    if kind == "overflow":
        c["pending_questions"] *= 16
    result = await graph.generate(c)
    assert result["outcome"] == "needs_human"
    if kind in {"no_model", "overflow"}:
        graph.agent.client.chat.assert_not_called()
    else:
        assert graph.agent.client.chat.call_count > 0
        unsafe = "old" if kind == "risk" else "new"
        assert (
            next(q for q in result["question_results"] if q["message_id"] == unsafe)["outcome"]
            == "needs_human"
        )


async def test_resolved_historical_risk_does_not_override_current_pending_batch(tmp_path):
    config = settings(tmp_path)
    graph = DraftGraph(config, FixtureBusinessClient(config))
    c = context("什么时候发货？")
    c["history"] = [{"id": "answered-risk", "direction": "inbound", "content": "我要退款"}]
    c["pending_questions"] = [
        {"message_id": "current", "message_type": "text", "content": "什么时候发货？"}
    ]
    graph.agent.client = client_with(
        LLMResponse(None, [call("search_customer_service_knowledge", {"topic": "shipping"})]),
        answer(),
        LLMResponse('{"supported":true}', []),
    )
    result = await graph.generate(c)
    assert result["outcome"] == "draft"
    assert graph.agent.client.chat.call_count == 3


async def test_partial_batch_keeps_verified_answer_and_separates_risky_question(tmp_path):
    config = settings(tmp_path)
    graph = DraftGraph(config, FixtureBusinessClient(config))
    payload = context("我要退款")
    payload["pending_questions"] = [
        {"message_id": "safe", "message_type": "text", "content": "什么时候发货？"},
        {"message_id": "risk", "message_type": "text", "content": "我要退款"},
    ]
    graph.agent.client = client_with(
        LLMResponse(None, [call("search_customer_service_knowledge", {"topic": "shipping"})]),
        answer(),
        LLMResponse('{"supported":true}', []),
    )
    result = await graph.generate(payload)
    assert result["outcome"] == "draft"
    assert result["answered_message_ids"] == ["safe"]
    assert result["question_results"][1]["outcome"] == "needs_human"
    assert "MANDATORY_HANDOFF" in result["question_results"][1]["risk_codes"]
    assert "48小时" in result["content"]
    assert graph.agent.client.chat.call_count == 3


async def test_batch_with_no_answer_surfaces_specific_question_reasons(tmp_path):
    graph = DraftGraph(settings(tmp_path), None)
    graph._generate_single = AsyncMock(
        side_effect=[
            {
                "outcome": "insufficient_evidence",
                "content": "请先澄清具体问题。",
                "risk_codes": ["INTENT_UNCLEAR"],
                "citations": [],
                "tool_trace": [],
            },
            {
                "outcome": "needs_human",
                "content": "订单状态待核实。",
                "risk_codes": ["ORDER_DETAILS_INCOMPLETE"],
                "citations": [],
                "tool_trace": [],
            },
        ]
    )
    payload = context("订单进度")
    payload["pending_questions"] = [
        {"message_id": "ambiguous", "message_type": "text", "content": "这个呢"},
        {"message_id": "order-status", "message_type": "text", "content": "我的订单发货了吗"},
    ]

    result = await graph.generate(payload)

    assert result["outcome"] == "needs_human"
    assert result["answered_message_ids"] == []
    assert "未生成可用回复" in result["content"]
    assert [question["risk_codes"] for question in result["question_results"]] == [
        ["INTENT_UNCLEAR"],
        ["ORDER_DETAILS_INCOMPLETE"],
    ]


async def test_each_question_uses_its_own_bound_product_facts(tmp_path):
    graph = DraftGraph(settings(tmp_path), None)
    payload = context("一份几张？")
    payload["pending_questions"] = [
        {"message_id": key, "message_type": "text", "content": "一份几张？"}
        for key in ["first", "second"]
    ]
    payload["question_contexts"] = {"first": {"goods_id": "a"}, "second": {"goods_id": "b"}}
    payload["question_product_facts"] = {
        "first": {"goods_id": "a", "description": "每份一张"},
        "second": {"goods_id": "b", "description": "每份两张"},
    }
    payload["product_context"] = {"goods_id": "b"}
    payload["platform_product_facts"] = payload["question_product_facts"]["second"]

    async def generate(child):
        from copilot.agent.tool_executor import ProductQuery

        result = await ToolExecutor(child, None).product_knowledge(ProductQuery())
        return {
            "outcome": "draft",
            "content": result["evidence"][0]["evidence_text"],
            "citations": result["evidence"],
            "risk_codes": [],
            "engine": "llm_agent",
        }

    graph.agent.generate = AsyncMock(side_effect=generate)
    result = await graph.generate(payload)
    assert result["answered_message_ids"] == ["first", "second"]
    assert [e["source_id"] for e in result["citations"]] == [
        "platform-details:a",
        "platform-details:b",
    ]
    assert "一张" in result["citations"][0]["evidence_text"]
    assert "两张" not in result["citations"][0]["evidence_text"]


async def test_conflicting_explicit_product_links_stop_before_model(tmp_path):
    graph = DraftGraph(settings(tmp_path), None)
    graph.agent.generate = AsyncMock()
    payload = context("一份几张？")
    payload["product_context"] = {"ambiguous": True}
    result = await graph.generate(payload)
    assert result["risk_codes"] == ["PRODUCT_CONTEXT_AMBIGUOUS"]
    graph.agent.generate.assert_not_awaited()


async def test_repair_can_pass_only_after_a_second_check(tmp_path):
    s = settings(tmp_path)
    client = client_with(
        LLMResponse(None, [call("search_customer_service_knowledge", {"topic": "shipping"})]),
        answer(),
        LLMResponse('{"supported":false,"issues":["unsupported_promise"]}', []),
        answer(),
        LLMResponse('{"supported":true,"issues":[]}', []),
    )
    result = await ModelAgent(s, FixtureBusinessClient(s), client).generate(context())
    assert result["outcome"] == "draft"
    assert result["model_calls"] == 5
    assert result["verification"]["checks"] == [
        {"supported": False, "issues": ["unsupported_promise"]},
        {"supported": True, "issues": []},
    ]
    assert all(call.kwargs.get("use_tools") is False for call in client.chat.call_args_list[2:])


async def test_repair_cannot_fabricate_an_evidence_id(tmp_path):
    s = settings(tmp_path)
    client = client_with(
        LLMResponse(None, [call("search_customer_service_knowledge", {"topic": "shipping"})]),
        answer(),
        LLMResponse('{"supported":false,"issues":["other"]}', []),
        answer(["made-up"]),
    )
    result = await ModelAgent(s, FixtureBusinessClient(s), client).generate(context())
    assert result["outcome"] == "needs_human"
    assert result["risk_codes"] == ["INVALID_MODEL_CITATIONS"]
    assert len(result["verification"]["checks"]) == 1


async def test_invalid_verifier_output_is_not_a_pass(tmp_path):
    s = settings(tmp_path)
    client = client_with(
        LLMResponse(None, [call("search_customer_service_knowledge", {"topic": "shipping"})]),
        answer(),
        LLMResponse('{"supported":true,"issues":["private-text"]}', []),
    )
    result = await ModelAgent(s, FixtureBusinessClient(s), client).generate(context())
    assert result["risk_codes"] == ["VERIFICATION_FAILED"]
    assert "private-text" not in json.dumps(result)


@pytest.mark.parametrize(
    "status,code",
    [(401, "MODEL_AUTH_REJECTED"), (429, "MODEL_RATE_LIMITED"), (503, "MODEL_SERVICE_UNAVAILABLE")],
)
async def test_provider_status_errors_have_safe_distinct_codes(tmp_path, status, code):
    s = settings(tmp_path)
    request = httpx.Request("POST", "https://model.example/v1/chat/completions")
    client = AsyncMock()
    client.chat.side_effect = httpx.HTTPStatusError(
        "provider-private-body", request=request, response=httpx.Response(status, request=request)
    )
    result = await ModelAgent(s, FixtureBusinessClient(s), client).generate(context())
    assert result["risk_codes"] == [code]
    assert "provider-private-body" not in json.dumps(result)
