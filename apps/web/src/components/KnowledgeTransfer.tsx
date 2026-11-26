import { useEffect, useRef, useState } from "react";
import { Download, FolderSync, X } from "lucide-react";
import { api, ApiError } from "../api";
import { errorMessage } from "../errors";
import { knowledgeTopics } from "../knowledge";
import type { KnowledgeEntry, PerformAction, Shop } from "../types";

interface Archive {
  format: string;
  schema_version: number;
  exported_at: string;
  source: {
    shop_id: string;
    shop_name: string;
    mode: string;
    version_label: string;
  };
  snapshot_json: string;
  snapshot_sha256: string;
}

interface Plan {
  source_shop_name: string;
  target_shop_name: string;
  base_version: string;
  added: number;
  updated: number;
  skipped: number;
  conflicts: number;
  excluded_products: number;
  total_after: number;
  missing_goods_ids: string[];
  can_import: boolean;
  unchanged: boolean;
}

export default function KnowledgeTransfer({
  shop,
  hasKnowledge,
  action,
  onDone,
}: {
  shop: Shop;
  hasKnowledge: boolean;
  action: PerformAction;
  onDone: () => Promise<void>;
}) {
  const fileInput = useRef<HTMLInputElement>(null);
  const menu = useRef<HTMLDetailsElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [exporting, setExporting] = useState(false);
  return (
    <>
      <details ref={menu} className="knowledge-transfer-menu">
        <summary>
          <FolderSync size={15} />
          备份与迁移
        </summary>
        <div className="knowledge-transfer-actions">
          <button
            disabled={!hasKnowledge || exporting}
            onClick={() => {
              if (menu.current) menu.current.open = false;
              void action(async () => {
                setExporting(true);
                try {
                  const archive = await api<Archive>(
                    `/admin/shops/${shop.id}/knowledge/archive`,
                  );
                  const url = URL.createObjectURL(
                    new Blob([JSON.stringify(archive, null, 2)], {
                      type: "application/json;charset=utf-8",
                    }),
                  );
                  const link = document.createElement("a");
                  link.href = url;
                  link.download = `knowledge-${Date.now()}.json`;
                  document.body.appendChild(link);
                  link.click();
                  link.remove();
                  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
                } finally {
                  setExporting(false);
                }
              });
            }}
          >
            <Download size={15} />
            {exporting ? "导出中…" : "导出知识库"}
          </button>
          <button
            onClick={() => {
              if (menu.current) menu.current.open = false;
              fileInput.current?.click();
            }}
          >
            <FolderSync size={15} />
            迁移导入
          </button>
        </div>
      </details>
      <input
        ref={fileInput}
        type="file"
        accept=".json"
        hidden
        aria-label="知识库备份文件"
        onChange={(event) => {
          setFile(event.target.files?.[0] || null);
          event.target.value = "";
        }}
      />
      {file && (
        <MigrationDialog
          file={file}
          shop={shop}
          action={action}
          onDone={onDone}
          onClose={() => setFile(null)}
        />
      )}
    </>
  );
}

