import { useEffect, useRef, useState } from "react";
import { X } from "lucide-react";
import { errorMessage } from "../errors";
import type { KnowledgeEntry, Product } from "../types";
import { knowledgeTopics as topics, isProductTopic } from "../knowledge";

export default function KnowledgeEntryEditor({
  entry,
  initialTopic = "shipping",
  products,
  onSave,
  onClose,
}: {
  entry?: KnowledgeEntry;
  initialTopic?: string;
  products: Product[];
  onSave: (entry: KnowledgeEntry) => Promise<void>;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const submitting = useRef(false);
  const [title, setTitle] = useState(entry?.title || "");
  const [text, setText] = useState(entry?.text || "");
  const [topic, setTopic] = useState(entry?.topics[0] || initialTopic);
  const [goodsId, setGoodsId] = useState(entry?.goods_id || "");
  const localTime = (value?: string) => value
    ? new Date(new Date(value).getTime() - new Date(value).getTimezoneOffset() * 60000).toISOString().slice(0,16) : "";
  const [validFrom, setValidFrom] = useState(localTime(entry?.valid_from));
  const [validUntil, setValidUntil] = useState(localTime(entry?.valid_until));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const productTopic = isProductTopic(topic);
  useEffect(() => {
    dialog.current?.showModal();
    return () => dialog.current?.close();
  }, []);
  return (
    <dialog
      ref={dialog}
      className="document-import knowledge-editor"
      aria-labelledby="knowledge-editor-title"
      onCancel={(e) => {
        e.preventDefault();
        if (!busy) onClose();
      }}
    >
      <div className="document-import-header">
        <h2 id="knowledge-editor-title">
          {entry ? "编辑资料" : "手动新增资料"}
        </h2>
        <button aria-label="关闭资料编辑" disabled={busy} onClick={onClose}>
          <X size={20} />
        </button>
      </div>
      <form
        className="knowledge-editor-form"
        onSubmit={async (e) => {
          e.preventDefault();
          if (submitting.current) return;
          submitting.current = true;
          setBusy(true);
          setError("");
          try {
            await onSave({
              ...entry,
              source_id: entry?.source_id || `entry-${crypto.randomUUID()}`,
              title: title.trim(),
              text: text.trim(),
              source_type: productTopic ? "product" : "shop_policy",
              topics: [topic],
              goods_id: productTopic ? goodsId : undefined,
              classification_method: "manual",
              valid_from: validFrom ? new Date(validFrom).toISOString() : undefined,
              valid_until: validUntil ? new Date(validUntil).toISOString() : undefined,
            });
          } catch (error) {
            setError(errorMessage(error));
          } finally {
            submitting.current = false;
            setBusy(false);
          }
        }}
      >
        <div className="document-import-body">
          {error && (
            <p role="alert" className="callout warning">
              {error}
            </p>
          )}
          <label>
            资料名称
            <input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              maxLength={120}
              required
              disabled={busy}
            />
          </label>
          <label>
            条目分类
            <select
              value={topic}
              onChange={(e) => setTopic(e.target.value)}
              disabled={busy}
            >
              {Object.entries(topics).map(([id, name]) => (
                <option key={id} value={id}>
                  {name}
                </option>
              ))}
            </select>
          </label>
          {productTopic && (
            <label>
              适用商品
              <select
                value={goodsId}
                onChange={(e) => setGoodsId(e.target.value)}
                required
                disabled={busy}
              >
                <option value="">选择平台商品</option>
                {products.map((p) => (
                  <option key={p.goods_id} value={String(p.goods_id)}>
                    {p.goods_name}
                  </option>
                ))}
              </select>
            </label>
          )}
          <label>
            资料内容
            <textarea
              value={text}
              onChange={(e) => setText(e.target.value)}
              required
              maxLength={10000}
              rows={8}
              disabled={busy}
              placeholder={
                topic === "response_guidelines"
                  ? "先直接回答问题，通常 1–3 句；只保留相关条件，不重复寒暄，不做未经核实的承诺。"
                  : topic === "faq"
                    ? "问题：买家会怎样提问？\n答案：填写已经核实的答案。\n适用范围：哪些商品或情况适用？"
                    : undefined
              }
            />
          </label>
          {topic === "response_guidelines" && (
            <small>
              每次生成与检查都会应用；「保真吗」等固定问答请归入常见问答。
            </small>
          )}
          {topic === "faq" && (
            <small>
              答案须有已核实的店铺依据，写明适用范围；实时价格、库存与发货以平台查询为准。
            </small>
          )}
          {entry?.document_name && (
            <small>
              来源：{entry.document_name} · {entry.source_location}
              。再次导入同名文件会替换这次手动修改。
            </small>
          )}
          <label>生效时间（可选）<input type="datetime-local" value={validFrom}
            onChange={event => setValidFrom(event.target.value)} disabled={busy} /></label>
          <label>失效时间（可选）<input type="datetime-local" value={validUntil}
            min={validFrom || undefined} onChange={event => setValidUntil(event.target.value)} disabled={busy} /></label>
          <small>保存为变更草稿，提交审核并发布后生效。</small>
        </div>
        <div className="document-import-footer">
          <button
            type="button"
            className="secondary"
            onClick={onClose}
            disabled={busy}
          >
            取消
          </button>
          <button
            className="primary"
            disabled={
              busy ||
              !title.trim() ||
              !text.trim() ||
              (productTopic &&
                !products.some((p) => String(p.goods_id) === goodsId))
            }
          >
            {busy ? "正在保存…" : "保存草稿"}
          </button>
        </div>
      </form>
    </dialog>
  );
}
