from unittest.mock import AsyncMock

import pytest
from copilot.agent.queries.shipping import lookup_shipping
from copilot.agent.tool_registry import ShippingQuery
from copilot.business_errors import BusinessToolError
from test_model_agent import context


def normalized_shipping():
    return {
        "goods_id": "g1",
        "goods_name": "测试商品",
        "fetched_at": "2026-10-28T00:00:00Z",
        "shipping": {"mode": "regular", "summary": "非预售；48小时内发货及揽收"},
        "freight": {
            "status": "synced",
            "free_regions": ["浙江"],
            "undeliverable_regions": ["新疆"],
            "paid_rules": [
                {
                    "regions": ["甘肃"],
                    "unit": "piece",
                    "first_units": 1,
                    "first_fee_cents": 1500,
                    "additional_units": 1,
                    "additional_fee_cents": 0,
                    "free_min_count": None,
                    "free_min_amount_cents": None,
                }
            ],
        },
    }


@pytest.mark.parametrize(
    "province,decision",
    [
        ("浙江省", "free"),
        ("甘肃", "paid_or_conditional"),
        ("新疆", "not_delivered"),
        ("上海", "unknown"),
    ],
)
async def test_shipping_uses_normalized_product_facts(province, decision):
    business = AsyncMock()
    business.product_detail.return_value = normalized_shipping()
    payload = context()
    payload["product_context"] = {"goods_id": "g1"}
    result = await lookup_shipping(
        business, payload, ShippingQuery(province=province), lambda citation: citation
    )
    assert result["province_result"] == decision
    assert len(result["evidence"]) == 2
    assert all(e["source_type"] == "platform_product" for e in result["evidence"])
    assert "15.00元" in result["evidence"][1]["evidence_text"]
    assert "未计算本次订单最终运费" in result["evidence"][1]["evidence_text"]


async def test_missing_product_or_wrong_scope_cannot_use_another_products_shipping():
    business = AsyncMock()
    assert (
        await lookup_shipping(business, context(), ShippingQuery(), lambda citation: citation)
    )["missing"] == "product_selection"
    business.product_detail.assert_not_awaited()
    business.product_detail.return_value = {"goods_id": "other", "fetched_at": "now"}
    with pytest.raises(BusinessToolError):
        await lookup_shipping(
            business,
            context(),
            ShippingQuery(goods_id="g1"),
            lambda citation: citation,
        )


async def test_missing_normalized_shipping_data_does_not_create_shipping_claims():
    business = AsyncMock()
    business.product_detail.return_value = {
        "goods_id": "g1",
        "goods_name": "测试商品",
        "fetched_at": "now",
        "shipping": {"mode": "unknown", "summary": "发货设置未取得"},
        "freight": {"status": "unavailable", "free_regions": [], "paid_rules": []},
    }
    payload = context()
    payload["product_context"] = {"goods_id": "g1"}
    result = await lookup_shipping(business, payload, ShippingQuery(), lambda citation: citation)
    assert result["evidence"] == []
    assert result["missing"] == "product_shipping_unavailable"
