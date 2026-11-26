"""Deterministic minimum-price comparison across matching shop products."""

import hashlib
from datetime import datetime, timezone

from .catalog_terms import normalize_name, resolve_catalog_keywords
from .queries.sku import summarize, yuan
from .tool_registry import SkuQuery

MAX_CATALOG_PRICE_PRODUCTS = 12


def search_catalog(products, keywords, cite, *, include_prices=False):
    """Return name-matched catalog facts and inventory evidence."""
    if not keywords or any(not word.strip() for word in keywords):
        raise ValueError("CATALOG_KEYWORDS_REQUIRED")
    resolved_keywords = resolve_catalog_keywords(products, keywords)
    words = [normalize_name(word) for word in resolved_keywords]
    matching = [
        product
        for product in products
        if all(word in normalize_name(product.get("goods_name", "")) for word in words)
    ]
    available = [p for p in matching if type(p.get("quantity")) is int and p["quantity"] > 0]
    sold_out = [p for p in matching if type(p.get("quantity")) is int and p["quantity"] == 0]
    unknown = [p for p in matching if type(p.get("quantity")) is not int or p["quantity"] < 0]
    ordered = available + sold_out + unknown
    evidence, inventory_ids = [], []
    display_limit = 5 if include_prices else 3
    for product in ordered[:display_limit]:
        quantity = product.get("quantity")
        facts = [
            product.get("goods_name", ""),
            f"商品级库存：{quantity}件" if type(quantity) is int and quantity >= 0 else "库存未知",
        ]
        if include_prices and product.get("price") is not None:
            facts.append(f"接口商品价格：{product['price']}元")
        entry = cite(
            {
                "source_type": "platform_product",
                "source_id": str(product["goods_id"]),
                "source_version": product["fetched_at"],
                "fetched_at": product["fetched_at"],
                "evidence_text": "；".join(facts),
            }
        )
        evidence.append(entry)
        if type(quantity) is int and quantity > 0:
            inventory_ids.append(entry["evidence_id"])
    fetched_at = datetime.now(timezone.utc).isoformat()
    identity = hashlib.sha256("\n".join(words).encode()).hexdigest()[:16]
    summary = cite(
        {
            "source_type": "platform_product",
            "source_id": "catalog-search:" + identity,
            "source_version": fetched_at,
            "fetched_at": fetched_at,
            "evidence_text": (
                f"本次成功查询当前店铺在售商品列表，按名称同时匹配关键词{'、'.join(resolved_keywords)}，"
                f"命中{len(matching)}项，其中明确有库存{len(available)}项、库存为零{len(sold_out)}项、"
                f"库存未知{len(unknown)}项。仅代表本次名称检索范围，不能当作全店所有同类商品的完整分类或历史库存。"
            ),
        }
    )
    evidence.append(summary)
    if available or not unknown:
        inventory_ids.append(summary["evidence_id"])
    return {
        "evidence": evidence,
        "inventory_evidence_ids": inventory_ids,
        "inventory_scope": "catalog_search",
        "query_keywords": resolved_keywords,
        "matched_count": len(matching),
        "available_count": len(available),
        "unknown_stock_count": len(unknown),
        "display_truncated": len(matching) > display_limit,
        "multiple_products": len(matching) > 1,
    }


def spec_text(sku):
    parts = []
    for item in sku.get("specs", [])[:20]:
        name = item.get("name", "")
        value = item.get("value", "")
        if name or value:
            parts.append(f"{name}：{value}" if name else str(value))
    return "；".join(parts) or "平台未填写规格名称"


def _summary_evidence(cite, keywords, basis, message, source_id):
    return cite(
        {
            "source_type": "platform_product",
            "source_id": source_id,
            "source_version": datetime.now(timezone.utc).isoformat(),
            "evidence_text": message,
            "query_keywords": keywords,
            "price_basis": basis,
        }
    )