function MigrationDialog({
  file,
  shop,
  action,
  onDone,
  onClose,
}: {
  file: File;
  shop: Shop;
  action: PerformAction;
  onDone: () => Promise<void>;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [archive, setArchive] = useState<Archive | null>(null);
  const [plan, setPlan] = useState<Plan | null>(null);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState("");
  const [includeProducts, setIncludeProducts] = useState(true);
  const [conflictPolicy, setConflictPolicy] = useState("keep_target");
  const [confirmed, setConfirmed] = useState(false);
  const [retry, setRetry] = useState(0);
  const [selected, setSelected] = useState(0);
  useEffect(() => {
    dialog.current?.showModal();
    let active = true;
    void (async () => {
      try {
        if (file.size > 20 * 1024 * 1024)
          throw new Error("知识备份不能超过 20MB。");
        const value = JSON.parse(await file.text());
        if (
          value?.format !== "pdd-copilot-knowledge" ||
          value.schema_version !== 1 ||
          typeof value.snapshot_json !== "string" ||
          !value.source
        )
          throw new Error("请选择通过知识库导出功能生成的备份文件。");
        const rows = JSON.parse(value.snapshot_json);
        if (
          !Array.isArray(rows) ||
          rows.length > 500 ||
          rows.some(
            (row) =>
              !row ||
              typeof row.text !== "string" ||
              !Array.isArray(row.topics),
          )
        )
          throw new Error("知识备份中的资料格式不正确。");
        if (active) setArchive(value);
      } catch (problem) {
        if (active) {
          setError(
            problem instanceof SyntaxError
              ? "备份文件不是有效的 JSON。"
              : errorMessage(problem),
          );
          setBusy(false);
        }
      }
    })();
    return () => {
      active = false;
      dialog.current?.close();
    };
  }, [file]);
  useEffect(() => {
    if (!archive) return;
    let active = true;
    setBusy(true);
    setError("");
    setPlan(null);
    setConfirmed(false);
    api<Plan>(`/admin/shops/${shop.id}/knowledge/migration/preview`, {
      archive,
      include_products: includeProducts,
      conflict_policy: conflictPolicy,
      base_version: "",
    })
      .then((value) => {
        if (active) setPlan(value);
      })
      .catch((problem) => {
        if (active) setError(errorMessage(problem));
      })
      .finally(() => {
        if (active) setBusy(false);
      });
    return () => {
      active = false;
    };
  }, [archive, includeProducts, conflictPolicy, retry, shop.id]);
  const entries: KnowledgeEntry[] = archive
    ? JSON.parse(archive.snapshot_json)
    : [];
  const entry = entries[selected];
  return (
    <dialog
      ref={dialog}
      className="document-import knowledge-editor knowledge-migration"
      aria-labelledby="knowledge-migration-title"
      onCancel={(event) => {
        event.preventDefault();
        if (!busy) onClose();
      }}
    >
      <div className="document-import-header">
        <h2 id="knowledge-migration-title">迁移知识库</h2>
        <button aria-label="关闭知识迁移" disabled={busy} onClick={onClose}>
          <X size={20} />
        </button>
      </div>
      <div className="document-import-body">
        <p>
          <strong>{archive?.source.shop_name || file.name}</strong> →{" "}
          <strong>{shop.name}</strong>
        </p>
        <p className="field-hint">
          合并到当前店铺并发布新版本，目标已有的其他资料会保留。
        </p>
        {error && (
          <div role="alert" className="callout warning">
            {error}
            {archive && (
              <button
                className="text-button"
                disabled={busy}
                onClick={() => setRetry(retry + 1)}
              >
                重新检查
              </button>
            )}
          </div>
        )}
        {archive && (
          <>
            <label className="migration-checkbox">
              <input
                type="checkbox"
                checked={includeProducts}
                disabled={busy}
                onChange={(event) => setIncludeProducts(event.target.checked)}
              />
              包含商品扩展知识
            </label>
            <label>
              同编号资料
              <select
                aria-label="迁移冲突处理"
                value={conflictPolicy}
                disabled={busy}
                onChange={(event) => setConflictPolicy(event.target.value)}
              >
                <option value="keep_target">保留目标已有内容</option>
                <option value="replace_matches">用备份内容更新</option>
              </select>
            </label>
            <div className="migration-categories">
              {Object.entries(knowledgeTopics).map(([id, title]) => {
                const count = entries.filter((row) =>
                  row.topics.includes(id),
                ).length;
                return count ? (
                  <span key={id}>
                    {title} {count}
                  </span>
                ) : null;
              })}
            </div>
            {!!entries.length && (
              <details className="migration-source-preview">
                <summary>查看备份资料 · {entries.length} 条</summary>
                <select
                  aria-label="查看迁移资料"
                  value={selected}
                  onChange={(event) => setSelected(Number(event.target.value))}
                >
                  {entries.map((row, i) => (
                    <option key={i} value={i}>
                      {row.title || row.source_id}
                    </option>
                  ))}
                </select>
                {entry && (
                  <>
                    <small>
                      {entry.document_name}
                      {entry.source_location
                        ? ` · ${entry.source_location}`
                        : ""}
                      {entry.goods_id ? ` · 商品 ${entry.goods_id}` : ""}
                    </small>
                    <pre>{entry.text}</pre>
                  </>
                )}
              </details>
            )}
          </>
        )}
        {busy && <p role="status">正在核验备份与目标资料…</p>}
        {plan && (
          <>
            <dl className="migration-counts">
              <div>
                <dt>新增</dt>
                <dd>{plan.added}</dd>
              </div>
              <div>
                <dt>更新</dt>
                <dd>{plan.updated}</dd>
              </div>
              <div>
                <dt>跳过</dt>
                <dd>{plan.skipped}</dd>
              </div>
              <div>
                <dt>迁移后</dt>
                <dd>{plan.total_after}</dd>
              </div>
            </dl>
            {!!plan.conflicts && (
              <p className="field-hint">
                {plan.conflicts} 条同编号资料内容不同，将按上方选项处理。
              </p>
            )}
            {!!plan.excluded_products && (
              <p className="field-hint">
                本次不迁移 {plan.excluded_products} 条商品扩展知识。
              </p>
            )}
            {!!plan.missing_goods_ids.length && (
              <p role="alert" className="callout warning">
                目标店铺缺少 {plan.missing_goods_ids.length}{" "}
                个对应商品，请先同步商品。也可取消勾选商品扩展知识，仅迁移店铺资料。
              </p>
            )}
            {plan.total_after > 500 && (
              <p role="alert" className="callout warning">
                合并后超过 500 条，请先整理资料。
              </p>
            )}
            {plan.unchanged ? (
              <p role="status">资料已经存在，无需重复迁移。</p>
            ) : (
              <label className="migration-checkbox">
                <input
                  type="checkbox"
                  checked={confirmed}
                  disabled={busy || !plan.can_import}
                  onChange={(event) => setConfirmed(event.target.checked)}
                />
                已核对资料适用于「{shop.name}」
              </label>
            )}
          </>
        )}
      </div>
      <div className="document-import-footer">
        <button className="secondary" disabled={busy} onClick={onClose}>
          取消
        </button>
        <button
          className="primary"
          disabled={
            busy ||
            !archive ||
            !plan?.can_import ||
            plan.unchanged ||
            !confirmed
          }
          onClick={() => {
            void action(async () => {
              setBusy(true);
              setError("");
              try {
                await api(`/admin/shops/${shop.id}/knowledge/migration`, {
                  archive,
                  include_products: includeProducts,
                  conflict_policy: conflictPolicy,
                  base_version: plan!.base_version,
                });
                await onDone();
                onClose();
              } catch (problem) {
                setError(errorMessage(problem));
                if (problem instanceof ApiError && problem.status === 409) {
                  setPlan(null);
                  setConfirmed(false);
                }
                throw problem;
              } finally {
                setBusy(false);
              }
            }, "知识迁移已完成；已有草稿需重新生成。");
          }}
        >
          迁移到当前店铺
        </button>
      </div>
    </dialog>
  );
}
