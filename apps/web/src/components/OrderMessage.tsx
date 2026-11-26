import { ReceiptText } from "lucide-react";
import { useRef, useState } from "react";
import { api, ApiError } from "../api";
import { orderQueryNotice, readOrderCard } from "../messages";
import type { AgentRun, MessageMedia } from "../types";
import PlatformImage from "./PlatformImage";

export default function OrderMessage({
  content,
  media,
  orderStatus,
  buyerRef,
}: {
  content: string;
  media?: MessageMedia | null;
  orderStatus?: AgentRun["order_context"];
  buyerRef?: string;
}) {
  const order = readOrderCard(content);
  const [queried, setQueried] = useState<AgentRun["order_context"]>();
  const [busy, setBusy] = useState(false);
  const [verifying, setVerifying] = useState(false);
  const [error, setError] = useState("");
  const viewer = useRef<Window | null>(null);
  const current = queried && Date.parse(queried.fetched_at || "") >= Date.parse(orderStatus?.fetched_at || "1970-01-01") ? queried : orderStatus;
  async function query(verify: boolean) {
    if (!order?.orderId || !buyerRef) return;
    const popup = verify ? window.open("about:blank", "pdd-order-verification") : null;
    if (verify) viewer.current = popup;
    setBusy(true); setError("");
    try {
      const result = await api<{order?: AgentRun["order_context"]; viewer_url?: string}>(
        verify ? "/admin/shop/order-query/verification" : "/shop/order-query/retry",
        {order_id: order.orderId, buyer_ref: buyerRef});
      if (result.order) { setQueried(result.order); popup?.close(); }
      if (result.viewer_url) {
        setVerifying(true);
        if (popup) popup.location.href = result.viewer_url;
        else setError("浏览器拦截了窗口，请允许此站点弹出窗口后重试。");
      } else if (!verify && result.order?.order_status !== "unknown") {
        setVerifying(false); viewer.current?.close(); viewer.current = null;
      }
    } catch (failure) {
      popup?.close();
      setError(failure instanceof ApiError && failure.status === 403 ? "请联系店铺管理员核验订单查询。" : "订单查询未完成，请稍后重试。");
    } finally { setBusy(false); }
  }
  async function closeVerification() {
    try {
      await api("/admin/shop/order-query/close", {}); setVerifying(false);
      viewer.current?.close(); viewer.current = null;
    }
    catch { setError("核验窗口暂时无法关闭，请稍后重试。"); }
  }
  // The media belongs to this exact order-card message; comparing loosely
  // normalized product IDs here can hide a valid image from the same card.
  const image = media?.kind === "product" ? media.image_url : undefined;
  return (
    <article
      className="message-order"
      data-testid="message-order"
      aria-label="订单卡片"
    >
      <div className="order-card-heading">
        <ReceiptText size={16} aria-hidden="true" />
        <span>订单信息</span>
      </div>
      {order ? (
        <>
          <div className="order-card-product">
            <PlatformImage src={image} alt={order.title || "订单商品图片"} />
            <div>
              <strong>{order.title || "订单商品"}</strong>
              {order.specification && <p>规格：{order.specification}</p>}
            </div>
          </div>
          {order.orderId && (
            <div className="order-card-number">
              <span>订单号</span>
              <span>{order.orderId}</span>
            </div>
          )}
          {order.orderId && (
            <div className="order-card-status" data-testid="order-card-status">
              <span>订单状态</span>
              <strong>
                {current?.order_status &&
                current.order_status !== "unknown"
                  ? current.status_text || current.order_status
                  : current?.status_error_code
                    ? orderQueryNotice(current.status_error_code)
                    : "尚未核实"}
              </strong>
            </div>
          )}
          {order.orderId && buyerRef && (
            <div className="order-query-actions">
              {current?.status_error_code === "ORDER_QUERY_VERIFICATION_REQUIRED" && (
                <button disabled={busy} onClick={() => void query(true)}>安全核验</button>
              )}
              <button disabled={busy} onClick={() => void query(false)}>{busy ? "查询中…" : verifying ? "已完成验证，重新查询" : "查询状态"}</button>
              {verifying && <button disabled={busy} onClick={() => void closeVerification()}>关闭核验窗口</button>}
              {error && <small role="alert">{error}</small>}
            </div>
          )}
        </>
      ) : (
        <p>订单内容暂无法展示，请在拼多多核对。</p>
      )}
    </article>
  );
}
