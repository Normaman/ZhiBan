import { useRef, useState } from "react";
import { Send } from "lucide-react";
import { api, ApiError } from "../api";
import { label } from "../format";
import type { ConversationDetail, PerformAction } from "../types";

export default function ManualComposer({
  detail,
  online,
  busy,
  action,
}: {
  detail: ConversationDetail;
  online: boolean;
  busy: boolean;
  action: PerformAction;
}) {
  const [content, setContent] = useState("");
  const [uncertain, setUncertain] = useState(false);
  const pending = useRef<{
    request_id: string;
    revision: number;
    content: string;
    answered_message_ids: string[];
  } | null>(null);
  const submitting = useRef(false);
  const commands = detail.send_commands;
  const inProgress = commands.some((c) =>
    ["approved", "sending"].includes(c.state),
  );
  const unresolved = commands.some((c) => c.state === "send_unknown");
  const enabled = detail.send_enabled === true;
  const blocked = !enabled || !online || unresolved || inProgress;
  async function submit() {
    if (
      submitting.current ||
      busy ||
      (!uncertain && (blocked || !content.trim()))
    )
      return;
    submitting.current = true;
    await action(async () => {
      pending.current ||= {
        request_id: crypto.randomUUID(),
        revision: detail.revision,
        content: content.trim(),
        answered_message_ids: [],
      };
      try {
        await api(
          `/conversations/${detail.id}/manual-replies`,
          pending.current,
        );
        pending.current = null;
        setUncertain(false);
        setContent("");
      } catch (error) {
        if (error instanceof ApiError && error.status < 500) {
          pending.current = null;
          setUncertain(false);
        } else setUncertain(true);
        throw error;
      }
    }, "人工回复已提交，等待平台发送结果。");
    submitting.current = false;
  }
  return (
    <div className="manual-composer">
      {!!detail.manual_replies?.some((r) => r.state !== "sent") && (
        <div className="manual-reply-status" aria-label="人工回复发送记录">
          {detail.manual_replies
            .filter((r) => r.state !== "sent")
            .map((r) => (
              <div key={r.id}>
                <p>{r.content}</p>
                <span>
                  {r.state === "approved" ? "等待发送" : label(r.state)}
                </span>
                {r.state === "send_failed" && (
                  <button
                    className="secondary"
                    disabled={
                      busy ||
                      blocked ||
                      r.conversation_revision !== detail.revision
                    }
                    onClick={() =>
                      action(
                        () => api(`/send-commands/${r.command_id}/retry`, {}),
                        "正在重试这条人工回复。",
                      )
                    }
                  >
                    重试此回复
                  </button>
                )}
              </div>
            ))}
        </div>
      )}
      <label htmlFor="manual-reply-content">人工回复</label>
      <textarea
        id="manual-reply-content"
        rows={3}
        maxLength={4000}
        value={content}
        readOnly={busy || uncertain}
        onChange={(e) => setContent(e.target.value)}
        placeholder="输入要发送给买家的回复，点击发送后提交。"
      />
      <div className="manual-composer-actions">
        <small>{content.length} / 4000</small>
        <button
          className="primary"
          disabled={busy || (!uncertain && (blocked || !content.trim()))}
          onClick={submit}
        >
          <Send size={16} />
          {uncertain
            ? "确认上次提交"
            : busy
              ? "正在提交…"
              : "发送人工回复"}
        </button>
      </div>
      <p className="manual-composer-hint" role="status">
        {uncertain
          ? "网络中断，提交结果待确认。请先确认上次提交，避免重复发送。"
          : unresolved
            ? "有一条回复送达情况待核实，暂不能继续发送或恢复 Agent。"
            : !enabled
              ? "此会话尚未开放真实发送，可以先编辑文本。"
              : !online
                ? "店铺连接已断开，恢复在线后才能发送。"
                : inProgress
                  ? "上一条回复正在发送，请等待结果。"
                : "点击发送后将回复真实买家。"}
      </p>
    </div>
  );
}
