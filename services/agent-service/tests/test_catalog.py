"""Catalog availability uses a fresh, bounded name search, not the prior SKU."""

import json
from dataclasses import replace
from unittest.mock import AsyncMock

import pytest
from copilot.agent.catalog_terms import resolve_catalog_keywords
from copilot.agent.catalog import search_catalog
from copilot.agent.runner import ModelAgent
from copilot.agent.tool_executor import ToolExecutor
from copilot.agent.understanding import analyze
from copilot.business_errors import BusinessToolError
from copilot.clients.llm import LLMResponse
from copilot.graph import DraftGraph
from test_model_agent import call, client_with, context, settings
from test_understanding import response, understood


def catalog(**extra):
    return understood(**{"product_scope": "catalog", "product_keywords": ["法国", "纸币"], **extra})


def product(goods_id="new", name="法国100法郎纸币", quantity=2):
    return {
        "goods_id": goods_id,
        "goods_name": name,
        "quantity": quantity,
        "price": 88,
        "fetched_at": "live-test",
    }


def test_joined_catalog_phrase_splits_only_into_facets_present_in_titles():
    products = [
        product("be-1", "比利时50法郎纸币", 1),
        product("be-2", "比利时100法郎纸币", 2),
        product("nl-1", "荷兰10盾纸币", 3),
    ]
    citations = []

    def cite(value):
        citations.append(value)
        return {"evidence_id": f"e{len(citations)}", **value}

    result = search_catalog(products, ["比利时纸币"], cite)

    assert result["query_keywords"] == ["比利时", "纸币"]
    assert result["matched_count"] == 2
    assert {e["source_id"] for e in result["evidence"][:-1]} == {"be-1", "be-2"}


def test_catalog_facet_resolution_does_not_drop_an_unmatched_explicit_category():
    products = [product("be-1", "比利时50法郎纸币", 1)]

    assert resolve_catalog_keywords(products, ["比利时硬币"]) == ["比利时硬币"]


@pytest.mark.parametrize(
    "question,keywords", [("法国纸币有吗", ["法国", "纸币"]), ("有没有蓝色杯子", ["蓝色", "杯子"])]
)
async def test_named_catalog_question_is_searchable_before_selecting_a_product(question, keywords):
    result = await analyze(
        context(question),
        client_with(response(catalog(product_keywords=keywords, needs_clarification=True))),
    )
    assert result["product_scope"] == "catalog" and result["product_keywords"] == keywords
    assert result["needs_clarification"]


@pytest.mark.parametrize("words", [["捷克"], [" "], ["库存"], ["这个"]])
async def test_invented_history_and_empty_keywords_cannot_search_entire_catalog(words):
    result = await analyze(
        context("法国纸币有吗"), client_with(response(catalog(product_keywords=words)))
    )
    assert result["product_keywords"] == [] and result["needs_clarification"]


async def test_explicit_catalog_query_can_replace_stale_product_context():
    connector = AsyncMock()
    connector.products.return_value = [
        product("old", "捷克纸币", 99),
        product(),
        product("fr-2", "法国500法郎纸币", 1),
    ]
    executor = ToolExecutor(
        {
            "understanding": catalog(),
            "product_context": {"goods_id": "old"},
            "message": {"content": "法国纸币有吗"},
        },
        connector,
    )
    result = json.loads(
        (await executor.execute(call("get_shop_products", {"scope": "catalog", "query": "法国"})))[
            "content"
        ]
    )
    assert result["matched_count"] == result["available_count"] == 2
    assert "product_selection" not in executor.missing and not executor.missing
    assert {
        e["source_id"]
        for e in result["evidence"]
        if not e["source_id"].startswith("catalog-search:")
    } == {"new", "fr-2"}
    assert all("价格" not in e["evidence_text"] for e in result["evidence"])
    assert executor.has_inventory_evidence([result["evidence"][0]["evidence_id"]])
    assert {
        "get_shop_products",
        "get_product_skus",
        "transfer_conversation",
    } <= executor.registry.keys()
    connector.products.assert_awaited_once_with()


async def test_model_broadens_catalog_query_after_zero_match_using_buyer_terms(tmp_path):
    payload = context("波兰钱有吗")
    payload["understanding"] = catalog(product_keywords=["波兰钱"], quantity_meaning="inventory")
    connector = AsyncMock()
    connector.products.return_value = [product("pl", "波兰纸币", 3)]
    client = client_with(
        LLMResponse(None, [call("get_shop_products", {"scope": "catalog", "query": "波兰钱"})]),
        LLMResponse(
            None, [call("get_shop_products", {"scope": "catalog", "query": "波兰"}, "call-2")]
        ),
        LLMResponse(
            json.dumps(
                {"outcome": "draft", "content": "有的，波兰纸币目前有货。", "evidence_ids": ["e2"]},
                ensure_ascii=False,
            ),
            [],
        ),
        LLMResponse('{"supported":true,"issues":[]}', []),
    )

    result = await ModelAgent(settings(tmp_path), connector, client).generate(payload)

    assert result["outcome"] == "draft"
    assert [item["status"] for item in result["tool_trace"]] == ["ok", "ok"]
    assert connector.products.await_count == 2
    first_result = json.loads(client.chat.call_args_list[1].args[0][-1]["content"])
    second_result = json.loads(client.chat.call_args_list[2].args[0][-1]["content"])
    assert first_result["matched_count"] == 0
    assert second_result["query_keywords"] == ["波兰"]
    assert result["citations"][0]["source_id"] == "pl"