async def compare_catalog_prices(connector, keywords, price_basis, cite, products=None):
    if not keywords or any(not word.strip() for word in keywords):
        return {
            "comparison_complete": False,
            "missing": "catalog_price_query_incomplete",
            "evidence": [],
        }

    basis = "普通拼单价" if price_basis == "group" else "单买价"
    product_rows = products if products is not None else await connector.products()
    resolved_keywords = resolve_catalog_keywords(product_rows, keywords)
    words = [normalize_name(word) for word in resolved_keywords]
    matched = [
        product
        for product in product_rows
        if all(word in normalize_name(product.get("goods_name", "")) for word in words)
    ]

    query_hash = hashlib.sha256(("|".join(words) + ":" + price_basis).encode()).hexdigest()[:16]
    if not matched:
        evidence = _summary_evidence(
            cite,
            resolved_keywords,
            basis,
            f"本次成功查询在售商品目录，按名称关键词{'、'.join(resolved_keywords)}检索，未找到匹配商品。",
            "catalog-price-comparison:" + query_hash,
        )
        return {
            "comparison_complete": True,
            "matched_count": 0,
            "compared_count": 0,
            "price_basis": basis,
            "evidence": [evidence],
            "comparison_evidence_id": evidence["evidence_id"],
            "missing": "product_not_found",
        }

    # Duplicate rows can appear when a merchant listing changes during paging.
    # A missing ID remains a candidate and makes the comparison incomplete.
    unique = []
    seen = set()
    missing_ids = 0
    for product in matched:
        goods_id = str(product.get("goods_id") or "").strip()
        if not goods_id:
            missing_ids += 1
            continue
        if goods_id in seen:
            continue
        seen.add(goods_id)
        unique.append((goods_id, product))

    if len(unique) > MAX_CATALOG_PRICE_PRODUCTS:
        evidence = _summary_evidence(
            cite,
            resolved_keywords,
            basis,
            f"目录命中{len(matched)}项，超过单次完整规格核价上限{MAX_CATALOG_PRICE_PRODUCTS}项；没有计算或确认全目录最低价。",
            "catalog-price-comparison:" + query_hash,
        )
        return {
            "comparison_complete": False,
            "matched_count": len(matched),
            "compared_count": 0,
            "price_basis": basis,
            "evidence": [evidence],
            "comparison_evidence_id": evidence["evidence_id"],
            "missing": "catalog_price_scope_too_large",
        }

    product_minima = []
    product_evidence = []
    incomplete_products = []
    for goods_id, product in unique:
        try:
            detail = await connector.product_detail(goods_id)
        except Exception:
            incomplete_products.append(str(product.get("goods_name") or goods_id))
            continue
        if str(detail.get("goods_id")) != goods_id or not detail.get("fetched_at"):
            incomplete_products.append(str(product.get("goods_name") or goods_id))
            continue
        comparison = summarize(
            detail,
            SkuQuery(goods_id=goods_id, price_basis=price_basis, in_stock_only=True),
        )
        if not comparison["comparison_complete"]:
            incomplete_products.append(str(product.get("goods_name") or goods_id))
            continue
        cheapest = comparison["cheapest"]
        minimum = cheapest[0][comparison["price_key"]] if cheapest else None
        minimum_price = yuan(minimum) if minimum is not None else None
        minima = [
            {
                "goods_id": goods_id,
                "goods_name": str(product.get("goods_name") or detail.get("goods_name") or "商品")[
                    :300
                ],
                "sku_id": str(sku["sku_id"]),
                "spec": spec_text(sku)[:600],
                "price_cents": minimum,
                "price": minimum_price,
                "quantity": sku.get("quantity"),
                "fetched_at": detail["fetched_at"],
            }
            for sku in cheapest
        ]
        product_minima.extend(minima)
        product_summary = (
            f"商品：{product.get('goods_name') or detail.get('goods_name') or goods_id}；"
            + (
                f"已上架且有库存的{basis}最低为{minimum_price}元，规格：{minima[0]['spec']}；"
                f"该商品并列最低SKU数：{len(minima)}。"
                if minima
                else "完整规格中没有已上架且明确有库存的商品。"
            )
        )
        product_evidence.append(
            cite(
                {
                    "source_type": "platform_product",
                    "source_id": goods_id,
                    "source_version": detail["fetched_at"],
                    "fetched_at": detail["fetched_at"],
                    "evidence_text": product_summary,
                }
            )
        )

    complete = not incomplete_products and missing_ids == 0
    evidence = list(product_evidence)
    winner_product_ids = []
    winners = []
    if complete and product_minima:
        lowest = min(item["price_cents"] for item in product_minima)
        winners = [item for item in product_minima if item["price_cents"] == lowest]
        winner_product_ids = list(dict.fromkeys(item["goods_id"] for item in winners))
        for item in winners[:20]:
            evidence.append(
                cite(
                    {
                        "source_type": "platform_product",
                        "source_id": f"{item['goods_id']}:sku:{item['sku_id']}",
                        "source_version": item["fetched_at"],
                        "fetched_at": item["fetched_at"],
                        "evidence_text": (
                            f"商品：{item['goods_name']}；SKU：{item['sku_id']}；"
                            f"规格：{item['spec']}；{basis}：{item['price']}元；"
                            f"库存：{item['quantity']}；仅统计已上架且有库存规格。"
                        ),
                    }
                )
            )

    if not complete:
        failed = "、".join(incomplete_products[:5]) or "部分匹配商品"
        message = (
            f"关键词{'、'.join(resolved_keywords)}命中{len(matched)}项，成功完整核价{len(product_evidence)}项；"
            f"{failed}的 SKU 信息不完整或查询失败，不能确认全目录最低价。"
        )
        missing = "catalog_price_query_incomplete"
    elif not product_minima:
        message = (
            f"关键词{'、'.join(resolved_keywords)}命中{len(matched)}项，所有商品的规格均已完整查询；"
            "没有已上架且明确有库存的规格，因此无法给出当前可售最低价。"
        )
        missing = "catalog_price_not_available"
    else:
        price = yuan(min(item["price_cents"] for item in winners))
        by_product = []
        for item in product_minima:
            key = item["goods_id"]
            if key in {row["goods_id"] for row in by_product}:
                continue
            by_product.append(item)
        minima_text = "；".join(
            f"{item['goods_name']}最低{item['price']}元（{item['spec']}）" for item in by_product
        )
        winner_text = "；".join(
            f"{item['goods_name']}（{item['spec']}，{item['price']}元）" for item in winners[:8]
        )
        message = (
            f"成功从完整在售目录匹配并查询{len(matched)}项商品的全部规格；按{basis}比较"
            f"已上架且明确有库存的规格，最低为{price}元，共{len(winners)}个规格并列。"
            f"最低项：{winner_text}。各商品最低价：{minima_text}。"
            "未计入活动、优惠券或运费，不能代表买家最终到手价。"
        )
        missing = None

    summary = _summary_evidence(
        cite,
        resolved_keywords,
        basis,
        message,
        "catalog-price-comparison:" + query_hash,
    )
    evidence.append(summary)
    return {
        "comparison_complete": complete,
        "matched_count": len(matched),
        "compared_count": len(product_evidence),
        "price_basis": basis,
        "in_stock_only": True,
        "minimum_price": yuan(min(item["price_cents"] for item in winners)) if winners else None,
        "cheapest": winners[:20],
        "winner_product_ids": winner_product_ids,
        "winner_products": [
            product for goods_id, product in unique if goods_id in winner_product_ids
        ],
        "evidence": evidence,
        "comparison_evidence_id": summary["evidence_id"],
        "missing": missing,
    }
