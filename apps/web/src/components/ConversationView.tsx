import { useLayoutEffect, useRef, useState } from "react";
import { Check, Sparkles, ShieldCheck, LoaderCircle } from "lucide-react";
import { buyer, time } from "../format";
import type { ConversationDetail, PerformAction } from "../types";
import { api } from "../api";
import MessageContent from "./MessageContent";
import ManualComposer from "./ManualComposer";
import { readOrderCard } from "../messages";

export default function ConversationView({
  detail,
  action,
  busy,
  online,
  onAtLatestChange,
}: {
  detail: ConversationDetail;
  action: PerformAction;
  busy: boolean;
  online: boolean;
  onAtLatestChange: (atLatest: boolean) => void;
}) {
  const scroll = useRef<HTMLDivElement>(null);
  const content = useRef<HTMLDivElement>(null);
  const following = useRef(true);
  const messageCount = useRef(detail.messages.length);
  messageCount.current = detail.messages.length;
  const seenCount = useRef(detail.messages.length);
  const lastMessage = useRef(detail.messages.at(-1)?.id);
  const [newMessages, setNewMessages] = useState(0);
  function latest(smooth = false) {
    const element = scroll.current;
    if (!element) return;
    following.current = true;
    seenCount.current = messageCount.current;
    setNewMessages(0);
    element.scrollTo({
      top: element.scrollHeight,
      behavior:
        smooth && !window.matchMedia("(prefers-reduced-motion: reduce)").matches
          ? "smooth"
          : "instant",
    });
    onAtLatestChange(true);
  }
  function scrolled() {
    const element = scroll.current;
    if (!element) return;
    const atLatest =
      element.scrollHeight - element.scrollTop - element.clientHeight < 64;
    following.current = atLatest;
    onAtLatestChange(atLatest);
    if (atLatest) {
      seenCount.current = messageCount.current;
      setNewMessages(0);
    }
  }
  useLayoutEffect(() => {
    latest();
    const observer = new ResizeObserver(() => {
      if (following.current) latest();
    });
    if (content.current) observer.observe(content.current);
    return () => {
      observer.disconnect();
      onAtLatestChange(false);
    };
  }, [detail.id]);
  useLayoutEffect(() => {
    const newest = detail.messages.at(-1);
    const ownReply =
      newest?.id !== lastMessage.current && newest?.direction === "outbound";
    lastMessage.current = newest?.id;
    if (following.current || ownReply) latest();
    else
      setNewMessages(Math.max(0, detail.messages.length - seenCount.current));
  }, [detail.messages]);
  return (
    <section className="chat">
      <div className="chat-header">
        <div className="avatar buyer-avatar">{detail.buyer_ref.slice(-2)}</div>
        <div>
          <h2>{buyer(detail.buyer_ref)}</h2>
          <p>
            拼多多 ·{" "}
            {detail.state === "transferred"
              ? "人工接待 · Agent 已暂停"
              : "Agent 辅助 · 人工确认后发送"}
          </p>
        </div>
        {detail.state === "transferred" ? (
          <button
            className="secondary"
            disabled={
              busy ||
              detail.send_commands.some((c) =>
                ["approved", "sending", "send_unknown"].includes(c.state),
              )
            }
            onClick={() =>
              action(
                () =>
                  api(`/conversations/${detail.id}/resume`, {
                    revision: detail.revision,
                  }),
                "Agent 已恢复，新的买家咨询将自动生成草稿；旧草稿不会自动重发。",
              )
            }
          >
            恢复 Agent
          </button>
        ) : (
          <button
            className="secondary"
            disabled={
              busy ||
              detail.send_commands.some((c) =>
                ["approved", "sending"].includes(c.state),
              )
            }
            onClick={() =>
              action(
                () => api(`/conversations/${detail.id}/transfer`, {}),
                "已切换人工接待，可在对话下方输入回复。",
              )
            }
          >
            人工接管
          </button>
        )}
      </div>
      <div className="chat-body">
        <div
          className="chat-scroll"
          data-testid="chat"
          ref={scroll}
          onScroll={scrolled}
        >
          <div ref={content}>
            <div className="timeline-label">本次咨询</div>
            {detail.messages.map((m) => (
              <div
                key={m.id}
                className={`message ${m.direction === "outbound" ? "outbound" : "inbound"}`}
              >
                <div className="message-meta">
                  {m.direction === "outbound"
                    ? "客服回复"
                    : m.direction === "system" ||
                        m.message_type === "system_hint"
                      ? "平台通知"
                      : buyer(detail.buyer_ref)}
                  <time>{time(m.occurred_at || m.received_at)}</time>
                </div>
                <div
                  className={`bubble${m.message_type === "order_info" ? " order-bubble" : ""}`}
                >
                  <MessageContent
                    message={m}
                    buyerRef={detail.buyer_ref}
                    orderStatus={
                      m.message_type === "order_info"
                        ? detail.agent_runs.find(
                            (run) =>
                              run.order_context?.order_id ===
                              readOrderCard(m.content)?.orderId,
                          )?.order_context
                        : undefined
                    }
                  />
                </div>
                {m.direction === "outbound" && (
                  <div className="delivery" data-testid="message-delivery">
                    {m.state === "sent" && (
                      <>
                        <Check size={12} />
                        <span>已发送</span>
                      </>
                    )}
                    <span
                        className={`buyer-read-state ${m.buyer_read_state || "unknown"}`}
                        title={
                          m.buyer_read_reason === "message_id_missing"
                            ? "这条回复缺少平台消息编号，无法匹配阅读回执；发送成功不代表买家已读。"
                            : m.buyer_read_reason === "outside_supported_range"
                              ? "该消息不在平台回执支持的范围内，暂不能确认已读状态。"
                              : m.buyer_read_state === "unknown" ||
                                  !m.buyer_read_state
                                ? "尚未收到可用于这条消息的买家阅读回执。"
                                : `来自平台阅读回执${m.buyer_read_observed_at ? `，更新于 ${time(m.buyer_read_observed_at)}` : ""}`
                        }
                      >
                        ·{" "}
                        {m.buyer_read_state === "read"
                          ? "买家已读"
                          : m.buyer_read_state === "unread"
                            ? "买家未读"
                            : "阅读状态待核实"}
                    </span>
                  </div>
                )}
              </div>
            ))}
            {detail.state === "open" &&
              detail.agent_runs[0]?.conversation_revision === detail.revision &&
              ["queued", "running"].includes(detail.agent_runs[0].state) && (
                <div className="agent-thinking">
                  <Sparkles size={16} />
                  <span>
                    {detail.agent_runs[0].state === "queued"
                      ? "咨询已排队，等待合并或上一任务完成"
                      : "正在检索资料，准备回复草稿"}
                  </span>
                  <LoaderCircle size={14} className="spin" />
                </div>
              )}
          </div>
        </div>
        {newMessages > 0 && (
          <button className="new-messages" onClick={() => latest(true)}>
            {newMessages} 条新消息 · 回到最新
          </button>
        )}
      </div>
      {detail.state === "transferred" && (
        <ManualComposer
          key={detail.id}
          detail={detail}
          online={online}
          busy={busy}
          action={action}
        />
      )}
      <div className="chat-footer">
        <ShieldCheck size={16} />
        <span>AI 草稿仅供参考，发送内容由你确认。</span>
      </div>
    </section>
  );
}
