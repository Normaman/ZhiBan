import { useEffect, useRef, useState } from "react";
import { RefreshCw, Plus, Upload, X } from "lucide-react";
import { api, ApiError } from "../api";
import { errorMessage } from "../errors";
import DocumentImport from "../components/DocumentImport";
import KnowledgeEntryEditor from "../components/KnowledgeEntryEditor";
import KnowledgeRemoval from "../components/KnowledgeRemoval";
import KnowledgeLibrary from "../components/KnowledgeLibrary";
import ProductCatalog from "../components/ProductCatalog";
import KnowledgeTransfer from "../components/KnowledgeTransfer";
import KnowledgeGovernance from "../components/KnowledgeGovernance";
import KnowledgeSearch from "../components/KnowledgeSearch";
import { knowledgeTopics as topics } from "../knowledge";
import type {
  Shop,
  KnowledgeVersion,
  KnowledgeEntry,
  PerformAction,
  ProductSnapshot,
  Product,
} from "../types";

const versionId = () => `kb-${Date.now()}-${crypto.randomUUID().slice(0, 6)}`;
const template = JSON.stringify(
  {
    entries: [
      {
        source_id: "shipping-policy",
        title: "发货规则",
        source_type: "shop_policy",
        topics: ["shipping"],
        text: "",
      },
      {
        source_id: "product-spec",
        title: "商品参数",
        source_type: "product",
        goods_id: "请替换为平台商品编号",
        topics: ["spec"],
        text: "",
      },
    ],
  },
  null,
  2,
);
type Baseline = { entries: KnowledgeEntry[]; version: string };