async def test_available_match_after_first_five_sold_out_items_is_not_truncated_away():
    connector = AsyncMock()
    connector.products.return_value = [product(str(i), quantity=0) for i in range(6)] + [
        product("stock", quantity=3)
    ]
    executor = ToolExecutor({"understanding": catalog()}, connector)
    result = await executor.products(executor.registry["get_shop_products"].param_model())
    assert result["matched_count"] == 7 and result["available_count"] == 1
    assert result["display_truncated"] and result["evidence"][0]["source_id"] == "stock"
    assert executor.has_inventory_evidence([result["evidence"][-1]["evidence_id"]])


@pytest.mark.parametrize(
    "quantities,proof",
    [
        ([], True),
        ([0], True),
        ([None], False),
        ([-1, 0], False),
        ([True], False),
        ([0, None, 2], True),
    ],
)
async def test_empty_search_and_unknown_stock_have_distinct_evidence(quantities, proof):
    connector = AsyncMock()
    connector.products.return_value = [
        product(str(i), quantity=q) for i, q in enumerate(quantities)
    ]
    executor = ToolExecutor({"understanding": catalog()}, connector)
    result = await executor.products(executor.registry["get_shop_products"].param_model())
    assert executor.has_inventory_evidence(list(executor.evidence)) == proof
    assert ("inventory_unavailable" in executor.missing) == (not proof)
    assert "本次名称检索范围" in result["evidence"][-1]["evidence_text"]
    if not quantities:
        assert result["matched_count"] == 0 and len(result["evidence"]) == 1


async def test_failed_catalog_request_is_not_evidence_of_absence():
    connector = AsyncMock()
    connector.products.side_effect = BusinessToolError("PRODUCT_QUERY_FAILED")
    executor = ToolExecutor({"understanding": catalog()}, connector)
    result = json.loads((await executor.execute(call("get_shop_products", {})))["content"])
    assert result["error"] == "PRODUCT_QUERY_FAILED"
    assert not executor.evidence and not executor.has_inventory_evidence([])
    assert "product_query_failed" in executor.missing


async def test_catalog_graph_discards_old_product_context_and_keeps_live_citation(tmp_path):
    payload = context("法国纸币有吗")
    payload.update(
        product_context={"goods_id": "old", "title": "捷克纸币"},
        platform_product_facts={"description": "旧商品资料"},
    )
    connector = AsyncMock()
    connector.products.return_value = [product()]
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), connector)
    graph.agent.client = client_with(
        response(catalog(needs_clarification=True)),
        LLMResponse(None, [call("get_shop_products", {"scope": "catalog", "query": "法国"})]),
        response(
            {
                "outcome": "draft",
                "content": "有的，目前法国100法郎纸币有货。",
                "evidence_ids": ["e1"],
            }
        ),
        response({"supported": True, "issues": []}),
    )
    result = await graph.generate(payload)
    assert result["outcome"] == "draft" and not result["risk_codes"]
    assert result["citations"][0]["source_id"] == "new"
    assert result["understanding"]["product_scope"] == "catalog"
    generation = graph.agent.client.chat.call_args_list[1].args[0]
    assert "捷克纸币" not in json.dumps(generation, ensure_ascii=False)
    assert payload["product_context"]["goods_id"] == "old"  # No mutation of persisted history.


async def test_model_requests_catalog_lookup_before_generating_a_grounded_reply(tmp_path):
    payload = context("法国纸币有吗")
    connector = AsyncMock()
    connector.products.return_value = [product()]
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), connector)
    graph.agent.client = client_with(
        response(catalog()),
        LLMResponse(None, [call("get_shop_products", {"scope": "catalog", "query": "法国"})]),
        response(
            {
                "outcome": "draft",
                "content": "有的，法国100法郎纸币目前有货。",
                "evidence_ids": ["e1"],
            }
        ),
        response({"supported": True, "issues": []}),
    )
    result = await graph.generate(payload)
    assert result["outcome"] == "draft"
    connector.products.assert_awaited_once_with()
    assert result["tool_trace"][0]["name"] == "get_shop_products"
    generation = graph.agent.client.chat.call_args_list[2].args[0]
    assert generation[-1]["role"] == "tool"
    assert json.loads(generation[-1]["content"])["available_count"] == 1


async def test_failed_catalog_lookup_cannot_create_a_positive_draft(tmp_path):
    connector = AsyncMock()
    connector.products.side_effect = BusinessToolError("PRODUCT_QUERY_FAILED")
    graph = DraftGraph(replace(settings(tmp_path), nlu_enabled=True), connector)
    graph.agent.client = client_with(
        response(catalog()),
        LLMResponse(None, [call("get_shop_products", {"scope": "catalog", "query": "法国"})]),
        response({"outcome": "insufficient_evidence", "content": "查询失败", "evidence_ids": []}),
    )
    result = await graph.generate(context("法国纸币有吗"))
    assert result["outcome"] != "draft" and not result["citations"]
    assert result["tool_trace"][0]["status"] == "failed"
