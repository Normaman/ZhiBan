"""Read current product delivery settings; never infer a universal free-shipping promise."""

from ...business_errors import BusinessToolError

PROVINCES = dict(
    enumerate(
        [
            "北京", "安徽", "福建", "甘肃", "广东", "广西", "贵州", "海南", "河北", "河南",
            "黑龙江", "湖北", "湖南", "吉林", "江苏", "江西", "辽宁", "内蒙古", "宁夏", "青海",
            "山东", "山西", "陕西", "上海", "四川", "天津", "西藏", "新疆", "云南", "浙江",
            "重庆", "香港", "澳门", "台湾",
        ],
        2,
    )
)


def duration(seconds):
    if type(seconds) is not int or seconds <= 0:
        return ""
    number, unit = (seconds / 3600, "小时") if seconds <= 172800 else (seconds / 86400, "天")
    return f"{number:g}{unit}内发货及揽收"


async def lookup_shipping(connector, context, params, cite):
    goods = params.goods_id or context.get("product_context", {}).get("goods_id")
    if not goods:
        return {"evidence": [], "missing": "product_selection"}
    detail = await connector.product_detail(goods)
    if str(detail.get("goods_id")) != goods or not detail.get("fetched_at"):
        raise BusinessToolError("PRODUCT_DETAIL_SCOPE_MISMATCH")
    shipping, freight = detail.get("shipping", {}), detail.get("freight", {})
    if (
        shipping.get("mode") == "unknown"
        and freight.get("status") != "synced"
        and not detail.get("_query_session_refreshed")
    ):
        detail = await connector.product_detail(goods)
        if str(detail.get("goods_id")) != goods or not detail.get("fetched_at"):
            raise BusinessToolError("PRODUCT_DETAIL_SCOPE_MISMATCH")
        shipping, freight = detail.get("shipping", {}), detail.get("freight", {})
    lines = []
    if shipping.get("mode") not in (None, "unknown"):
        lines.append(shipping["summary"])
    if shipping.get("mode") == "by_sku" and params.spec_query:
        matches = [
            sku
            for sku in detail.get("skus", [])
            if all(
                word.casefold() in " ".join(s["value"] for s in sku.get("specs", [])).casefold()
                for word in params.spec_query.split()
            )
        ]
        if len(matches) == 1 and not detail.get("skus_truncated"):
            sku = matches[0]
            if sku.get("presale_deadline"):
                lines.append(
                    f"规格{params.spec_query}：预售，{sku['presale_deadline']}前发货及揽收"
                )
            elif sku.get("shipment_limit_seconds"):
                texts = [
                    "当日发货及揽收"
                    if seconds == 1
                    else duration(seconds)
                    if seconds != 2
                    else "按规格设置，需人工核对"
                    for seconds in sku["shipment_limit_seconds"]
                ]
                lines.append(f"规格{params.spec_query}：{'、'.join(texts)}")
    evidence = []

    def reference(suffix, text):
        return cite(
            {
                "source_type": "platform_product",
                "source_id": f"{goods}:{suffix}",
                "source_version": detail["fetched_at"],
                "fetched_at": detail["fetched_at"],
                "evidence_text": detail.get("goods_name", "") + "；" + text,
            }
        )

    if lines:
        evidence.append(reference("shipping", "；".join(lines)))
    freight_lines = []
    decision = "unknown"
    if freight.get("status") == "synced":
        freight_lines = [
            "包邮地区：" + ("、".join(freight["free_regions"]) or "无"),
            "不配送地区：" + ("、".join(freight["undeliverable_regions"]) or "无"),
        ]
        for row in freight["paid_rules"]:
            first = (
                f"{row['first_units']}件"
                if row["unit"] == "piece"
                else f"{row['first_units'] / 1000:g}kg"
            )
            extra = (
                f"{row['additional_units']}件"
                if row["unit"] == "piece"
                else f"{row['additional_units'] / 1000:g}kg"
            )
            text = f"{'、'.join(row['regions'])}：{first}内{row['first_fee_cents'] / 100:.2f}元，每增加{extra}加{row['additional_fee_cents'] / 100:.2f}元"
            if row.get("free_min_count"):
                text += f"；满{row['free_min_count']}件包邮"
            if row.get("free_min_amount_cents"):
                text += f"；满{row['free_min_amount_cents'] / 100:.2f}元包邮"
            freight_lines.append(text)
        text = "；".join(freight_lines)
        if len(text) <= 12000:
            evidence.append(
                reference("freight", text + "；未计算本次订单最终运费，未列地区不承诺包邮")
            )
        name = params.province.strip()
        province = next(
            (
                p
                for p in PROVINCES.values()
                if name
                in (
                    p,
                    p + "省",
                    p + "市",
                    p + "自治区",
                    p + "壮族自治区",
                    p + "回族自治区",
                    p + "维吾尔自治区",
                    p + "特别行政区",
                )
            ),
            None,
        )
        if province and len(text) <= 12000:
            paid = any(province in r["regions"] for r in freight["paid_rules"])
            free = province in freight["free_regions"]
            decision = (
                "not_delivered"
                if province in freight["undeliverable_regions"]
                else "unknown"
                if paid and free
                else "paid_or_conditional"
                if paid
                else "free"
                if free
                else "unknown"
            )
    return {
        "evidence": evidence,
        "shipping": shipping,
        "province_result": decision,
        "freight_status": freight.get("status", "unavailable"),
        "missing": "product_shipping_unavailable" if not evidence else None,
    }
