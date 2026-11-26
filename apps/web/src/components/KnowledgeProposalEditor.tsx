import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import { errorMessage } from "../errors";
import type { KnowledgeEntry, Product } from "../types";
import KnowledgeEntryEditor from "./KnowledgeEntryEditor";
import KnowledgeLibrary from "./KnowledgeLibrary";

export interface EditableProposal {
  id: string;
  revision: number;
  base_version: string | null;
  snapshot: string;
  current_base_version: string;
  current_entries: KnowledgeEntry[];
  base_entries: KnowledgeEntry[];
}
interface Conflict {
  id: string;
  current?: KnowledgeEntry;
  proposed?: KnowledgeEntry;
}
function canonical(value: unknown): string {
  if (value === undefined) return "undefined";
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value !== null && typeof value === "object")
    return `{${Object.entries(value)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([k, v]) => `${JSON.stringify(k)}:${canonical(v)}`)
      .join(",")}}`;
  return JSON.stringify(value) ?? "undefined";
}
function prepare(proposal: EditableProposal) {
  const proposed = JSON.parse(proposal.snapshot) as KnowledgeEntry[];
  if ((proposal.base_version || "") === proposal.current_base_version)
    return { entries: proposed, conflicts: [] as Conflict[] };
  const base = new Map(proposal.base_entries.map((e) => [e.source_id, e]));
  const draft = new Map(proposed.map((e) => [e.source_id, e]));
  const current = new Map(
    proposal.current_entries.map((e) => [e.source_id, e]),
  );
  const merged = new Map(current);
  const conflicts: Conflict[] = [];
  for (const id of new Set([...base.keys(), ...draft.keys()])) {
    if (canonical(base.get(id)) === canonical(draft.get(id))) continue;
    if (
      canonical(current.get(id)) !== canonical(base.get(id)) &&
      canonical(current.get(id)) !== canonical(draft.get(id))
    ) {
      conflicts.push({ id, current: current.get(id), proposed: draft.get(id) });
      continue;
    }
    const entry = draft.get(id);
    if (entry) merged.set(id, entry);
    else merged.delete(id);
  }
  return { entries: [...merged.values()], conflicts };
}

export default function KnowledgeProposalEditor({
  proposal,
  path,
  products,
  onClose,
  onSaved,
}: {
  proposal: EditableProposal;
  path: string;
  products: Product[];
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const [initial] = useState(() => prepare(proposal));
  const [entries, setEntries] = useState(initial.entries);
  const [conflicts, setConflicts] = useState(initial.conflicts);
  const [selected, setSelected] = useState<string | null>(
    entries[0]?.source_id || null,
  );
  const [topic, setTopic] = useState("");
  const [editing, setEditing] = useState<KnowledgeEntry | "new" | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const saving = useRef(false);
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    dialog.current?.showModal();
    return () => dialog.current?.close();
  }, []);
  function remove(ids: Set<string>) {
    setEntries((old) => old.filter((entry) => !ids.has(entry.source_id)));
    setSelected(null);
  }
  function resolve(conflict: Conflict, proposed: boolean) {
    const chosen = proposed ? conflict.proposed : conflict.current;
    setEntries((old) => [
      ...old.filter((e) => e.source_id !== conflict.id),
      ...(chosen ? [chosen] : []),
    ]);
    setConflicts((old) => old.filter((c) => c.id !== conflict.id));
  }
  return (
    <dialog
      ref={dialog}
      className="document-import knowledge-proposal-editor"
      aria-labelledby="proposal-editor-title"
      onCancel={(event) => {
        event.preventDefault();
        if (!busy && !editing) onClose();
      }}
    >
      <div className="document-import-header">
        <h2 id="proposal-editor-title">编辑变更草稿</h2>
        <button
          className="secondary"
          disabled={busy || !!editing}
          onClick={onClose}
        >
          取消
        </button>
      </div>
      <div className="document-import-body">
        {error && (
          <p role="alert" className="callout warning">
            {error}
          </p>
        )}
        {(proposal.base_version || "") !== proposal.current_base_version && (
          <p className="callout warning">
            已保留当前知识库的其他变更，并合并本草稿的修改；冲突条目需逐项选择。保存后仍需提交审核。
          </p>
        )}
        {conflicts.map((conflict) => (
          <section key={conflict.id} className="knowledge-merge-conflict">
            <h3>
              {conflict.current?.title ||
                conflict.proposed?.title ||
                conflict.id}
            </h3>
            <div className="knowledge-merge-options">
              <div>
                <strong>当前知识库</strong>
                <p>{conflict.current?.text || "条目已移除"}</p>
                <button
                  className="secondary"
                  onClick={() => resolve(conflict, false)}
                >
                  保留当前
                </button>
              </div>
              <div>
                <strong>原草稿</strong>
                <p>{conflict.proposed?.text || "移除此条目"}</p>
                <button
                  className="secondary"
                  onClick={() => resolve(conflict, true)}
                >
                  采用草稿
                </button>
              </div>
            </div>
          </section>
        ))}
        {!conflicts.length && (
          <>
            <button
              className="secondary"
              disabled={busy}
              onClick={() => setEditing("new")}
            >
              新增条目
            </button>
            <KnowledgeLibrary
              entries={entries}
              products={products}
              selectedId={selected}
              onSelect={setSelected}
              topic={topic}
              onTopic={setTopic}
              onEdit={(entry) => {
                if (!busy) setEditing(entry);
              }}
              onRemove={(entry) => {
                if (!busy) remove(new Set([entry.source_id]));
              }}
              onRemoveDocument={(name) => {
                if (
                  !busy &&
                  window.confirm(
                    `从草稿移除整份“${name}”？保存并审核发布后才生效。`,
                  )
                )
                  remove(
                    new Set(
                      entries
                        .filter((e) => e.document_name === name)
                        .map((e) => e.source_id),
                    ),
                  );
              }}
            />
          </>
        )}
      </div>
      <div className="document-import-footer">
        <span>
          {entries.length} 条资料
          {conflicts.length ? `，${conflicts.length} 处冲突待处理` : ""}
        </span>
        <button
          className="primary"
          disabled={busy || !!editing || !!conflicts.length}
          onClick={async () => {
            if (saving.current) return;
            saving.current = true;
            setBusy(true);
            setError("");
            try {
              await api(
                `${path}/${proposal.id}`,
                {
                  revision: proposal.revision,
                  knowledge: {
                    version: `edit-${proposal.id}`,
                    base_version: proposal.current_base_version,
                    entries,
                  },
                },
                "PUT",
              );
              await onSaved();
            } catch (cause) {
              setError(errorMessage(cause));
            } finally {
              saving.current = false;
              setBusy(false);
            }
          }}
        >
          {busy ? "保存中…" : "保存草稿"}
        </button>
      </div>
      {editing && (
        <KnowledgeEntryEditor
          entry={editing === "new" ? undefined : editing}
          products={products}
          onClose={() => setEditing(null)}
          onSave={async (entry) => {
            setEntries((old) => [
              ...old.filter((e) => e.source_id !== entry.source_id),
              entry,
            ]);
            setSelected(entry.source_id);
            setEditing(null);
          }}
        />
      )}
    </dialog>
  );
}
