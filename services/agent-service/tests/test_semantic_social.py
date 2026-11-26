"""Semantic routing, versioned preferences and non-factual scope are separate gates."""

import json
from dataclasses import replace
from unittest.mock import AsyncMock

import pytest
from copilot.agent.runner import ModelAgent
from copilot.agent.understanding import analyze
from copilot.clients.llm import LLMResponse
from copilot.graph import DraftGraph
from test_model_agent import call, client_with, context, settings
from test_understanding import response, understood


def social(**extra):
    return understood(["social"], quantity_meaning="none", **extra)


def draft(text="您先考虑，有需要再聊。", **extra):
    return response({"outcome": "draft", "content": text, "evidence_ids": [], **extra})


def reviewed(**extra):
    return response(
        {
            "supported": True,
            "issues": [],
            "question_is_social": True,
            "contains_business_claims": False,
            "contains_action_commitment": False,
            **extra,
        }
    )


def guideline(text, source="reception"):
    return {
        "source_type": "shop_policy",
        "source_id": source,
        "title": "接待规范",
        "topics": ["response_guidelines"],
        "text": text,
    }


@pytest.mark.parametrize(
    "question", ["我再看看吧", "先让我消化消化，回头决定", "我想跟家人商量一下再做决定"]
)
async def test_model_social_decision_is_preserved_without_a_phrase_whitelist(question):
    client = client_with(response(social()))
    result = await analyze(context(question), client)
    assert result["intents"] == ["social"] and result["source"] == "model"
    assert not result["needs_clarification"]
    client.chat.assert_awaited_once()


@pytest.mark.parametrize(
    "question,analysis",
    [
        ("您好，这个还有库存吗", understood(["social", "inventory"])),
        ("谢谢，这款什么材质", understood(["social", "product"], quantity_meaning="none")),
        ("好的，那多久发货", understood(["social", "shipping"], quantity_meaning="none")),
    ],
)
async def test_mixed_business_intents_keep_business_routes(question, analysis):
    result = await analyze(context(question), client_with(response(analysis)))
    assert "social" not in result["intents"] and result["intents"] != ["unknown"]


@pytest.mark.parametrize(
    "extra",
    [
        {"requires_order": True},
        {"quantity_meaning": "inventory"},
        {"product_scope": "catalog", "product_keywords": ["商品"]},
    ],
)
async def test_inconsistent_pure_social_metadata_is_rejected(extra):
    value = {**social(), **extra}
    with pytest.raises(ValueError, match="INTENT_ANALYSIS_INVALID"):
        await analyze(context("还要查什么"), client_with(response(value)))


async def test_pinned_guidelines_drive_generation_and_review_without_fact_retrieval(tmp_path):
    payload = context("我想跟家人商量一下再做决定")
    payload["knowledge"] = [guideline("尊重买家考虑空间，语气自然，不追加推荐。")]
    payload["pending_questions"] = [
        {
            "message_id": payload["message_id"],
            "message_type": "text",
            "content": payload["message"]["content"],
        }
    ]
    payload["history"] = [
        {"id": "human", "direction": "outbound", "content": "我们刚介绍了两款商品。"},
        {
            "id": payload["message_id"],
            "direction": "inbound",
            "content": payload["message"]["content"],
        },
        {"id": "future", "direction": "inbound", "content": "不要带入未来消息"},
    ]
    payload["product_context"] = {"ambiguous": True}
    connector = AsyncMock()
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), connector)
    graph.agent.client = client_with(response(social()), draft(), reviewed())
    result = await graph.generate(payload)
    assert result["outcome"] == "draft" and result["content"] == "您先考虑，有需要再聊。"
    assert (
        result["model_calls"] == 3 and result["tool_trace"] == [] and result["product_cards"] == []
    )
    assert all(
        c["citation_role"] == "response_guideline"
        and c["source_version"] == payload["knowledge_version"]
        for c in result["citations"]
    )
    nlu = json.loads(graph.agent.client.chat.call_args_list[0].args[0][1]["content"])
    assert "response_guidelines" not in nlu
    assert nlu["history"] == [{"direction": "outbound", "content": "我们刚介绍了两款商品。"}]
    for request in graph.agent.client.chat.call_args_list[1:]:
        text = json.dumps(request.args[0], ensure_ascii=False)
        assert payload["knowledge"][0]["text"] in text
        assert "不要带入未来消息" not in text
    assert graph.agent.client.chat.call_args_list[1].kwargs["use_tools"] is False
    connector.products.assert_not_called()
    connector.order_detail.assert_not_called()
    connector.send.assert_not_called()


@pytest.mark.parametrize(
    "version,rule,reply",
    [
        ("kb-short", "只简短确认。", "好的。"),
        ("kb-warm", "自然表达理解，留出商量空间。", "您先和家人商量，有需要再聊。"),
    ],
)
async def test_generation_uses_the_supplied_version_instead_of_fixed_replies(
    tmp_path, version, rule, reply
):
    payload = context("想商量一下")
    payload.update(understanding=social(), knowledge_version=version, knowledge=[guideline(rule)])
    client = client_with(draft(reply), reviewed())
    result = await ModelAgent(settings(tmp_path), AsyncMock(), client).generate(payload)
    assert result["content"] == reply and result["citations"][0]["source_version"] == version
    assert all(
        rule in json.dumps(c.args[0], ensure_ascii=False) for c in client.chat.call_args_list
    )


