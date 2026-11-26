import type { AgentRun, Message } from "../types";
import PlatformImage from "./PlatformImage";
import OrderMessage from "./OrderMessage";

export default function MessageContent({
  message,
  orderStatus,
  buyerRef,
}: {
  message: Message;
  orderStatus?: AgentRun["order_context"];
  buyerRef?: string;
}) {
  if (message.message_type === "order_info")
    return (
      <OrderMessage
        content={message.content}
        media={message.media}
        orderStatus={orderStatus}
        buyerRef={buyerRef}
      />
    );
  const media = message.media;
  if (!media) return <>{message.content}</>;
  if (media.kind === "image")
    return <PlatformImage src={media.image_url} alt="会话图片" />;
  const structured = ["goods_card", "goods_inquiry", "goods_spec"].includes(
    message.message_type,
  );
  let specification = "";
  if (structured) {
    try {
      const value = JSON.parse(message.content);
      if (typeof value.goods_spec === "string")
        specification = value.goods_spec;
    } catch {
      /* Preserve the text below when the source is not a structured product card. */
    }
  }
  return (
    <>
      <article className="message-product" data-testid="message-product">
        <PlatformImage src={media.image_url} alt={media.title || "商品图片"} />
        <div>
          <span className="eyebrow">
            {message.direction === "outbound" ? "推荐商品" : "咨询商品"}
          </span>
          <strong>{media.title || "平台商品"}</strong>
          {media.goods_id && <small>商品编号 {media.goods_id}</small>}
          {specification && <p>{specification}</p>}
        </div>
      </article>
      {!structured && message.content && (
        <p className="media-caption">{message.content}</p>
      )}
    </>
  );
}
