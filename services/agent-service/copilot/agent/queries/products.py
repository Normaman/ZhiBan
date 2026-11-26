"""Catalog fact handlers; authority stays with Core and versioned sources."""

import re

from ..catalog import compare_catalog_prices as compare_catalog_products
from ..catalog_terms import catalog_query_terms
from ..catalog import search_catalog


async def products(execution, params):
    products = await execution.connector.products()
    understanding = execution.context.get("understanding", {})
    if params.scope == "catalog" or (
        understanding.get("product_scope") == "catalog" and not params.goods_id
    ):
        keywords = catalog_query_terms(
            params.query,
            understanding.get("product_keywords", []),
            execution.context.get("message", {}).get("content", ""),
        )
        if not keywords:
            execution.missing.add("product_selection")
            return {"error": "CATALOG_KEYWORDS_REQUIRED", "evidence": []}
        execution.catalog_products = products
        if params.comparison == "lowest_in_stock_price":
            return await execution.catalog_price_comparison(params, products, keywords)
        result = search_catalog(
            products,
            keywords,
            execution.cite,
            include_prices="price" in understanding.get("intents", []),
        )
        execution.catalog_inventory_evidence.update(result.pop("inventory_evidence_ids"))
        result["card_options"] = execution.remember_cards(products, result["evidence"])
        execution.missing.difference_update(
            {
                "product_query_failed",
                "product_not_found",
                "product_selection",
                "inventory_unavailable",
            }
        )
        if not result["matched_count"]:
            execution.missing.add("product_not_found")
        if not result["available_count"] and result["unknown_stock_count"]:
            execution.missing.add("inventory_unavailable")
        return result
    context_id = execution.context.get("product_context", {}).get("goods_id")
    generic = not params.query.strip() or bool(
        re.fullmatch(
            r"(?:这款|这个|该|商品|当前|现在|目前|请问|还有|有|的|是|吗|呢|啊|？|\?|库存|现货|有货|多少|剩余|剩|几|张|件|个|枚|片|包|盒|套|袋|本|条|双|瓶|支|卷|数量|\s)+",
            params.query,
        )
    )
    goods_id = params.goods_id or (context_id if generic and params.scope == "current" else None)
    query = "" if goods_id and generic else params.query
    matching = [
        p
        for p in products
        if (not goods_id or str(p.get("goods_id")) == goods_id)
        and (not query or query.casefold() in p.get("goods_name", "").casefold())
    ]
    execution.missing.difference_update(
        {
            "product_query_failed",
            "product_not_found",
            "product_selection",
            "inventory_unavailable",
        }
    )
    if not matching:
        execution.missing.add("product_not_found")
    elif len(matching) > 1:
        execution.missing.add("product_selection")
    elif matching[0].get("quantity") is None:
        execution.missing.add("inventory_unavailable")
    evidence = []
    for p in matching[:5]:
        facts = [p.get("goods_name", "")]
        if p.get("price") is not None:
            facts.append(f"接口显示价格：{p['price']} 元")
        if p.get("quantity") is not None:
            facts.append(
                f"接口商品级库存：{p['quantity']}件（可售件数）；未取得具体颜色、尺码的分规格库存"
            )
        else:
            facts.append("库存未知，不能承诺有货")
        evidence.append(
            execution.cite(
                {
                    "source_type": "platform_product",
                    "source_id": str(p["goods_id"]),
                    "source_version": p["fetched_at"],
                    "fetched_at": p["fetched_at"],
                    "evidence_text": "；".join(facts),
                }
            )
        )
        if type(p.get("quantity")) is int and p["quantity"] >= 0:
            execution.inventory_evidence[evidence[-1]["evidence_id"]] = str(p["goods_id"])
    return {
        "evidence": evidence,
        "card_options": execution.remember_cards(matching, evidence),
        "multiple_products": len(matching) > 1,
        "inventory_scope": "product",
    }


async def catalog_price_comparison(execution, params, products, keywords):
    execution.missing.difference_update(
        {
            "product_query_failed",
            "product_not_found",
            "catalog_price_scope_too_large",
            "catalog_price_query_incomplete",
            "catalog_price_not_available",
        }
    )
    result = await compare_catalog_products(
        execution.connector,
        keywords,
        params.price_basis,
        execution.cite,
        products=products,
    )
    missing = result.get("missing")
    if missing:
        execution.missing.add(missing)
    winner_ids = set(result.get("winner_product_ids", []))
    winner_products = result.pop("winner_products", [])
    card_evidence = [
        execution.cite(
            {
                "source_type": "platform_product",
                "source_id": str(product["goods_id"]),
                "source_version": product["fetched_at"],
                "fetched_at": product["fetched_at"],
                "evidence_text": (
                    f"店铺在售商品卡片：{product.get('goods_name', '')}；"
                    f"平台目录显示价格：{product.get('price') or '未提供'}。"
                ),
            }
        )
        for product in winner_products
        if str(product.get("goods_id")) in winner_ids and product.get("fetched_at")
    ]
    result["card_options"] = execution.remember_cards(
        winner_products,
        card_evidence,
    )
    return result
