import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import { errorMessage } from "../errors";
import type { KnowledgeEntry, Product, Shop } from "../types";

import KnowledgeProposalEditor, {
  type EditableProposal,
} from "./KnowledgeProposalEditor";

interface Proposal {
  id: string;
  state: string;
  revision: number;
  created_by: string;
  created_at: number;
  review_note?: string;
}
interface Detail extends Proposal, EditableProposal {
  history: {
    revision: number;
    state: string;
    actor: string;
    created_at: number;
    review_note?: string;
  }[];
  conflicts: { first: string; second: string; reason: string }[];
  changes: {
    source_id: string;
    before: KnowledgeEntry | null;
    after: KnowledgeEntry | null;
  }[];
}
const names: Record<string, string> = {
  draft: "草稿",
  pending: "待审核",
  published: "已发布",
  rejected: "已驳回",
  withdrawn: "已撤回",
};

export default function KnowledgeGovernance({
  shop,
  username,
  canReview,
  revision,
  onPublished,
  products,
}: {
  products: Product[];
  shop: Shop;
  username: string;
  canReview: boolean;
  revision: number;
  onPublished: () => Promise<void>;
}) {
  const [rows, setRows] = useState<Proposal[]>([]);
  const [selected, setSelected] = useState<Detail | null>(null);
  const [editing, setEditing] = useState<number | null>(null);
  const [note, setNote] = useState("");
  const [acknowledged, setAcknowledged] = useState(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const epoch = useRef(0);
  const path = `/shops/${shop.id}/knowledge/proposals`;
  async function load() {
    const current = epoch.current;
    const result = await api<Proposal[]>(path);
    if (current === epoch.current) setRows(result);
  }
  useEffect(() => {
    epoch.current++;
    setRows([]);
    setSelected(null);
    setEditing(null);
    setError("");
    setBusy(false);
    void load().catch((cause) => setError(errorMessage(cause)));
    return () => {
      epoch.current++;
    };
  }, [shop.id, revision]);
  async function open(id: string) {
    const current = ++epoch.current;
    setSelected(null);
    setNote("");
    setAcknowledged(false);
    setError("");
    try {
      const value = await api<Detail>(`${path}/${id}`);
      if (current === epoch.current) setSelected(value);
    } catch (cause) {
      if (current === epoch.current) setError(errorMessage(cause));
    }
  }
  async function decide(action: string) {
    if (!selected || busy) return;
    const current = epoch.current;
    setBusy(true);
    setError("");
    try {
      const prefix = ["approve", "reject"].includes(action) ? "/admin" : "";
      await api(`${prefix}${path}/${selected.id}/decisions`, {
        revision: selected.revision,
        action,
        note: note.trim(),
        acknowledge_conflicts: acknowledged,
      });
      if (current !== epoch.current) return;
      await load();
      if (current !== epoch.current) return;
      setSelected(null);
      if (action === "approve") await onPublished();
    } catch (cause) {
      if (current === epoch.current) setError(errorMessage(cause));
    } finally {
      if (current === epoch.current) setBusy(false);
    }
  }
  return (
    <details className="card knowledge-governance">
      <summary>
        变更审核 ·{" "}
        {rows.filter((row) => ["draft", "pending"].includes(row.state)).length}{" "}
        条待处理
      </summary>
      {error && (
        <p role="alert" className="callout warning">
          {error}
        </p>
      )}
      {selected && editing !== null && (
        <KnowledgeProposalEditor
          key={`${selected.id}:${selected.revision}`}
          proposal={selected}
          path={path}
          products={products}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            if (editing !== epoch.current) return;
            const current = epoch.current;
            await load();
            if (current !== epoch.current) return;
            setEditing(null);
            await open(selected.id);
          }}
        />
      )}
      <div className="knowledge-proposal-grid">
        <nav aria-label="知识变更">
          {rows.length ? (
            rows.map((row) => (
              <button
                key={row.id}
                className="secondary"
                onClick={() => void open(row.id)}
                disabled={busy}
              >
                {names[row.state]} · {row.created_by} ·{" "}
                {new Date(row.created_at).toLocaleString("zh-CN")}
              </button>
            ))
          ) : (
            <p>暂无变更草稿。</p>
          )}
        </nav>
        {selected && (
          <section className="knowledge-review-detail">
            <h3>
              {names[selected.state]} · {selected.changes.length} 项变更
            </h3>
            {selected.created_by === username &&
              ["draft", "rejected", "withdrawn"].includes(selected.state) && (
                <button
                  className="secondary"
                  disabled={busy}
                  onClick={() => setEditing(epoch.current)}
                >
                  {selected.state === "draft" ? "编辑草稿" : "修改并重新提交"}
                </button>
              )}
            {(selected.base_version || "") !==
              selected.current_base_version && (
              <p className="callout warning">
                知识库已有新版本，请先编辑草稿、核对合并结果，再提交审核。
              </p>
            )}
            {selected.changes.map((change) => (
              <details key={change.source_id}>
                <summary>
                  {change.after?.title ||
                    change.before?.title ||
                    change.source_id}{" "}
                  · {change.before ? (change.after ? "修改" : "移除") : "新增"}
                </summary>
                {change.before && (
                  <div>
                    <strong>当前内容</strong>
                    <p className="knowledge-diff-text">{change.before.text}</p>
                  </div>
                )}
                {change.after && (
                  <div>
                    <strong>变更后</strong>
                    <p className="knowledge-diff-text">{change.after.text}</p>
                    {(change.after.valid_from || change.after.valid_until) && (
                      <small>
                        有效期：{change.after.valid_from || "立即"} 至{" "}
                        {change.after.valid_until || "长期"}
                      </small>
                    )}
                  </div>
                )}
              </details>
            ))}
            {!!selected.conflicts.length && (
              <p className="callout warning">
                发现 {selected.conflicts.length}{" "}
                组重复内容或同名不同内容，请核实适用范围。这是初步检查，不代表已识别所有语义冲突。
              </p>
            )}
            {selected.review_note && <p>处理说明：{selected.review_note}</p>}
            {!!selected.history.length && (
              <details>
                <summary>修改与审核记录</summary>
                <ol className="knowledge-review-history">
                  {selected.history.map((item) => (
                    <li key={item.revision}>
                      {names[item.state]} · {item.actor} ·{" "}
                      {new Date(item.created_at).toLocaleString("zh-CN")}
                      {item.review_note && <p>{item.review_note}</p>}
                    </li>
                  ))}
                </ol>
              </details>
            )}
            {["draft", "pending"].includes(selected.state) && (
              <>
                <label>
                  处理说明
                  <input
                    value={note}
                    onChange={(event) => setNote(event.target.value)}
                    maxLength={1000}
                    disabled={busy}
                  />
                </label>
                {canReview &&
                  selected.state === "pending" &&
                  !!selected.conflicts.length && (
                    <label>
                      <input
                        type="checkbox"
                        checked={acknowledged}
                        onChange={(event) =>
                          setAcknowledged(event.target.checked)
                        }
                      />
                      已核对重复内容和适用条件
                    </label>
                  )}
                <div className="heading-actions">
                  {selected.created_by === username &&
                    selected.state === "draft" && (
                      <button
                        className="primary"
                        disabled={busy || !note.trim()}
                        onClick={() => void decide("submit")}
                      >
                        提交审核
                      </button>
                    )}
                  {canReview && selected.state === "pending" && (
                    <>
                      <button
                        className="primary"
                        disabled={
                          busy ||
                          !note.trim() ||
                          (!!selected.conflicts.length && !acknowledged)
                        }
                        onClick={() => void decide("approve")}
                      >
                        审核并发布
                      </button>
                      <button
                        className="secondary"
                        disabled={busy || !note.trim()}
                        onClick={() => void decide("reject")}
                      >
                        驳回
                      </button>
                    </>
                  )}
                  {selected.created_by === username && (
                    <button
                      className="secondary"
                      disabled={busy || !note.trim()}
                      onClick={() => void decide("withdraw")}
                    >
                      撤回
                    </button>
                  )}
                </div>
              </>
            )}
          </section>
        )}
      </div>
    </details>
  );
}
