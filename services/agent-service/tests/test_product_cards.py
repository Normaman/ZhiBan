"""The Agent may propose product cards only from successful product queries."""

import json
from dataclasses import replace
from unittest.mock import AsyncMock

from copilot.clients.llm import LLMResponse
from copilot.graph import DraftGraph
from test_catalog import catalog, product
from test_model_agent import call, client_with, context, settings
from test_understanding import response


def recommendation_history(payload):
    payload["history"] = [
        {
            "id": "rec-a",
            "direction": "outbound",
            "message_type": "goods_card",
            "content": json.dumps({"goods_id": "a"}),
        },
        {
            "id": "rec-b",
            "direction": "outbound",
            "message_type": "goods_card",
            "content": json.dumps({"goods_id": "b"}),
        },
    ]
    payload["product_context"] = {"goods_id": "b", "source_message_id": "rec-b"}
    return payload


async def test_generic_reference_after_two_cards_does_not_choose_last_product(tmp_path):
    transport = connector()
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), transport)
    graph.agent.client = client_with(
        response(catalog(product_scope="current", product_keywords=[], needs_clarification=True)),
        LLMResponse(None, [call("get_shop_products", {})]),
        response(
            {"outcome": "insufficient_evidence", "content": "请指出具体款式", "evidence_ids": []}
        ),
    )
    result = await graph.generate(recommendation_history(context("这款还有吗")))
    assert result["outcome"] != "draft"
    assert result["understanding"]["product_ambiguous"]
    assert not result["citations"]
    transport.products.assert_not_awaited()


async def test_explicit_catalog_query_after_two_cards_still_searches_shop(tmp_path):
    transport = connector()
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), transport)
    graph.agent.client = client_with(
        response(catalog()),
        LLMResponse(None, [call("get_shop_products", {"scope": "catalog", "query": "法国"})]),
        response({"outcome": "draft", "content": "有货。", "evidence_ids": ["e1"]}),
        response({"supported": True, "issues": []}),
    )
    result = await graph.generate(recommendation_history(context("法国纸币有吗")))
    assert result["outcome"] == "draft" and not result["understanding"].get("product_ambiguous")
    transport.products.assert_awaited_once_with()


def card(goods="new"):
    return {
        "goods_id": goods,
        "title": "法国100法郎纸币",
        "image_url": None,
        "price": "88.00",
        "fetched_at": "2026-10-28T12:00:00Z",
    }


def connector():
    value = AsyncMock()
    value.products.return_value = [product()]
    return value


async def test_model_can_propose_only_queried_in_stock_cards_without_sending(tmp_path):
    transport = connector()
    transport.products.return_value = [product() | {"fetched_at": card()["fetched_at"]}]
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), transport)
    graph.agent.client = client_with(
        response(catalog()),
        LLMResponse(None, [call("get_shop_products", {"scope": "catalog", "query": "法国"})]),
        response(
            {
                "outcome": "draft",
                "content": "有的，您可以看看这款。",
                "evidence_ids": ["e1"],
                "product_card_ids": ["new"],
            }
        ),
        response({"supported": True, "issues": []}),
    )
    result = await graph.generate(context("法国纸币有吗"))
    assert result["outcome"] == "draft" and result["product_cards"][0]["goods_id"] == "new"
    assert result["product_cards"][0]["price"] == "88"
    check = json.loads(graph.agent.client.chat.call_args_list[-1].args[0][-1]["content"])
    assert check["product_cards"] == result["product_cards"]


async def test_unqueried_or_sold_out_card_proposal_is_blocked(tmp_path):
    transport = connector()
    transport.products.return_value = [product(quantity=0)]
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), transport)
    graph.agent.client = client_with(
        response(catalog()),
        LLMResponse(None, [call("get_shop_products", {"scope": "catalog", "query": "法国"})]),
        LLMResponse(
            json.dumps(
                {
                    "outcome": "draft",
                    "content": "有货。",
                    "evidence_ids": ["e1"],
                    "product_card_ids": ["new"],
                }
            ),
            [],
        ),
    )
    result = await graph.generate(context("法国纸币有吗"))
    assert result["outcome"] != "draft" and result["product_cards"] == []
    assert result["risk_codes"] == ["INVALID_PRODUCT_CARDS"]
