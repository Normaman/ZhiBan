interface OrderCard {
  orderId: string;
  goodsId: string;
  title: string;
  specification: string;
}

export function orderQueryNotice(code?: string): string {
  if (code === "ORDER_QUERY_RATE_LIMITED") return "平台限制查询频率，请稍后重试";
  if (code === "ORDER_QUERY_VERIFICATION_REQUIRED") return "订单查询需要在授权窗口完成平台安全验证";
  if (code === "ORDER_DETAIL_NOT_FOUND") return "平台未返回此订单详情，请核对店铺与订单";
  if (code?.startsWith("ORDER_SESSION_")) return "订单查询会话自动更新失败，请重新核验";
  return "订单状态查询未完成";
}

function orderPayload(content: string): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(content);
    return value && typeof value === "object" && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

export function readOrderCard(content: string): OrderCard | null {
  const value = orderPayload(content);
  if (!value) return null;
  const orderId = typeof value.order_id === "string"
    ? value.order_id.trim()
    : typeof value.order_id === "number"
      ? content.match(/"order_id"\s*:\s*([0-9]+)/)?.[1] || ""
      : "";
  const goodsId =
    typeof value.goods_id === "string"
      ? value.goods_id.trim()
      : typeof value.goods_id === "number" && Number.isSafeInteger(value.goods_id)
        ? String(value.goods_id)
        : "";
  const title =
    typeof value.goods_name === "string" ? value.goods_name.trim() : "";
  const specification = typeof value.spec === "string" ? value.spec.trim() : "";
  return orderId || title || specification
    ? { orderId, goodsId, title, specification }
    : null;
}

export function messagePreview(content: string | null, type?: string): string {
  if (!content) return "";
  const value =
    !type && content.trimStart().startsWith("{") ? orderPayload(content) : null;
  const isOrder =
    type === "order_info" ||
    (value && "order_id" in value && "afterSalesStatus" in value);
  if (!isOrder) {
    const isProduct = ["goods_inquiry", "goods_card", "goods_spec"].includes(type || "") ||
      (!type && value && "goods_id" in value && "goods_name" in value && "goods_thumb_url" in value);
    if (!isProduct) return content;
    const product = value || orderPayload(content);
    return typeof product?.goods_name === "string" && product.goods_name.trim()
      ? `商品 · ${product.goods_name.trim()}` : "商品信息";
  }
  const order = readOrderCard(content);
  return order?.title
    ? `订单 · ${order.title}`
    : order?.orderId
      ? `订单 · 尾号 ${order.orderId.slice(-4)}`
      : "订单信息";
}
