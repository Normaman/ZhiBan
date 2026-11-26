"""Orders fact handlers; authority stays with Core and versioned sources."""

from ...business_errors import BusinessToolError


async def order_details(execution, params):
    order = execution.context.get("order_context", {})
    if not order.get("fetched_at") or not order.get("order_id"):
        reference = execution.context.get("order_reference", {})
        buyer = execution.context.get("buyer_ref")
        if not reference.get("order_id") or not buyer:
            return {
                "error": "ORDER_REFERENCE_REQUIRED",
                "missing_fields": ["verified_order_reference"],
                "evidence": [],
            }
        order = await execution.connector.order_detail(reference["order_id"], buyer)
        if order.get("order_id") != reference["order_id"] or (
            order.get("buyer_ref") is not None and order["buyer_ref"] != buyer
        ):
            raise BusinessToolError("ORDER_BUYER_MISMATCH")
        execution.context["order_context"] = order
        if not order.get("fetched_at"):
            return {"error": "ORDER_FACTS_REQUIRED", "evidence": []}
    facts = ["订单商品：" + str(order.get("goods_name") or "未取得")]
    if order.get("order_status", "unknown") != "unknown" and order.get("status_text"):
        facts.append("平台订单状态：" + order["status_text"])
        facts.append("本次仅可解释订单状态，未执行催单、加急或发货操作")
    else:
        facts.append("订单状态未知，不能承诺安排发货")
    if order.get("created_at"):
        facts.append("下单时间：" + order["created_at"])
    if order.get("spec"):
        facts.append("订单规格：" + order["spec"])
    if order.get("purchased_quantity") is not None:
        facts.append(f"订单购买数量：{order['purchased_quantity']}件；不是当前库存")
    missing_labels = {
        "order_status": "订单状态",
        "created_at": "下单时间",
        "collected": "揽收",
        "logistics_status": "物流轨迹",
        "signed": "签收",
        "freight_insurance": "运费险",
        "goods_category": "类目",
    }
    missing = [missing_labels[k] for k in order.get("missing_fields", []) if k in missing_labels]
    if missing:
        facts.append("、".join(missing) + "尚未取得，不能推断")
    return {
        "evidence": [
            execution.cite(
                {
                    "source_type": "platform_order",
                    "source_id": order["order_id"],
                    "source_version": order["fetched_at"],
                    "fetched_at": order["fetched_at"],
                    "evidence_text": "；".join(facts),
                }
            )
        ],
        "missing_fields": order.get("missing_fields", []),
    }
