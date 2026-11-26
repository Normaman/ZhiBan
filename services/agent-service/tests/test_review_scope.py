"""Published factual answers and reception examples have different evidence roles."""

import json
from unittest.mock import AsyncMock

import pytest
from copilot.agent.runner import ModelAgent
from copilot.clients.llm import LLMResponse
from test_model_agent import call, client_with, context, settings
from test_semantic_social import draft, reviewed, social


def faq_context():
    payload = context("可以开发票吗")
    payload["understanding"] = {"intents": ["product"]}
    payload["knowledge"] = [
        {
            "source_type": "shop_policy",
            "source_id": "faq",
            "topics": ["faq"],
            "title": "常见问答",
            "text": "问：发票是什么类型？回复模板：本店提供电子发票。",
        },
        {
            "source_type": "shop_policy",
            "source_id": "style",
            "topics": ["response_guidelines"],
            "text": "本规范及示例仅指导表达，不提供发票等业务事实。",
        },
    ]
    return payload


def fact_answer(text="本店提供电子发票。"):
    return LLMResponse(
        json.dumps(
            {"outcome": "draft", "content": text, "evidence_ids": ["e1"]}, ensure_ascii=False
        ),
        [],
    )


async def test_published_faq_answer_keeps_its_identity_despite_template_label_and_style_disclaimer(
    tmp_path,
):
    client = client_with(
        LLMResponse(None, [call("search_customer_service_knowledge", {"topic": "faq"})]),
        fact_answer(),
        LLMResponse('{"supported":true,"issues":[]}', []),
    )
    result = await ModelAgent(settings(tmp_path), AsyncMock(), client).generate(faq_context())
    assert result["outcome"] == "draft" and result["verification"]["scope"] == "business_facts"
    citation = result["citations"][0]
    assert (
        citation["knowledge_topics"] == ["faq"] and citation["source_authority"] == "shop_published"
    )
    assert (
        citation["source_version"] == "kb-test-version" and "回复模板" in citation["evidence_text"]
    )
    check = client.chat.call_args_list[-1].args[0]
    assert "contains_business_claims" not in check[0]["content"]
    assert "business_claims" not in check[0]["content"]
    data = json.loads(check[-1]["content"])
    assert data["review_scope"] == "business_facts" and data["citations"][0] == citation
    assert data["response_guidelines"][0]["citation_role"] == "response_guideline"


@pytest.mark.parametrize("extra_claim", ["平台已经鉴定过商品。", "本店保证假一赔十。"])
async def test_faq_does_not_authorize_unwritten_certification_or_compensation(
    tmp_path, extra_claim
):
    rejected = LLMResponse('{"supported":false,"issues":["unsupported_promise"]}', [])
    client = client_with(
        LLMResponse(None, [call("search_customer_service_knowledge", {"topic": "faq"})]),
        fact_answer(extra_claim),
        rejected,
        fact_answer(extra_claim),
        rejected,
    )
    result = await ModelAgent(settings(tmp_path), AsyncMock(), client).generate(faq_context())
    assert result["outcome"] == "needs_human" and result["risk_codes"] == ["DRAFT_NOT_SUPPORTED"]
    assert not result["citations"] and result["verification"]["repair_attempted"]


async def test_business_check_cannot_silently_ignore_a_non_factual_scope_reason(tmp_path):
    client = client_with(
        LLMResponse(None, [call("search_customer_service_knowledge", {"topic": "faq"})]),
        fact_answer(),
        LLMResponse('{"supported":false,"issues":["business_claims"]}', []),
    )
    result = await ModelAgent(settings(tmp_path), None, client).generate(faq_context())
    assert result["risk_codes"] == ["VERIFICATION_FAILED"] and not result["citations"]


async def test_social_scope_still_requires_three_non_factual_checks(tmp_path):
    payload = context("我想先考虑一下") | {"understanding": social()}
    client = client_with(draft(), reviewed())
    result = await ModelAgent(settings(tmp_path), None, client).generate(payload)
    assert result["outcome"] == "draft" and result["verification"]["scope"] == "non_factual"
    assert "contains_business_claims" in client.chat.call_args_list[-1].args[0][0]["content"]
    assert result["verification"]["checks"][-1]["question_is_social"]