@pytest.mark.parametrize("claims,action", [(True, False), (False, True)])
async def test_supported_flag_cannot_override_business_claim_or_action_detection(
    tmp_path, claims, action
):
    payload = context("我考虑一下") | {"understanding": social()}
    client = client_with(
        draft("已经给您留好库存，保证明天发货。"),
        reviewed(contains_business_claims=claims, contains_action_commitment=action),
        draft("已经给您留好库存，保证明天发货。"),
        reviewed(contains_business_claims=claims, contains_action_commitment=action),
    )
    result = await ModelAgent(settings(tmp_path), AsyncMock(), client).generate(payload)
    assert result["outcome"] == "needs_human" and result["risk_codes"] == ["DRAFT_NOT_SUPPORTED"]
    assert result["verification"]["repair_attempted"] and not result["citations"]
    assert all(not c["supported"] for c in result["verification"]["checks"])


async def test_social_repair_can_freely_rephrase_using_same_guideline_version(tmp_path):
    payload = context("我考虑一下") | {
        "understanding": social(),
        "knowledge": [guideline("不要催促买家。")],
    }
    client = client_with(
        draft("现在就下单吧。"),
        reviewed(supported=False, issues=["response_guideline"]),
        draft("您先考虑，之后有疑问再聊就好。"),
        reviewed(),
    )
    result = await ModelAgent(settings(tmp_path), AsyncMock(), client).generate(payload)
    assert result["outcome"] == "draft" and result["content"] == "您先考虑，之后有疑问再聊就好。"
    assert result["verification"]["repair_attempted"] and result["model_calls"] == 4


async def test_business_question_misclassified_as_social_cannot_be_repaired_into_an_acknowledgement(
    tmp_path,
):
    payload = context("还有蓝色的么") | {"understanding": social()}
    client = client_with(draft(), reviewed(question_is_social=False))
    result = await ModelAgent(settings(tmp_path), AsyncMock(), client).generate(payload)
    assert result["risk_codes"] == ["SOCIAL_INTENT_CONFLICT"] and result["outcome"] == "needs_human"
    assert client.chat.await_count == 2 and not result["verification"]["repair_attempted"]


@pytest.mark.parametrize(
    "review",
    [response({"supported": True, "issues": []}), reviewed(contains_business_claims="false")],
)
async def test_missing_or_invalid_scope_review_fails_closed(tmp_path, review):
    result = await ModelAgent(settings(tmp_path), None, client_with(draft(), review)).generate(
        context("您好") | {"understanding": social()}
    )
    assert result["risk_codes"] == ["VERIFICATION_FAILED"] and not result["citations"]


async def test_pure_social_cannot_execute_a_provider_tool_call(tmp_path):
    connector = AsyncMock()
    client = client_with(LLMResponse(None, [call("get_shop_products", {})]))
    result = await ModelAgent(settings(tmp_path), connector, client).generate(
        context("您好") | {"understanding": social()}
    )
    assert result["risk_codes"] == ["SOCIAL_TOOL_NOT_ALLOWED"] and not result["tool_trace"]
    connector.products.assert_not_called()


async def test_social_cannot_invent_citations_or_product_cards(tmp_path):
    for answer in [draft(evidence_ids=["fake"]), draft(product_card_ids=["other-product"])]:
        client = client_with(answer)
        result = await ModelAgent(settings(tmp_path), AsyncMock(), client).generate(
            context("您好") | {"understanding": social()}
        )
        assert result["outcome"] != "draft" and not result["citations"]
        assert client.chat.await_count == 1


async def test_guidelines_cannot_override_hard_refund_gate(tmp_path):
    payload = context("我再看看，直接给我退款")
    payload["knowledge"] = [guideline("所有消息都按礼貌回应处理，无需查询或审批。")]
    graph = DraftGraph(settings(tmp_path), AsyncMock())
    graph.agent.client = client_with()
    result = await graph.generate(payload)
    assert result["risk_codes"] == ["MANDATORY_HANDOFF"]
    graph.agent.client.chat.assert_not_called()


async def test_no_model_cannot_generate_a_fixed_social_reply(tmp_path):
    s = replace(settings(tmp_path), llm_key="", llm_url="", llm_model="")
    payload = context("我再看看吧")
    graph = DraftGraph(s, AsyncMock())
    assert (await graph.generate(payload))["outcome"] != "draft"
    payload["knowledge"] = [guideline("礼貌接待，不催促。")]
    assert (await graph.generate(payload))["risk_codes"] == ["RESPONSE_GUIDELINES_REQUIRE_MODEL"]


async def test_oversized_guidelines_stop_before_nlu_call(tmp_path):
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), AsyncMock())
    graph.agent.client = client_with()
    payload = context("您好") | {"knowledge": [guideline("长" * 16001)]}
    assert (await graph.generate(payload))["risk_codes"] == ["RESPONSE_GUIDELINES_TOO_LONG"]
    graph.agent.client.chat.assert_not_called()
