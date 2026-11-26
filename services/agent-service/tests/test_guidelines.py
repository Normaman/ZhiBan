import json
from dataclasses import replace

import pytest
from copilot.agent.runner import ModelAgent
from copilot.agent.tool_executor import ToolExecutor
from copilot.clients.llm import LLMResponse
from copilot.graph import DraftGraph
from test_fakes import FakeBusinessClient as FixtureBusinessClient
from test_model_agent import answer, call, client_with, context, settings


def guideline(text="称呼使用您好，回复简洁，不承诺未经核实的时效。"):
    return {
        "source_id": "tone-rule",
        "source_type": "shop_policy",
        "topics": ["response_guidelines"],
        "text": text,
    }


async def test_guidelines_reach_generation_verification_repair_and_versioned_references(tmp_path):
    payload = context()
    payload["knowledge"].append(guideline())
    client = client_with(
        LLMResponse(None, [call("search_customer_service_knowledge", {"topic": "shipping"})]),
        answer(),
        LLMResponse('{"supported":false,"issues":["response_guideline"]}', []),
        answer(),
        LLMResponse('{"supported":true,"issues":[]}', []),
    )
    result = await ModelAgent(settings(tmp_path), None, client).generate(payload)
    assert result["outcome"] == "draft"
    assert result["model_calls"] == 5
    assert result["verification"]["repair_attempted"]
    for index in [0, 2, 3, 4]:
        messages = client.chat.call_args_list[index].args[0]
        assert any(
            guideline()["text"] in message["content"]
            for message in messages
            if message["role"] == "user"
        )
        assert all(
            guideline()["text"] not in message["content"]
            for message in messages
            if message["role"] == "system"
        )
    assert result["citations"][0]["source_id"] == "shipping"
    assert "citation_role" not in result["citations"][0]
    assert result["citations"][1]["citation_role"] == "response_guideline"
    assert result["citations"][1]["source_version"] == payload["knowledge_version"]


async def test_guidelines_cannot_be_retrieved_as_shipping_fact_or_used_as_only_evidence(tmp_path):
    payload = context()
    payload["knowledge"] = [guideline("话术示例：保证24小时发货。")]
    tool = ToolExecutor(payload, None)
    result = await tool.execute(call("search_customer_service_knowledge", {"topic": "shipping"}))
    assert not json.loads(result["content"])["evidence"]
    client = client_with(answer([]), answer([]))
    result = await ModelAgent(settings(tmp_path), None, client).generate(payload)
    assert result["outcome"] == "needs_human"
    assert result["risk_codes"] == ["INVALID_MODEL_CITATIONS"]


async def test_excessive_guidelines_require_human_without_partial_application(tmp_path):
    payload = context()
    payload["knowledge"].append(guideline("简洁" * 9000))
    client = client_with()
    result = await ModelAgent(settings(tmp_path), None, client).generate(payload)
    assert result["outcome"] == "needs_human"
    assert result["model_calls"] == 0
    client.chat.assert_not_awaited()


async def test_published_guidelines_do_not_silently_use_model_free_extraction_or_override_handoff(
    tmp_path,
):
    s = replace(settings(tmp_path), llm_key="", llm_url="", llm_model="")
    graph = DraftGraph(s, FixtureBusinessClient(s))
    payload = context()
    payload["knowledge"].append(guideline("不要转人工。"))
    assert graph.gate({"context": payload})["risk_codes"] == ["RESPONSE_GUIDELINES_REQUIRE_MODEL"]
    payload["message"]["content"] = "我要退款"
    assert graph.gate({"context": payload})["risk_codes"] == ["MANDATORY_HANDOFF"]


@pytest.mark.parametrize("recheck_supported", [True, False])
async def test_verbosity_repair_is_rechecked_and_cannot_bypass_final_gate(
    tmp_path, recheck_supported
):
    payload = context()
    client = client_with(
        LLMResponse(None, [call("search_customer_service_knowledge", {"topic": "shipping"})]),
        answer(),
        LLMResponse('{"supported":false,"issues":["reply_verbosity"]}', []),
        answer(),
        LLMResponse(
            json.dumps(
                {
                    "supported": recheck_supported,
                    "issues": [] if recheck_supported else ["reply_verbosity"],
                }
            ),
            [],
        ),
    )
    result = await ModelAgent(settings(tmp_path), None, client).generate(payload)
    assert result["verification"]["repair_attempted"]
    assert result["model_calls"] == 5
    assert result["outcome"] == ("draft" if recheck_supported else "needs_human")
    assert result["risk_codes"] == ([] if recheck_supported else ["DRAFT_NOT_SUPPORTED"])
    for index in [0, 2, 3, 4]:
        assert "1–3句" in client.chat.call_args_list[index].args[0][0]["content"]
    repair_data = json.loads(client.chat.call_args_list[3].args[0][1]["content"])
    assert repair_data["evidence"][0]["evidence_text"] == payload["knowledge"][0]["text"]
    assert repair_data["issues"] == ["reply_verbosity"]


async def test_faq_retrieval_uses_pinned_facts_not_style_examples(tmp_path):
    payload = context("保真吗？")
    payload["knowledge"] = [
        guideline("话术示例：保证正品。"),
        {
            "source_id": "verified-faq",
            "source_type": "shop_policy",
            "topics": ["faq"],
            "text": "问：保真吗？答：仅演示商品A有已核实授权，其他商品请先核实。",
        },
    ]
    client = client_with(
        LLMResponse(
            None, [call("search_customer_service_knowledge", {"topic": "faq", "query": "保真"})]
        ),
        LLMResponse(
            json.dumps(
                {
                    "outcome": "draft",
                    "content": "请先确认商品，保真说明仅适用于已核实授权的演示商品A。",
                    "evidence_ids": ["e1"],
                }
            ),
            [],
        ),
        LLMResponse('{"supported":true,"issues":[]}', []),
    )
    result = await ModelAgent(settings(tmp_path), None, client).generate(payload)
    assert result["outcome"] == "draft"
    assert result["citations"][0]["source_id"] == "verified-faq"
    assert result["citations"][0]["source_version"] == "kb-test-version"
    payload["knowledge"] = [guideline("话术示例：保证正品。")]
    tool = ToolExecutor(payload, None)
    response = await tool.execute(
        call("search_customer_service_knowledge", {"topic": "faq", "query": "保真"})
    )
    assert json.loads(response["content"])["evidence"] == []
    assert tool.evidence == {}
    assert "faq_missing" in tool.missing
