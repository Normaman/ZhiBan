import { useState, type ReactNode } from "react";
import { Inbox, Search } from "lucide-react";
import { buyer, time } from "../format";
import type { ConversationSummary } from "../types";
import { messagePreview } from "../messages";

export default function ConversationList({
  items,
  selected,
  onSelect,
  toolbar,
}: {
  toolbar?: ReactNode;
  items: ConversationSummary[];
  selected: string;
  onSelect: (id: string) => void;
}) {
  const [search, setSearch] = useState("");
  const conversations = items.filter((c) =>
    `${c.buyer_ref} ${c.preview || ""}`
      .toLowerCase()
      .includes(search.toLowerCase()),
  );
  return (
    <aside className="conversation-list">
      <div className="list-title">
        <h2>全部会话</h2>
        <div className="list-title-actions">
          <span>{conversations.length}</span>
          {toolbar}
        </div>
      </div>
      <div className="search">
        <Search size={16} />
        <input
          aria-label="搜索会话"
          placeholder="搜索访客或消息"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </div>
      <div className="list-scroll">
        {conversations.map((c) => (
          <button
            key={c.id}
            onClick={() => onSelect(c.id)}
            className={`conversation-row ${selected === c.id ? "selected" : ""}`}
          >
            <div className="avatar buyer-avatar">{c.buyer_ref.slice(-2)}</div>
            <div className="conversation-info">
              <div>
                <strong>{buyer(c.buyer_ref)}</strong>
                <time>{time(c.updated_at)}</time>
              </div>
              <p>{messagePreview(c.preview) || "暂无消息"}</p>
              <small>
                {c.state === "transferred" ? "人工接管" : "拼多多咨询"}
                {c.unread_count > 0 && (
                  <span
                    className="unread-badge"
                    title="当前账号在工作台尚未查看的买家消息，未同步为平台已读"
                    aria-label={`${c.unread_count} 条工作台未读`}
                  >
                    {c.unread_count > 99 ? "99+" : c.unread_count}
                  </span>
                )}
              </small>
            </div>
          </button>
        ))}
        {!conversations.length && (
          <div className="empty small">
            <Inbox size={28} />
            <p>{search ? "没有匹配的会话" : "等待买家咨询"}</p>
          </div>
        )}
      </div>
      <div className="list-footer">
        <i />
        <span>
          每 2 秒更新会话
          <br />
          未读数按当前工作台账号统计
        </span>
      </div>
    </aside>
  );
}
