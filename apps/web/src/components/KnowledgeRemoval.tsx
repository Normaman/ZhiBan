import { useEffect, useRef, useState } from "react";
import { X } from "lucide-react";
import { errorMessage } from "../errors";

export default function KnowledgeRemoval({
  name,
  count,
  onConfirm,
  onClose,
}: {
  name: string;
  count: number;
  onConfirm: () => Promise<void>;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const submitting = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    dialog.current?.showModal();
    return () => dialog.current?.close();
  }, []);
  return (
    <dialog
      ref={dialog}
      className="document-import knowledge-editor"
      aria-labelledby="knowledge-remove-title"
      onCancel={(e) => {
        e.preventDefault();
        if (!busy) onClose();
      }}
    >
      <div className="document-import-header">
        <h2 id="knowledge-remove-title">删除资料</h2>
        <button aria-label="关闭删除确认" disabled={busy} onClick={onClose}>
          <X size={20} />
        </button>
      </div>
      <div className="document-import-body">
        {error && (
          <p role="alert" className="callout warning">
            {error}
          </p>
        )}
        <p>
          将「{name}」的 {count} 条资料从当前知识库移除。
        </p>
        <p>
          新咨询不再检索这些条目；历史版本和旧草稿的引用保留。已有草稿需重新生成。
        </p>
      </div>
      <div className="document-import-footer">
        <button className="secondary" onClick={onClose} disabled={busy}>
          取消
        </button>
        <button
          className="primary danger"
          disabled={busy}
          onClick={async () => {
            if (submitting.current) return;
            submitting.current = true;
            setBusy(true);
            setError("");
            try {
              await onConfirm();
            } catch (error) {
              setError(errorMessage(error));
            } finally {
              submitting.current = false;
              setBusy(false);
            }
          }}
        >
          {busy ? "正在删除…" : "确认删除"}
        </button>
      </div>
    </dialog>
  );
}
