const allowedRequestFields = [
  "orderType", "afterSaleType", "pageNumber", "pageSize", "sortType",
  "remarkStatus", "urgeShippingStatus", "groupStartTime", "groupEndTime"
];
const allowedSampleFields = new Set([
  "order_sn", "goods_name", "spec", "goods_price", "order_amount", "goods_number",
  "order_status", "order_status_str", "shipping_status", "delivery_status",
  "fulfillmentStatus", "group_status", "order_time", "created_at", "shipping_amount",
  "goods_id", "thumb_url", "goods_thumb_url"
]);

function safeScalar(value, key) {
  if (value === null || ["string", "number", "boolean"].includes(typeof value)) {
    if (key === "order_sn" && value != null) return `••••${String(value).slice(-4)}`;
    if (["goods_id"].includes(key)) return "present";
    if (["thumb_url", "goods_thumb_url"].includes(key)) return value ? "present" : value;
    if (typeof value === "string") return value.slice(0, 120);
    return value;
  }
  return Array.isArray(value) ? `array(${value.length})` : "object";
}

function safeShape(value, depth = 0) {
  if (depth > 3 || value == null || typeof value !== "object") return undefined;
  if (Array.isArray(value)) return value.length ? safeShape(value[0], depth + 1) : "empty array";
  return Object.fromEntries(Object.entries(value).slice(0, 80).map(([key, child]) =>
    [key, child && typeof child === "object" ? safeShape(child, depth + 1) : typeof child]));
}

function safeSummary(payload) {
  const request = payload.requestBody || {};
  const response = payload.responseBody || {};
  const result = response.result || {};
  const first = Array.isArray(result.pageItems) ? result.pageItems[0] : null;
  const fields = first && typeof first === "object" ? Object.keys(first).slice(0, 80) : [];
  const sample = first && typeof first === "object"
    ? Object.fromEntries(Object.entries(first)
      .filter(([key]) => allowedSampleFields.has(key))
      .map(([key, value]) => [key, safeScalar(value, key)]))
    : {};
  const nestedShapes = first && typeof first === "object"
    ? Object.fromEntries(Object.entries(first)
      .filter(([, value]) => value && typeof value === "object")
      .map(([key, value]) => [key, safeShape(value)]))
    : {};
  return {
    captured_at: payload.capturedAt,
    http_status: Number(payload.status) || 0,
    success: response.success === true,
    error_code: response.errorCode ?? response.error_code ?? null,
    error_message: String(response.errorMsg ?? response.error_msg ?? "").slice(0, 100),
    total: Number(result.totalItemNum ?? result.total ?? 0),
    returned: Array.isArray(result.pageItems) ? result.pageItems.length : 0,
    row_fields: fields,
    sample_safe_fields: sample,
    nested_shapes: nestedShapes,
    request: Object.fromEntries(allowedRequestFields
      .filter((key) => request[key] !== undefined)
      .map((key) => [key, request[key]]))
  };
}

chrome.runtime.onMessage.addListener((message, sender) => {
  if (message?.type !== "order-response" || !sender.url?.startsWith("https://mms.pinduoduo.com/orders/list")) return;
  const summary = safeSummary(message.payload || {});
  chrome.storage.session.set({ latestOrderProbe: summary });
});
