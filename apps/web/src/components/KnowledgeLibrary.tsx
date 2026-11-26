import { useEffect, useRef, useState } from "react";
import { BookOpen, FileText, Pencil, Search, Trash2 } from "lucide-react";
import { knowledgeTopics } from "../knowledge";
import type { KnowledgeEntry, Product } from "../types";

// Render document text as React text nodes; imported HTML is never executed.
function DocumentBody({ text }: { text: string }) {
  return (
    <div className="knowledge-reader-body">
      {text.split(/\n\s*\n/).map((block, i) => {
        const heading = block.match(/^#{1,6}\s+([^\n]+)$/);
        const lines = block.split("\n");
        if (heading) return <h4 key={i}>{heading[1]}</h4>;
        if (lines.every((line) => /^\s*[-*•]\s+/.test(line)))
          return (
            <ul key={i}>
              {lines.map((line, n) => (
                <li key={n}>{line.replace(/^\s*[-*•]\s+/, "")}</li>
              ))}
            </ul>
          );
        return <p key={i}>{block}</p>;
      })}
    </div>
  );
}

export default function KnowledgeLibrary({
  entries,
  products,
  selectedId,
  onSelect,
  topic,
  onTopic,
  onEdit,
  onRemove,
  onRemoveDocument,
}: {
  entries: KnowledgeEntry[];
  products: Product[];
  selectedId: string | null;
  onSelect: (id: string) => void;
  topic: string;
  onTopic: (topic: string) => void;
  onEdit: (entry: KnowledgeEntry) => void;
  onRemove: (entry: KnowledgeEntry) => void;
  onRemoveDocument: (name: string) => void;
}) {
  const [query, setQuery] = useState("");
  const reader = useRef<HTMLElement>(null);
  const index = useRef<HTMLDivElement>(null);
  const needle = query.trim().toLocaleLowerCase();
  const visible = entries.filter(
    (e) =>
      (!topic || e.topics.includes(topic)) &&
      (!needle ||
        [e.title, e.text, e.document_name].some((value) =>
          value?.toLocaleLowerCase().includes(needle),
        )),
  );
  const selected =
    visible.find((e) => e.source_id === selectedId) || visible[0];
  const groups = new Map<string, KnowledgeEntry[]>();
  for (const entry of visible) {
    const name = entry.document_name || "";
    groups.set(name, [...(groups.get(name) || []), entry]);
  }
  useEffect(() => {
    reader.current?.scrollTo({ top: 0 });
  }, [selected?.source_id]);
  useEffect(() => {
    const list = index.current;
    if (!list) return;
    const revealSelection = () => {
      const entry = list.querySelector('[aria-pressed="true"]');
      if (!entry) return;
      const item = entry.getBoundingClientRect(),
        bounds = list.getBoundingClientRect();
      if (item.bottom > bounds.bottom)
        list.scrollTop += item.bottom - bounds.bottom;
      else if (item.top < bounds.top) list.scrollTop -= bounds.top - item.top;
    };
    const observer = new ResizeObserver(revealSelection);
    observer.observe(list);
    revealSelection();
    return () => observer.disconnect();
  }, [selected?.source_id]);
  return (
    <div className="knowledge-library">
      <nav className="knowledge-categories" aria-label="知识分类">
        {[["", "全部资料"], ...Object.entries(knowledgeTopics)].map(
          ([id, name]) => (
            <button
              key={id}
              aria-pressed={topic === id}
              onClick={() => onTopic(id)}
            >
              {name}
              <span>
                {id
                  ? entries.filter((e) => e.topics.includes(id)).length
                  : entries.length}
              </span>
            </button>
          ),
        )}
      </nav>
      {topic === "response_guidelines" && (
        <p className="knowledge-category-note">
          每次生成和检查草稿都会应用。用于语气、长短与表达要求；固定问题的答案放在「常见问答」。
        </p>
      )}
      {topic === "faq" && (
        <p className="knowledge-category-note">
          填写问题、核实后的答案及适用范围，例如正品说明。只检索相关条目，不自动承诺。
        </p>
      )}
      <label className="knowledge-search">
        <Search size={16} />
        <input
          aria-label="搜索知识资料"
          placeholder="搜索标题、正文或文件名"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </label>
      <div className="knowledge-browser">
        <div ref={index} className="knowledge-index" aria-label="资料目录">
          {Array.from(groups, ([name, rows]) => (
            <section className="knowledge-document" key={name}>
              <div className="knowledge-document-heading">
                <FileText size={14} />
                <h3 title={name}>{name || "手动与模板资料"}</h3>
                <small>{rows.length}</small>
                {name && (
                  <button
                    className="icon-button"
                    aria-label={`删除整份${name}`}
                    title="删除整份文件"
                    onClick={() => onRemoveDocument(name)}
                  >
                    <Trash2 size={14} />
                  </button>
                )}
              </div>
              {rows.map((entry) => (
                <button
                  className="knowledge-entry"
                  key={entry.source_id}
                  aria-pressed={selected?.source_id === entry.source_id}
                  onClick={() => onSelect(entry.source_id)}
                >
                  <strong>
                    {entry.title ||
                      knowledgeTopics[entry.topics[0]] ||
                      "资料条目"}
                  </strong>
                  <small>
                    {entry.topics.map((t) => knowledgeTopics[t]).join(" · ")}
                  </small>
                </button>
              ))}
            </section>
          ))}
          {!visible.length && (
            <div className="empty">
              <BookOpen size={24} />
              <p>{entries.length ? "没有匹配的资料" : "尚未发布资料"}</p>
            </div>
          )}
        </div>
        <article
          ref={reader}
          className="knowledge-reader"
          aria-label="资料正文"
        >
          {selected ? (
            <>
              <header className="knowledge-reader-heading">
                <div>
                  <span className="knowledge-reader-category">
                    {selected.topics.map((t) => knowledgeTopics[t]).join(" · ")}
                  </span>
                  <h3>{selected.title || "资料条目"}</h3>
                </div>
                <div className="knowledge-entry-actions">
                  <button
                    className="secondary"
                    aria-label={`编辑${selected.title || selected.source_id}`}
                    onClick={() => onEdit(selected)}
                  >
                    <Pencil size={14} />
                    编辑
                  </button>
                  <button
                    className="secondary"
                    aria-label={`删除条目${selected.title || selected.source_id}`}
                    onClick={() => onRemove(selected)}
                  >
                    <Trash2 size={14} />
                    删除
                  </button>
                </div>
              </header>
              <div className="knowledge-reader-meta">
                <span>{selected.valid_from && Date.parse(selected.valid_from)>Date.now() ? "尚未生效"
                  : selected.valid_until && Date.parse(selected.valid_until)<=Date.now() ? "已失效" : "当前有效"}
                  {(selected.valid_from || selected.valid_until) && ` · ${selected.valid_from ? new Date(selected.valid_from).toLocaleString("zh-CN") : "立即"} 至 ${selected.valid_until ? new Date(selected.valid_until).toLocaleString("zh-CN") : "长期"}`}
                </span>
                <span>
                  {selected.source_type === "product"
                    ? products.find(
                        (p) => String(p.goods_id) === selected.goods_id,
                      )?.goods_name || "未绑定商品"
                    : "全店适用"}
                </span>
                <span>
                  {selected.document_name || "手动与模板资料"}
                  {selected.source_location
                    ? ` · ${selected.source_location}`
                    : ""}
                </span>
              </div>
              <DocumentBody text={selected.text} />
            </>
          ) : (
            <div className="empty">
              <BookOpen size={24} />
              <p>选择资料查看内容</p>
            </div>
          )}
        </article>
      </div>
    </div>
  );
}