export default function KnowledgePage({
  shop,
  action,
  canTransfer = false,
  username,
}: {
  shop: Shop;
  action: PerformAction;
  canTransfer?: boolean;
  username: string;
}) {
  const [retrievalMode, setRetrievalMode] = useState("loading");
  const [versions, setVersions] = useState<KnowledgeVersion[]>([]);
  const [reviewRevision, setReviewRevision] = useState(0);
  const [products, setProducts] = useState<Product[]>([]);
  const [productRevision, setProductRevision] = useState(0);
  const [syncRunning, setSyncRunning] = useState(false);
  const [syncStarting, setSyncStarting] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [topic, setTopic] = useState("");
  const [editor, setEditor] = useState<{
    entry?: KnowledgeEntry;
    base: Baseline;
  } | null>(null);
  const [removal, setRemoval] = useState<{
    name: string;
    ids: string[];
    base: Baseline;
  } | null>(null);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [documentFile, setDocumentFile] = useState<{
    file: File;
    base: Baseline;
  } | null>(null);
  const [imported, setImported] = useState<KnowledgeEntry[]>([]);
  const [importBase, setImportBase] = useState<Baseline | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const versionRequest = useRef(0);
  async function load() {
    const request = ++versionRequest.current;
    const value = await api<KnowledgeVersion[]>(`/shops/${shop.id}/knowledge`);
    if (request === versionRequest.current) setVersions(value);
  }
  useEffect(() => {
    let active = true;
    const request = ++versionRequest.current;
    api<{ mode: string }>(`/shops/${shop.id}/knowledge/retrieval`)
      .then((value) => {
        if (active) setRetrievalMode(value.mode);
      })
      .catch(() => {
        if (active) setRetrievalMode("unavailable");
      });
    api<KnowledgeVersion[]>(`/shops/${shop.id}/knowledge`)
      .then((value) => {
        if (active && request === versionRequest.current) setVersions(value);
      })
      .catch((e) => {
        if (active) setError(errorMessage(e));
      });
    api<ProductSnapshot[]>(`/shops/${shop.id}/products`)
      .then((rows) => {
        if (active) setProducts(rows[0] ? JSON.parse(rows[0].products) : []);
      })
      .catch((e) => {
        if (active) setError(errorMessage(e));
      });
    return () => {
      active = false;
    };
  }, [shop.id, shop.knowledge_version, productRevision]);
  const current =
    versions.find((v) => v.active) ||
    versions.find((v) => v.id === shop.knowledge_version);
  const entries: KnowledgeEntry[] = current ? JSON.parse(current.snapshot) : [];
  const baseline = (): Baseline => ({ entries, version: current?.id || "" });
  async function publish(next: KnowledgeEntry[], baseVersion: string) {
    if (next.length > 500) throw new Error("知识条目过多，请分批整理。");
    try {
      await api(`/shops/${shop.id}/knowledge/proposals`, {
        version: versionId(),
        entries: next,
        base_version: baseVersion,
      });
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) await load();
      throw e;
    }
    setReviewRevision((value) => value + 1);
  }
  async function readFile(file?: File) {
    if (!file) return;
    setImported([]);
    setImportBase(null);
    setMessage("");
    const base = baseline();
    if (!file.name.toLowerCase().endsWith(".json")) {
      setError("");
      setDocumentFile({ file, base });
      return;
    }
    try {
      if (file.size > 1024 * 1024) throw new Error("文件不能超过 1MB。");
      const value = JSON.parse(await file.text());
      const rows = Array.isArray(value) ? value : value.entries;
      if (!Array.isArray(rows) || !rows.length || rows.length > 500)
        throw new Error("请导入包含 1–500 条资料的 JSON 文件。");
      const ids = new Set<string>();
      const preview = rows.map((row): KnowledgeEntry => {
        if (
          !row ||
          typeof row.source_id !== "string" ||
          !row.source_id.trim() ||
          row.source_id.length > 100 ||
          ids.has(row.source_id) ||
          !["product", "shop_policy"].includes(row.source_type) ||
          typeof row.text !== "string" ||
          !row.text.trim() ||
          row.text.length > 10000 ||
          !Array.isArray(row.topics) ||
          !row.topics.length ||
          row.topics.some((t: string) => !topics[t])
        )
          throw new Error(
            "资料格式不完整，或条目编号重复。请按模板填写真实资料。",
          );
        if (
          row.source_type === "product" &&
          (typeof row.goods_id !== "string" ||
            !products.some((p) => String(p.goods_id) === row.goods_id))
        )
          throw new Error("商品资料需要绑定已同步的商品编号。");
        ids.add(row.source_id);
        return {
          source_id: row.source_id,
          source_type: row.source_type,
          title:
            typeof row.title === "string"
              ? row.title.slice(0, 120)
              : row.source_id,
          topics: row.topics,
          text: row.text,
          ...(row.valid_from ? { valid_from: row.valid_from } : {}),
          ...(row.valid_until ? { valid_until: row.valid_until } : {}),
          ...(row.source_type === "product" ? { goods_id: row.goods_id } : {}),
        };
      });
      setImported(preview);
      setImportBase(base);
      setError("");
    } catch (e) {
      setImported([]);
      setError(
        e instanceof SyntaxError
          ? "文件格式无效，请按资料模板整理后导入。"
          : e instanceof Error
            ? e.message
            : "文件无法读取。",
      );
    }
  }
  return (
    <>
      {documentFile && (
        <DocumentImport
          file={documentFile.file}
          shopId={shop.id}
          products={products}
          onClose={() => setDocumentFile(null)}
          onPublish={async (incoming) => {
            const first = incoming[0];
            const keep = documentFile.base.entries.filter(
              (entry) => entry.document_name !== first.document_name,
            );
            await publish([...keep, ...incoming], documentFile.base.version);
            setTopic("");
            setSelectedId(first.source_id);
            setMessage("资料变更已保存为草稿，请在变更审核中提交。");
          }}
        />
      )}
      {editor && (
        <KnowledgeEntryEditor
          entry={editor.entry}
          initialTopic={topic || "shipping"}
          products={products}
          onClose={() => setEditor(null)}
          onSave={async (entry) => {
            await publish(
              [
                ...editor.base.entries.filter(
                  (e) => e.source_id !== entry.source_id,
                ),
                entry,
              ],
              editor.base.version,
            );
            setEditor(null);
            setTopic("");
            setSelectedId(entry.source_id);
            setMessage("资料变更已保存为草稿，审核发布后生效。");
          }}
        />
      )}
      {removal && (
        <KnowledgeRemoval
          name={removal.name}
          count={removal.ids.length}
          onClose={() => setRemoval(null)}
          onConfirm={async () => {
            await publish(
              removal.base.entries.filter(
                (e) => !removal.ids.includes(e.source_id),
              ),
              removal.base.version,
            );
            setRemoval(null);
            setMessage("移除操作已保存为草稿，审核发布后生效。");
          }}
        />
      )}
      <section className="page-heading">
        <div>
          <h1>知识库</h1>
          <p>管理商品知识、常见问答与客服回答规范。</p>
          <small data-testid="retrieval-mode">
            {retrievalMode === "hybrid"
              ? "本地语义 + 关键词召回 · 重排"
              : retrievalMode === "bm25"
                ? "关键词检索"
                : retrievalMode === "loading"
                  ? "正在读取检索状态…"
                  : retrievalMode === "bm25_degraded"
                    ? "语义检索暂不可用，已降级关键词检索"
                    : "无法读取检索状态"}
          </small>
        </div>
        <div className="heading-actions">
          {canTransfer && (
            <KnowledgeTransfer
              shop={shop}
              hasKnowledge={!!current}
              action={action}
              onDone={load}
            />
          )}
          <button
            className="secondary"
            onClick={() => {
              setMessage("");
              setEditor({ base: baseline() });
            }}
          >
            <Plus size={15} />
            手动新增
          </button>
          <button
            className="secondary"
            onClick={() => fileInput.current?.click()}
          >
            <Upload size={15} />
            导入资料
          </button>
          <input
            ref={fileInput}
            type="file"
            hidden
            accept=".json,.txt,.md,.csv,.docx,.xlsx,.pdf"
            onChange={(e) => {
              void readFile(e.target.files?.[0]);
              e.target.value = "";
            }}
          />
          <button
            className="secondary"
            disabled={syncRunning || syncStarting}
            onClick={() =>
              action(async () => {
                setSyncStarting(true);
                try {
                  const result = await api<{
                    metadata_sync?: { state: string };
                  }>(`/shops/${shop.id}/products/sync`, {});
                  setSyncRunning(result.metadata_sync?.state === "running");
                  setProductRevision((value) => value + 1);
                } finally {
                  setSyncStarting(false);
                }
              }, "平台商品已同步。")
            }
          >
            <RefreshCw size={15} />
            {syncRunning || syncStarting ? "同步中…" : "同步商品"}
          </button>
        </div>
      </section>
      {error && (
        <div role="alert" className="notification error">
          {error}
          <button aria-label="关闭资料错误" onClick={() => setError("")}>
            <X size={15} />
          </button>
        </div>
      )}
      {!!imported.length && (
        <section className="card knowledge-import">
          <h2>待发布资料 · {imported.length} 条</h2>
          <p>导入内容尚未生效，请核对后发布；相同编号会替换原条目。</p>
          <ul className="knowledge-import-preview">
            {imported.map((entry) => (
              <li key={entry.source_id}>
                <details>
                  <summary>
                    {entry.title || entry.source_id} ·{" "}
                    {entry.source_type === "product" ? "商品资料" : "店铺规则"}
                  </summary>
                  <p>{entry.text}</p>
                </details>
              </li>
            ))}
          </ul>
          <button
            className="primary"
            onClick={() =>
              action(async () => {
                const ids = new Set(imported.map((entry) => entry.source_id));
                await publish(
                  [
                    ...importBase!.entries.filter(
                      (entry) => !ids.has(entry.source_id),
                    ),
                    ...imported,
                  ],
                  importBase!.version,
                );
                setImported([]);
                setImportBase(null);
                setTopic("");
                setSelectedId(imported[0]?.source_id || null);
              }, "导入资料已保存为待审核草稿。")
            }
          >
            保存草稿
          </button>
          <button
            className="secondary"
            onClick={() => {
              setImported([]);
              setImportBase(null);
            }}
          >
            取消导入
          </button>
        </section>
      )}
      <KnowledgeGovernance
        products={products}
        shop={shop}
        username={username}
        canReview={canTransfer}
        revision={reviewRevision}
        onPublished={async () => {
          await load();
          setMessage("知识版本已更新；已有回复草稿需重新生成。");
        }}
      />
      <KnowledgeSearch shop={shop} products={products} />
      <div className="knowledge-layout">
        <section className="card">
          <div className="card-heading">
            <h2>已发布资料</h2>
            <span>{entries.length} 条</span>
          </div>
          <p
            className="knowledge-help"
            data-testid="knowledge-change-result"
            aria-live="polite"
          >
            {message}
          </p>
          <KnowledgeLibrary
            key={`${shop.id}:${current?.id || "empty"}`}
            entries={entries}
            products={products}
            selectedId={selectedId}
            onSelect={setSelectedId}
            topic={topic}
            onTopic={setTopic}
            onEdit={(entry) => {
              setMessage("");
              setEditor({ entry, base: baseline() });
            }}
            onRemove={(entry) => {
              setMessage("");
              setRemoval({
                name: entry.title || entry.source_id,
                ids: [entry.source_id],
                base: baseline(),
              });
            }}
            onRemoveDocument={(name) => {
              setMessage("");
              setRemoval({
                name,
                ids: entries
                  .filter((e) => e.document_name === name)
                  .map((e) => e.source_id),
                base: baseline(),
              });
            }}
          />
          <details className="version-history">
            <summary>历史版本 · {versions.length}</summary>
            {versions.map((v) => (
              <div key={v.id}>
                <span>{new Date(v.created_at).toLocaleString("zh-CN")}</span>
                <small>{v.id === current?.id ? "当前生效" : "历史快照"}</small>
                {v.id !== current?.id && (
                  <button
                    className="secondary"
                    onClick={() =>
                      action(async () => {
                        await publish(
                          JSON.parse(v.snapshot),
                          current?.id || "",
                        );
                        setMessage("回滚变更已保存为草稿，请提交审核。");
                      })
                    }
                  >
                    申请恢复
                  </button>
                )}
              </div>
            ))}
          </details>
          <details className="knowledge-guide">
            <summary>支持的格式与更新说明</summary>
            <p>
              支持 TXT、Markdown、CSV、DOCX、XLSX、文字型 PDF（最多 5 MB）和
              JSON 模板（最多 1 MB）；扫描件、图片及旧版 DOC/XLS 暂不支持。
            </p>
            <p>
              编辑和删除先保存为变更草稿，审核发布后生效；历史版本保留，已有回复草稿需重新生成。再次导入同名文件会替换该文件的全部条目，包括手动修改。
            </p>
            <a
              download="店铺知识模板.json"
              href={`data:application/json;charset=utf-8,${encodeURIComponent(template)}`}
            >
              下载导入模板
            </a>
          </details>
        </section>
      </div>
      <ProductCatalog
        shopId={shop.id}
        revision={productRevision}
        onSyncStateChange={setSyncRunning}
      />
    </>
  );
}
