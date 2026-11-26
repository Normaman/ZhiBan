"""SKU identity, observed units and complete-set comparison, without platform traffic."""

import json
from unittest.mock import AsyncMock

import pytest
from copilot.clients.llm import ToolCall
from copilot.agent.queries.sku import SkuQuery, lookup_skus, summarize
from copilot.agent.tool_executor import ToolExecutor
from copilot.business_errors import BusinessToolError


def sku(id, price, quantity=2, on_sale=True, **extra):
    return {
        "sku_id": str(id),
        "group_price_cents": price,
        "single_price_cents": price + 100 if type(price) is int else None,
        "quantity": quantity,
        "on_sale": on_sale,
        "specs": [{"name": "款式", "value": str(id)}],
        **extra,
    }


def detail(*rows, **extra):
    return {
        "goods_id": "goods-a",
        "goods_name": "测试商品",
        "sku_count": len(rows),
        "skus": list(rows),
        "fetched_at": "2026-10-28T13:00:00+08:00",
        "skus_truncated": False,
        **extra,
    }


@pytest.mark.parametrize("incomplete", ["unknown_quantity", "unknown_state", "truncated"])
def test_inventory_total_is_not_inferred_from_an_incomplete_sku_set(incomplete):
    source = detail(sku("a", 100, quantity=3), sku("b", 200, quantity=4))
    if incomplete == "unknown_quantity":
        source["skus"][1]["quantity"] = None
    elif incomplete == "unknown_state":
        source["skus"][1]["on_sale"] = None
    else:
        source["skus_truncated"] = True
    assert summarize(source, SkuQuery())["inventory_total"] is None


def test_inventory_total_counts_all_matching_sale_units_before_display_limit():
    source = detail(
        *[sku(str(i), 100, quantity=2) for i in range(25)],
        sku("offline", 100, quantity=99, on_sale=False),
    )
    result = summarize(source, SkuQuery())
    assert len(result["rows"]) == 20 and result["inventory_total"] == 50
    assert summarize(source, SkuQuery(spec_query="offline"))["inventory_total"] == 0
    assert summarize(source, SkuQuery(spec_query="not-present"))["inventory_total"] is None


async def test_inventory_evidence_requires_known_quantity_and_matching_product():
    connector = AsyncMock()
    connector.product_detail.return_value = detail(
        sku("zero", 100, quantity=0), sku("unknown", 100, quantity=None)
    )
    executor = ToolExecutor({"product_context": {"goods_id": "goods-a"}}, connector)
    result = await executor.skus(SkuQuery(in_stock_only=False))
    zero = next(
        e["evidence_id"] for e in result["evidence"] if e["source_id"].endswith(":sku:zero")
    )
    unknown = next(
        e["evidence_id"] for e in result["evidence"] if e["source_id"].endswith(":sku:unknown")
    )
    assert executor.has_inventory_evidence([zero])
    assert not executor.has_inventory_evidence([unknown])
    executor.context["product_context"]["goods_id"] = "other"
    assert not executor.has_inventory_evidence([zero])


def test_lowest_price_is_computed_before_presentation_limit_excluding_unavailable_skus():
    rows = [sku(i, 10000 + i) for i in range(30)] + [
        sku("lowest", 1234),
        sku("tie", 1234),
        sku("soldout", 100, quantity=0),
        sku("offline", 50, on_sale=False),
    ]
    result = summarize(detail(*rows), SkuQuery())
    assert result["comparison_complete"] and result["display_truncated"]
    assert {s["sku_id"] for s in result["cheapest"]} == {"lowest", "tie"}
    assert result["rows"][0]["group_price_cents"] == 1234
    all_stock = summarize(detail(*rows), SkuQuery(in_stock_only=False))
    assert [s["sku_id"] for s in all_stock["cheapest"]] == ["soldout"]


@pytest.mark.parametrize(
    "extra",
    [
        sku("unknown_price", None),
        sku("unknown_stock", 99, quantity=None),
        sku("unknown_sale", 99, on_sale=None),
        sku("missing-id", 99, sku_id=None),
    ],
)
def test_incomplete_competitors_cannot_produce_a_false_minimum(extra):
    result = summarize(detail(sku("known", 1200), extra), SkuQuery())
    assert not result["comparison_complete"] and result["cheapest"] == []


def test_truncated_native_rows_prevent_global_minimum_and_spec_filter_is_scoped():
    source = detail(sku("white", 2000), sku("black", 1000))
    assert summarize(source, SkuQuery(spec_query="white"))["cheapest"][0]["sku_id"] == "white"
    source["skus_truncated"] = True
    assert summarize(source, SkuQuery())["cheapest"] == []


async def test_live_tool_cites_sku_relationships_separate_prices_and_computed_minimum():
    connector = AsyncMock()
    connector.product_detail.return_value = detail(sku("white", 2699), sku("black", 3999))
    tools = ToolExecutor({"product_context": {"goods_id": "goods-a"}}, connector)
    result = await tools.skus(SkuQuery())
    connector.product_detail.assert_awaited_once_with("goods-a")
    assert result["minimum_price"] == "26.99" and result["cheapest_sku_ids"] == ["white"]
    assert result["inventory_scope"] == "sku"
    assert "普通拼单价：26.99 元" in result["evidence"][0]["evidence_text"]
    assert "单买价：27.99 元" in result["evidence"][0]["evidence_text"]
    assert all(e["source_id"].startswith("goods-a:") for e in result["evidence"])
    minimum = next(e for e in result["evidence"] if ":sku-min:" in e["source_id"])
    assert "最低为 26.99 元" in minimum["evidence_text"]
    assert "到手价" in minimum["evidence_text"]


async def test_missing_product_and_cross_product_result_never_invent_mapping():
    connector = AsyncMock()
    result = await lookup_skus(connector, {}, SkuQuery(), lambda e: e)
    assert result["error"] == "PRODUCT_SELECTION_REQUIRED"
    connector.product_detail.assert_not_called()
    connector.product_detail.return_value = detail(sku("white", 1234))
    with pytest.raises(BusinessToolError, match="PRODUCT_DETAIL_SCOPE_MISMATCH"):
        await lookup_skus(connector, {}, SkuQuery(goods_id="other"), lambda e: e)


async def test_missing_sku_details_returns_non_retryable_specific_tool_error():
    connector = AsyncMock()
    connector.product_detail.side_effect = BusinessToolError("PRODUCT_NOT_FOUND")
    executor = ToolExecutor({"product_context": {"goods_id": "goods-a"}}, connector)
    call = ToolCall.model_validate(
        {
            "id": "call-sku",
            "function": {
                "name": "get_product_skus",
                "arguments": '{"goods_id":"goods-a"}',
            },
        }
    )

    result = await executor.execute(call)

    assert json.loads(result["content"]) == {
        "error": "SKU_DETAILS_UNAVAILABLE",
        "retryable": False,
    }


async def test_single_price_has_independent_sort_order():
    connector = AsyncMock()
    connector.product_detail.return_value = detail(
        sku("a", 1000, single_price_cents=5000), sku("b", 2000, single_price_cents=3000)
    )
    result = await ToolExecutor({"product_context": {"goods_id": "goods-a"}}, connector).skus(
        SkuQuery(price_basis="single")
    )
    assert result["cheapest_sku_ids"] == ["b"] and result["minimum_price"] == "30.00"
