"""Knowledge fact handlers; authority stays with Core and versioned sources."""

from ...business_errors import BusinessToolError
from ...knowledge.hybrid import search


async def knowledge(execution, source_type, params):
    entries = [
        entry
        for entry in execution.context.get("knowledge", [])
        if entry["source_type"] == source_type
        and "response_guidelines" not in entry.get("topics", [])
    ]
    if source_type == "product":
        goods_id = params.goods_id or (
            execution.context.get("product_context", {}).get("goods_id")
            if params.scope == "current"
            else None
        )
        entries = [e for e in entries if (not goods_id or str(e.get("goods_id")) == goods_id)]
    else:
        entries = [e for e in entries if params.topic in e.get("topics", [])]
    query = params.query or str(execution.context.get("message", {}).get("content", ""))
    scoped_entries = entries
    entries, retrieval = await search(execution.context, scoped_entries, query)
    # A narrow scope may be browsed when the model omitted keywords. For large
    # collections or an explicit query, no lexical hit must not mean random facts.
    if (
        not entries
        and retrieval["mode"] != "hybrid"
        and (not params.query or (source_type == "product" and goods_id))
        and len(scoped_entries) <= 5
    ):
        entries = scoped_entries
    if source_type == "product":
        missing_code = "product_specs_missing"
    elif params.topic == "shipping":
        missing_code = (
            "shipping_origin_missing"
            if "shipping_origin" in execution.context.get("understanding", {}).get("intents", [])
            and "shipping" not in execution.context.get("understanding", {}).get("intents", [])
            else "shipping_policy_missing"
        )
    elif params.topic == "faq":
        missing_code = "faq_missing"
    else:
        missing_code = "after_sales_policy_missing"
    execution.missing.discard(missing_code)
    if not entries:
        execution.missing.add(missing_code)
    return {
        "retrieval": retrieval,
        "evidence": [
            execution.cite(
                {
                    "source_type": entry["source_type"],
                    "source_id": entry["source_id"],
                    "source_version": execution.context.get("knowledge_version") or "",
                    "evidence_text": entry["text"],
                    "knowledge_topics": entry.get("topics", []),
                    "source_authority": "shop_published",
                    **(
                        {"goods_id": str(entry["goods_id"])}
                        if source_type == "product" and entry.get("goods_id")
                        else {}
                    ),
                    **{
                        key: entry[key]
                        for key in ("document_name", "source_location", "title")
                        if key in entry
                    },
                }
            )
            for entry in entries[:5]
        ],
        "multiple_products": source_type == "product"
        and len(entries) > 1
        and (
            any(not entry.get("goods_id") for entry in entries)
            or len({str(entry.get("goods_id")) for entry in entries}) > 1
        ),
    }


async def product_knowledge(execution, params):
    result = await execution.knowledge("product", params)
    goods_id = params.goods_id or (
        execution.context.get("product_context", {}).get("goods_id")
        if params.scope == "current"
        else None
    )
    if not goods_id:
        return result
    metadata = execution.product_details.get(goods_id)
    if metadata is None and callable(getattr(execution.connector, "product_detail", None)):
        try:
            detail = await execution.connector.product_detail(goods_id)
            if str(detail.get("goods_id")) != goods_id or not detail.get("fetched_at"):
                raise BusinessToolError("PRODUCT_DETAIL_SCOPE_MISMATCH")
            metadata = {
                "goods_id": goods_id,
                "goods_name": detail.get("goods_name", ""),
                "description": detail.get("description", ""),
                "category_path": detail.get("category_path", []),
                "attributes": detail.get("attributes", []),
                "specs": [sku.get("specs", []) for sku in detail.get("skus", [])],
                "fetched_at": detail["fetched_at"],
            }
            execution.product_details[goods_id] = metadata
            execution.context["platform_product_facts"] = metadata
        except Exception:
            result["platform_details_error"] = "PRODUCT_DETAILS_REFRESH_FAILED"
            return result
    if metadata is None:
        metadata = execution.context.get("platform_product_facts") or {}
    if metadata.get("goods_id") != goods_id:
        return result
    facts = []
    if metadata.get("goods_name"):
        facts.append("商品名称：" + metadata["goods_name"])
    if metadata.get("description"):
        facts.append("商品说明：" + metadata["description"])
    if metadata.get("category_path"):
        facts.append("平台商品分类：" + " / ".join(metadata["category_path"]))
    facts.extend(
        a["name"] + "：" + "、".join(a.get("values", []))
        for a in metadata.get("attributes", [])
        if a.get("name") and a.get("values")
    )
    for specs in metadata.get("specs", [])[:20]:
        if isinstance(specs, list):
            facts.extend(
                s["name"] + "：" + s["value"]
                for s in specs
                if isinstance(s, dict) and s.get("name") and s.get("value")
            )
    if not facts:
        return result
    text = "；".join(dict.fromkeys(facts))
    if len(text) > 16000:
        result["platform_details_error"] = "PRODUCT_DETAILS_TOO_LONG"
        return result
    # An exact goods ID already scopes platform facts. Natural questions such
    # as “一份几张” need not occur verbatim in “每份一张” to expose the evidence.
    result["evidence"].append(
        execution.cite(
            {
                "source_type": "product",
                "source_id": "platform-details:" + goods_id,
                "source_version": metadata.get("fetched_at") or "",
                "evidence_text": text,
            }
        )
    )
    execution.missing.discard("product_specs_missing")
    return result


async def policies(execution, params):
    return await execution.knowledge("shop_policy", params)
