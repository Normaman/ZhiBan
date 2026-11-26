import { BookOpen, ShieldCheck } from "lucide-react";
import { time } from "../format";
import type { Citation } from "../types";

function Source({ citation }: { citation: Citation }) {
  const label =
    citation.title ||
    (citation.source_type === "platform_product"
      ? "平台商品资料"
      : citation.source_type === "platform_order"
        ? "平台订单资料"
      : citation.source_type === "shop_policy"
        ? "店铺规则"
        : "商品知识");
  return (
    <details className="source-card">
      <summary>
        <BookOpen size={14} />
        <strong>{label}</strong>
      </summary>
      <p>{citation.evidence_text}</p>
      <footer>
        <span title={citation.source_id}>
          {[citation.document_name, citation.source_location]
            .filter(Boolean)
            .join(" · ") || "已发布资料"}
        </span>
        <span>
          {citation.fetched_at ? `查询于 ${time(citation.fetched_at)}` : ""}
        </span>
      </footer>
    </details>
  );
}

export default function DraftSources({
  citations,
  checked,
  attempted,
  edited,
  social = false,
}: {
  citations: Citation[];
  checked: boolean;
  attempted: boolean;
  edited: boolean;
  social?: boolean;
}) {
  const facts = citations.filter(
    (c) => c.citation_role !== "response_guideline",
  );
  const guidelines = citations.filter(
    (c) => c.citation_role === "response_guideline",
  );
  return (
    <div className="draft-sources">
      {(!social || facts.length > 0) && <details className="source-group" data-testid="reply-evidence">
        <summary>
          <BookOpen size={15} />
          <strong>回复依据</strong>
          <span>{facts.length} 条</span>
        </summary>
        {facts.map((citation, i) => (
          <Source key={i} citation={citation} />
        ))}
        {!facts.length && (
          <p className="knowledge-help">暂无可引用的有效依据</p>
        )}
      </details>}
      <details
        className="source-group guideline-sources"
        data-testid="reply-guidelines"
      >
        <summary>
          <ShieldCheck size={15} />
          <strong>
            {edited
              ? "已编辑，待人工核对"
              : checked
                ? "规范检查通过"
                : attempted
                  ? "规范检查未通过"
                  : "规范尚未检查"}
          </strong>
          <span>
            {guidelines.length
              ? `店铺规范 ${guidelines.length} 条`
              : "默认规范"}
          </span>
        </summary>
        <p className="knowledge-help">
          默认直接回答要点，通常 1–3
          句，保留必要条件；事实须有依据，发送须人工确认。
        </p>
        {guidelines.map((citation, i) => (
          <Source key={i} citation={citation} />
        ))}
      </details>
    </div>
  );
}
