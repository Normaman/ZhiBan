"""Live, product-scoped SKU evidence and deterministic price comparison."""

import hashlib
from decimal import Decimal

from ...business_errors import BusinessToolError
from ..tool_registry import SkuQuery as SkuQuery


def yuan(cents):
    return f"{Decimal(cents) / 100:.2f}" if type(cents) is int and cents > 0 else None


def summarize(detail, params):
    """Compare every observed row before limiting presentation; unknown values never become zero."""
    key = "group_price_cents" if params.price_basis == "group" else "single_price_cents"
    query = params.spec_query.strip().casefold()
    rows = [
        s
        for s in detail.get("skus", [])
        if not query
        or all(
            word
            in " ".join(
                f"{a.get('name', '')} {a.get('value', '')}" for a in s.get("specs", [])
            ).casefold()
            for word in query.split()
        )
    ]
    complete = not detail.get("skus_truncated", False) and len(
        detail.get("skus", [])
    ) == detail.get("sku_count")
    candidates = []
    for sku in rows:
        if sku.get("on_sale") is False:
            continue
        qty = sku.get("quantity")
        if params.in_stock_only and type(qty) is int and qty == 0:
            continue
        if sku.get("on_sale") is not True or (
            params.in_stock_only and (type(qty) is not int or qty < 0)
        ):
            complete = False
            continue
        if not sku.get("sku_id") or yuan(sku.get(key)) is None:
            complete = False
            continue
        candidates.append(sku)
    candidates.sort(key=lambda s: (s[key], str(s["sku_id"])))
    inventory_complete = (
        bool(rows)
        and not detail.get("skus_truncated", False)
        and len(detail.get("skus", [])) == detail.get("sku_count")
        and all(
            s.get("on_sale") is False
            or (s.get("on_sale") is True and type(s.get("quantity")) is int and s["quantity"] >= 0)
            for s in rows
        )
    )
    cheapest = (
        [s for s in candidates if s[key] == candidates[0][key]] if candidates and complete else []
    )
    # Also expose unavailable/unknown matching rows for explicit specification questions.
    ordered = candidates + [s for s in rows if s not in candidates]
    return {
        "rows": ordered[:20],
        "matched_count": len(rows),
        "display_truncated": len(ordered) > 20,
        "comparison_complete": complete,
        "cheapest": cheapest,
        "price_key": key,
        "inventory_total": sum(s["quantity"] for s in rows if s.get("on_sale") is True)
        if inventory_complete
        else None,
    }


async def lookup_skus(connector, context, params, cite):
    goods = params.goods_id or context.get("product_context", {}).get("goods_id")
    if not goods:
        return {
            "error": "PRODUCT_SELECTION_REQUIRED",
            "missing": "product_selection",
            "evidence": [],
        }
    try:
        detail = await connector.product_detail(goods)
    except BusinessToolError as error:
        if str(error) == "PRODUCT_NOT_FOUND":
            raise BusinessToolError("SKU_DETAILS_UNAVAILABLE") from error
        raise
    if str(detail.get("goods_id")) != goods or not detail.get("fetched_at"):
        raise BusinessToolError("PRODUCT_DETAIL_SCOPE_MISMATCH")
    result = summarize(detail, params)
    basis = "普通拼单价" if params.price_basis == "group" else "单买价"
    evidence = []
    inventory_evidence_ids = []
    for sku in result["rows"]:
        spec = (
            "；".join(f"{s.get('name', '')}：{s.get('value', '')}" for s in sku.get("specs", []))
            or "平台未填写规格名称"
        )
        quantity = sku.get("quantity")
        facts = [
            detail.get("goods_name", ""),
            f"规格：{spec}",
            f"SKU：{sku.get('sku_id') or '未知'}",
            f"库存：{quantity}件（可售件数）"
            if type(quantity) is int and quantity >= 0
            else "库存未知，不能承诺有货",
            "已上架"
            if sku.get("on_sale") is True
            else "已下架"
            if sku.get("on_sale") is False
            else "上下架状态未知",
        ]
        for field, label in [("group_price_cents", "普通拼单价"), ("single_price_cents", "单买价")]:
            price = yuan(sku.get(field))
            facts.append(f"{label}：{price} 元" if price is not None else f"{label}未知")
        evidence.append(
            cite(
                {
                    "source_type": "platform_product",
                    "source_id": f"{goods}:sku:{sku.get('sku_id') or len(evidence)}",
                    "source_version": detail["fetched_at"],
                    "fetched_at": detail["fetched_at"],
                    "evidence_text": "；".join(facts),
                }
            )
        )
        if type(quantity) is int and quantity >= 0:
            inventory_evidence_ids.append(evidence[-1]["evidence_id"])
    cheapest = result["cheapest"]
    minimum = yuan(cheapest[0][result["price_key"]]) if cheapest else None
    if cheapest:
        ids = [str(s["sku_id"]) for s in cheapest[:20]]
        comparison = f"按{basis}比较{'已上架且有库存' if params.in_stock_only else '已上架'}的匹配规格，最低为 {minimum} 元，共 {len(cheapest)} 项并列；对应 SKU：{'、'.join(ids)}。"
        identity = hashlib.sha256(
            f"{params.price_basis}:{params.in_stock_only}:{params.spec_query}".encode()
        ).hexdigest()[:16]
        evidence.append(
            cite(
                {
                    "source_type": "platform_product",
                    "source_id": f"{goods}:sku-min:{identity}",
                    "source_version": detail["fetched_at"],
                    "fetched_at": detail["fetched_at"],
                    "evidence_text": comparison
                    + "未计算活动、优惠券和运费，不代表买家最终到手价。",
                }
            )
        )
    if result["inventory_total"] is not None:
        identity = hashlib.sha256(params.spec_query.encode()).hexdigest()[:16]
        evidence.append(
            cite(
                {
                    "source_type": "platform_product",
                    "source_id": f"{goods}:sku-stock:{identity}",
                    "source_version": detail["fetched_at"],
                    "fetched_at": detail["fetched_at"],
                    "evidence_text": (
                        f"{detail.get('goods_name', '')}；"
                        f"{'规格匹配范围：' + params.spec_query if params.spec_query else '该商品全部规格'}；"
                        f"已完整核对匹配规格，已上架规格的可售库存合计{result['inventory_total']}件。"
                        "件数是平台销售数量，不是包装内含数量；未作单位换算。"
                    ),
                }
            )
        )
        inventory_evidence_ids.append(evidence[-1]["evidence_id"])
    return {
        "goods_id": goods,
        "inventory_scope": "sku",
        "inventory_total": result["inventory_total"],
        "inventory_evidence_ids": inventory_evidence_ids,
        "price_basis": basis,
        "evidence": evidence,
        "comparison_complete": result["comparison_complete"],
        "cheapest_sku_ids": [str(s["sku_id"]) for s in cheapest[:20]],
        "cheapest_count": len(cheapest),
        "minimum_price": minimum,
        "matched_count": result["matched_count"],
        "display_truncated": result["display_truncated"],
        "comparison_note": "只可在 comparison_complete=true 且 minimum_price 非空时断言匹配范围的最低价。未计算活动、优惠券和运费。",
        "missing": "sku_details_incomplete"
        if not result["comparison_complete"]
        else "sku_not_found"
        if not result["matched_count"]
        else None,
    }
