import { useEffect, useRef, useState } from "react";
import { X } from "lucide-react";
import { api, ApiError } from "../api";
import { errorMessage } from "../errors";
import type { KnowledgeEntry, Product } from "../types";
import {
  knowledgeTopics as categories,
  isProductTopic as isProduct,
} from "../knowledge";

type Chunk = Pick<
  KnowledgeEntry,
  | "source_id"
  | "title"
  | "text"
  | "document_name"
  | "document_sha256"
  | "source_location"
  | "chunk_index"
> & {
  suggested_topic?: string;
  classification_method?: "rule" | "semantic" | "manual" | "unclassified";
  classification_note?: string;
  is_heading?: boolean;
};
type Entry = Chunk & { topic: string; goods_id: string; included: boolean };
type Preview = { filename: string; chunks: Entry[]; warnings: string[] };

export default function DocumentImport({
  file,
  shopId,
  products,
  onClose,
  onPublish,
}: {
  file: File;
  shopId: string;
  products: Product[];
  onClose: () => void;
  onPublish: (entries: KnowledgeEntry[]) => Promise<void>;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const textarea = useRef<HTMLTextAreaElement>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [error, setError] = useState("");
  const [index, setIndex] = useState(0);
  const [bulkTopic, setBulkTopic] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const submitting = useRef(false);
  useEffect(() => {
    dialog.current?.showModal();
    return () => dialog.current?.close();
  }, []);
  useEffect(() => {
    let active = true;
    setPreview(null);
    (async () => {
      if (file.size > 5 * 1024 * 1024) throw new Error("文件不能超过 5 MB。");
      if (!file.size) throw new Error("文件为空，请选择有内容的资料。");
      const bytes = new Uint8Array(await file.arrayBuffer());
      let binary = "";
      for (let offset = 0; offset < bytes.length; offset += 8192)
        binary += String.fromCharCode(...bytes.subarray(offset, offset + 8192));
      const result = await api<{
        filename: string;
        chunks: Chunk[];
        warnings: string[];
      }>(`/shops/${shopId}/knowledge/preview`, {
        filename: file.name,
        content_base64: btoa(binary),
      });
      if (active) {
        const chunks = result.chunks.map(
          (c): Entry => ({
            ...c,
            topic: categories[c.suggested_topic || ""]
              ? c.suggested_topic!
              : "",
            goods_id: "",
            included: !c.is_heading,
          }),
        );
        setPreview({ ...result, chunks });
        setIndex(
          Math.max(
            0,
            chunks.findIndex((c) => c.included),
          ),
        );
      }
    })().catch((e) => {
      if (active)
        setError(
          e instanceof ApiError
            ? errorMessage(e)
            : e instanceof Error
              ? e.message
              : "文件无法读取，请重试。",
        );
    });
    return () => {
      active = false;
    };
  }, [file, shopId]);
  const chunk = preview?.chunks[index];
  const selected = preview?.chunks.filter((c) => c.included) || [];
  const incomplete = selected.filter(
    (c) =>
      !c.topic ||
      (isProduct(c.topic) && !c.goods_id) ||
      !c.text.trim() ||
      !c.title?.trim(),
  );
  function update(patch: Partial<Entry>) {
    setPreview((previous) =>
      previous
        ? {
            ...previous,
            chunks: previous.chunks.map((c, i) =>
              i === index ? { ...c, ...patch } : c,
            ),
          }
        : null,
    );
    setConfirmed(false);
  }
  function batchTopic(topic: string) {
    setBulkTopic(topic);
    if (!topic) return;
    setPreview((previous) =>
      previous
        ? {
            ...previous,
            chunks: previous.chunks.map((c) => ({
              ...c,
              topic,
              goods_id: isProduct(topic) ? c.goods_id : "",
              classification_method: "manual",
            })),
          }
        : null,
    );
    setConfirmed(false);
  }
  function split() {
    const position = textarea.current?.selectionStart || 0;
    if (
      !chunk ||
      position <= 0 ||
      position >= chunk.text.length ||
      preview!.chunks.length >= 500
    ) {
      setError("请把光标放在正文需要拆分的位置；最多支持 500 条。");
      return;
    }
    const left = chunk.text.slice(0, position).trim();
    const right = chunk.text.slice(position).trim();
    if (!left || !right) return;
    const added: Entry = {
      ...chunk,
      source_id: `doc-${chunk.document_sha256?.slice(0, 32)}-manual-${crypto.randomUUID().slice(0, 8)}`,
      text: right,
      topic: "",
      classification_method: "manual",
      classification_note: "手动拆分后请确认分类",
    };
    setPreview((previous) =>
      previous
        ? {
            ...previous,
            chunks: [
              ...previous.chunks.slice(0, index),
              { ...chunk, text: left },
              added,
              ...previous.chunks.slice(index + 1),
            ],
          }
        : null,
    );
    setConfirmed(false);
    setError("");
  }
  async function publish() {
    if (
      !preview ||
      submitting.current ||
      !selected.length ||
      incomplete.length ||
      !confirmed
    )
      return;
    submitting.current = true;
    setBusy(true);
    setError("");
    try {
      const entries = await Promise.all(
        selected.map(async (c) => {
          const scope = new TextEncoder().encode(
            JSON.stringify([
              c.topic,
              isProduct(c.topic) ? c.goods_id : "shop",
              preview.filename,
            ]),
          );
          const hash = Array.from(
            new Uint8Array(await crypto.subtle.digest("SHA-256", scope)),
          )
            .map((b) => b.toString(16).padStart(2, "0"))
            .join("")
            .slice(0, 12);
          return {
            source_id: `${c.source_id}-${hash}`,
            title: c.title,
            text: c.text,
            document_name: c.document_name,
            document_sha256: c.document_sha256,
            source_location: c.source_location,
            chunk_index: c.chunk_index,
            classification_method:
              c.classification_method === "unclassified"
                ? "manual"
                : c.classification_method,
            source_type: isProduct(c.topic)
              ? ("product" as const)
              : ("shop_policy" as const),
            topics: [c.topic],
            ...(isProduct(c.topic) ? { goods_id: c.goods_id } : {}),
          };
        }),
      );
      await onPublish(entries);
      onClose();
    } catch (e) {
      setError(
        e instanceof ApiError
          ? errorMessage(e)
          : e instanceof Error
            ? e.message
            : "草稿保存未完成，请重试。",
      );
    } finally {
      submitting.current = false;
      setBusy(false);
    }
  }
  return (
    <dialog
      ref={dialog}
      className="document-import"
      aria-labelledby="document-import-title"
      onCancel={(e) => {
        e.preventDefault();
        if (!busy) onClose();
      }}
    >
      <div className="document-import-header">
        <h2 id="document-import-title">核对导入资料</h2>
        <button aria-label="关闭导入" disabled={busy} onClick={onClose}>
          <X size={20} />
        </button>
      </div>
      <div className="document-import-body">
        <p className="document-filename">{file.name}</p>
        {error && (
          <p role="alert" className="callout warning">
            {error}
          </p>
        )}
        {!preview && !error && <p role="status">正在提取和整理条目，请稍候…</p>}
        {preview && (
          <>
            <p data-testid="document-organization">
              已整理 {preview.chunks.length} 条，选中导入 {selected.length} 条
              {incomplete.length
                ? `，${incomplete.length} 条待核对分类或商品`
                : ""}
              。保留原文，分类仅为建议；同名文件将整体替换该文件的旧条目。
            </p>
            {preview.warnings.length > 0 && (
              <details className="document-warnings" open>
                <summary>解析提示（{preview.warnings.length}）</summary>
                {preview.warnings.map((w) => (
                  <p key={w}>{w}</p>
                ))}
              </details>
            )}
            <details className="document-batch-settings">
              <summary>批量设置分类或商品</summary>
              <label>
                全部条目分类
                <select
                  aria-label="导入资料分类"
                  value={bulkTopic}
                  disabled={busy}
                  onChange={(e) => batchTopic(e.target.value)}
                >
                  <option value="">保留逐条分类</option>
                  {Object.entries(categories).map(([id, name]) => (
                    <option key={id} value={id}>
                      {name}
                    </option>
                  ))}
                </select>
              </label>
              {preview.chunks.some((c) => isProduct(c.topic)) && (
                <label>
                  全部商品类条目关联
                  <select
                    aria-label="批量关联商品"
                    disabled={busy}
                    defaultValue=""
                    onChange={(e) => {
                      const goods = e.target.value;
                      setPreview((p) =>
                        p
                          ? {
                              ...p,
                              chunks: p.chunks.map((c) =>
                                isProduct(c.topic)
                                  ? { ...c, goods_id: goods }
                                  : c,
                              ),
                            }
                          : null,
                      );
                      setConfirmed(false);
                    }}
                  >
                    <option value="">请选择商品</option>
                    {products.map((p) => (
                      <option key={p.goods_id} value={p.goods_id}>
                        {p.goods_name}
                      </option>
                    ))}
                  </select>
                </label>
              )}
            </details>
            <label>
              查看条目
              <select
                aria-label="查看导入段落"
                value={index}
                onChange={(e) => setIndex(Number(e.target.value))}
              >
                {preview.chunks.map((c, i) => (
                  <option key={c.source_id} value={i}>
                    {i + 1}.{" "}
                    {c.included ? categories[c.topic] || "待分类" : "不导入"} ·{" "}
                    {c.title || c.source_location}
                  </option>
                ))}
              </select>
            </label>
            {chunk && (
              <>
                <label className="document-confirm">
                  <input
                    type="checkbox"
                    aria-label="纳入本次草稿"
                    checked={chunk.included}
                    disabled={busy}
                    onChange={(e) => update({ included: e.target.checked })}
                  />
                  纳入本次草稿
                </label>
                <div className="document-import-fields">
                  <label>
                    当前条目分类
                    <select
                      aria-label="当前条目分类"
                      value={chunk.topic}
                      disabled={busy}
                      onChange={(e) =>
                        update({
                          topic: e.target.value,
                          goods_id: isProduct(e.target.value)
                            ? chunk.goods_id
                            : "",
                          classification_method: "manual",
                        })
                      }
                    >
                      <option value="">待分类，请选择</option>
                      {Object.entries(categories).map(([id, name]) => (
                        <option key={id} value={id}>
                          {name}
                        </option>
                      ))}
                    </select>
                  </label>
                  {isProduct(chunk.topic) && (
                    <label>
                      关联商品
                      <select
                        aria-label="导入关联商品"
                        value={chunk.goods_id}
                        disabled={busy}
                        onChange={(e) => update({ goods_id: e.target.value })}
                      >
                        <option value="">请选择已同步商品</option>
                        {products.map((p) => (
                          <option key={p.goods_id} value={p.goods_id}>
                            {p.goods_name}
                          </option>
                        ))}
                      </select>
                    </label>
                  )}
                </div>
                {chunk.classification_note && (
                  <small>{chunk.classification_note}</small>
                )}
                <small>来源：{chunk.source_location}</small>
                <label>
                  条目标题
                  <input
                    aria-label="导入段落标题"
                    value={chunk.title || ""}
                    maxLength={120}
                    disabled={busy}
                    onChange={(e) => update({ title: e.target.value })}
                  />
                </label>
                <label>
                  原文内容
                  <textarea
                    ref={textarea}
                    aria-label="导入段落内容"
                    value={chunk.text}
                    maxLength={10000}
                    disabled={busy}
                    onChange={(e) => update({ text: e.target.value })}
                  />
                </label>
                <div className="document-pagination">
                  <button
                    className="secondary"
                    disabled={!index}
                    onClick={() => setIndex(index - 1)}
                  >
                    上一段
                  </button>
                  <button className="secondary" disabled={busy} onClick={split}>
                    从光标处分条
                  </button>
                  <button
                    className="secondary"
                    disabled={index === preview.chunks.length - 1}
                    onClick={() => setIndex(index + 1)}
                  >
                    下一段
                  </button>
                </div>
              </>
            )}
            <label className="document-confirm">
              <input
                type="checkbox"
                aria-label="确认核对资料"
                checked={confirmed}
                disabled={busy}
                onChange={(e) => setConfirmed(e.target.checked)}
              />
              我已核对内容、分类、商品和解析提示，确认作为客服回复依据。
            </label>
          </>
        )}
      </div>
      <div className="document-import-footer">
        <button className="secondary" disabled={busy} onClick={onClose}>
          取消
        </button>
        <button
          className="primary"
          onClick={() => void publish()}
          disabled={
            busy ||
            !preview ||
            !selected.length ||
            !!incomplete.length ||
            !confirmed
          }
        >
          {busy ? "保存中…" : "保存变更草稿"}
        </button>
      </div>
    </dialog>
  );
}
