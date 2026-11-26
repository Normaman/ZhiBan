import { useEffect, useRef, useState } from "react";
import {
  X,
  Activity,
  Send,
  Sparkles,
  Check,
  RefreshCw,
  ShieldCheck,
  AlertTriangle,
} from "lucide-react";
import { api } from "../api";
import DraftSources from "./DraftSources";
import IntentDetails from "./IntentDetails";
import DraftProductCards from "./DraftProductCards";
import { label } from "../format";
import { messagePreview } from "../messages";
import type {
  ConversationDetail,
  ReplyDraft,
  PerformAction,
  Decision,
} from "../types";

const checkReasons: Record<string, string> = {
  price_scope: "价格口径不一致",
  price_value: "价格数值不一致",
  stock_value: "库存描述不一致",
  spec_identity: "规格或商品对应不一致",
  minimum_comparison: "最低价比较依据不足",
  unsupported_promise: "含无依据的承诺",
  missing_answer: "未完整回答当前问题",
  citation_missing: "引用依据不足",
  response_guideline: "未符合客服回答规范",
  reply_verbosity: "回复包含多余说明，需精简",
  business_claims: "非业务回应中包含未经核实的业务事实",
  other: "依据未能完整支持草稿",
};
const failureReasons: Record<string, string> = {
  LIVE_INVENTORY_REQUIRED: "未取得该商品的实时库存，不能用每件数量代答",
  INTENT_ANALYSIS_FAILED: "未能可靠识别本次诉求，请重试或人工核实",
  SOCIAL_INTENT_CONFLICT: "本条消息包含业务诉求，不能仅作礼貌回应",
  SOCIAL_TOOL_NOT_ALLOWED: "回应超出非业务交流范围，请重新生成或人工核实",
  INTENT_UNCLEAR: "本次诉求仍有歧义，请先向买家澄清",
  ORDER_FACTS_REQUIRED: "需要核对该买家的订单信息",
  ORDER_DETAILS_INCOMPLETE: "订单已关联，但所需状态尚未取得，请人工核实",
  OFF_PLATFORM_SOLICITATION: "草稿涉及站外引流，已阻止发送",
  REVIEW_INCENTIVE: "草稿涉及评价激励，已阻止发送",
  UNAUTHORIZED_PROMISE: "草稿包含未经授权的售后承诺，已阻止发送",
  PRODUCT_CONTEXT_AMBIGUOUS: "本条咨询关联了不同商品，请先核实商品",
  DRAFT_NOT_SUPPORTED: "草稿与查询依据不一致",
  INVALID_MODEL_CITATIONS: "草稿引用无效",
  VERIFICATION_FAILED: "依据检查未完成",
  MODEL_TIMEOUT: "模型处理超时",
  MODEL_RESPONSE_INVALID: "模型返回格式不正确",
  MODEL_AUTH_REJECTED: "模型服务认证失败",
  MODEL_RATE_LIMITED: "模型服务请求受限",
  MODEL_SERVICE_UNAVAILABLE: "模型服务暂不可用",
  MODEL_REQUEST_FAILED: "模型请求未完成",
  RESPONSE_GUIDELINES_TOO_LONG: "客服回答规范过多，请精简后重新生成",
  RESPONSE_GUIDELINES_REQUIRE_MODEL:
    "客服回答规范需要启用模型，或由人工按规范回复",
};

export default function DraftPanel({
  detail,
  online,
  busy,
  action,
  notify,
  sendEnabled = true,
}: {
  detail: ConversationDetail;
  online: boolean;
  busy: boolean;
  action: PerformAction;
  notify: (s: string) => void;
  sendEnabled?: boolean;
}) {
  const draft = detail.drafts[0] as ReplyDraft | undefined;
  const [content, setContent] = useState(draft?.content || ""),
    [base, setBase] = useState(draft ? `${draft.id}:${draft.revision}` : ""),
    [edited, setEdited] = useState(false);
  const submitting = useRef(false);
  const [selectedCards, setSelectedCards] = useState<string[]>(
    draft?.product_cards?.map((c) => c.goods_id) || [],
  );
  const key = draft ? `${draft.id}:${draft.revision}` : "";
  useEffect(() => {
    if (key !== base) {
      if (edited) notify("草稿已有新版本，已显示最新内容，请重新核对。");
      setContent(draft?.content || "");
      setBase(key);
      setEdited(false);
      setSelectedCards(draft?.product_cards?.map((c) => c.goods_id) || []);
    } else if (!edited && draft) setContent(draft.content);
  }, [key]);
  const waiting = draft?.state === "waiting_human";
  const canApprove =
    waiting &&
    detail.state === "open" &&
    draft?.conversation_revision === detail.revision &&
    draft?.outcome === "draft" &&
    online &&
    content.trim() &&
    !busy &&
    sendEnabled;
  const latestInbound = [...detail.messages]
    .reverse()
    .find(
      (m) =>
        m.direction === "inbound" &&
        m.message_type !== "system_hint" &&
        m.message_type !== "order_info" &&
        !m.context_only,
    );
  const command = detail.send_commands.find((s) => s.draft_id === draft?.id);
  const run = detail.agent_runs.find((r) => r.id === draft?.run_id);
  const currentRun = detail.agent_runs.find(
    (r) =>
      ["queued", "running"].includes(r.state) &&
      r.conversation_revision === detail.revision,
  );
  const activeRun = !!currentRun;
  const check = run?.verification?.checks?.slice(-1)[0];
  const questionChecks = draft?.question_results
    ?.filter((question) => question.outcome === "draft")
    .map((question) => question.verification?.checks?.slice(-1)[0]);
  const replyChecks = questionChecks?.length ? questionChecks : [check];
  const replyChecked =
    run?.engine === "llm_agent" &&
    draft?.outcome === "draft" &&
    replyChecks.every((result) => result?.supported && !result.issues.length);
  const unresolvedQuestions = (draft?.question_results || []).filter(
    (question) =>
      question.outcome !== "draft" &&
      (!detail.pending_questions ||
        detail.pending_questions.some(
          (pending) => pending.message_id === question.message_id,
        )),
  );
  const rejectedReasons =
    check && !check.supported
      ? check.issues
          .map((code) => checkReasons[code] || checkReasons.other)
          .join("、")
      : "";
  const batchRiskCodes = draft?.question_results?.length
    ? [
        ...new Set(
          unresolvedQuestions.flatMap((question) => question.risk_codes || []),
        ),
      ]
    : draft?.risk_codes || [];
  const failureReason = batchRiskCodes
    ?.map((code) => failureReasons[code])
    .filter(Boolean)
    .join("、");
  const latestDraft = detail.drafts.find(
    (d) => d.message_id === latestInbound?.id,
  );
  const canRegenerate =
    detail.state === "open" &&
    !!latestInbound &&
    (detail.pending_questions === undefined ||
      detail.pending_questions.length > 0) &&
    !activeRun &&
    !detail.send_commands.some((s) => s.draft_id === latestDraft?.id) &&
    !["approved", "sending", "sent", "send_failed", "send_unknown"].includes(
      latestDraft?.state || "",
    );
  const sendReason =
    detail.state === "transferred"
      ? "当前由人工接待，请在对话下方发送人工回复。"
      : activeRun
        ? currentRun?.state === "queued"
          ? "咨询已排队，等待合并或上一任务完成。"
          : "正在生成，草稿就绪后可编辑并发送。"
        : !draft
          ? "请先生成草稿，再确认发送。"
          : ["approved", "sending"].includes(draft.state)
            ? "已确认，正在发送，请勿重复提交。"
            : draft.state === "sent"
              ? "这份回复已发送，无需重复提交。"
              : draft.state === "send_unknown"
                ? "发送结果待核实，暂不能再次发送。"
                : draft.state === "send_failed"
                  ? "发送失败，请使用重试发送。"
                  : !waiting || draft.conversation_revision !== detail.revision
                    ? "旧草稿已失效，请先点击重新生成草稿。"
                    : draft.outcome !== "draft"
                      ? "当前建议不可直接发送，请人工接管后回复，或补充资料重新生成。"
                      : !sendEnabled
                        ? "当前会话未开放真实发送。"
                        : !online
                          ? "店铺离线，恢复连接后才能发送。"
                          : !content.trim()
                            ? "请填写回复内容。"
                            : "正在提交，请稍候。";
  const missingLabels: Record<string, string> = {
    faq_missing: "店铺尚未发布适用的问答，请补充已核实的答案。",
    sku_query_failed: "规格实时查询失败，请重试或人工核实。",
    sku_details_incomplete:
      "规格价格、库存或上架信息不完整，暂不能确定最低价。",
    sku_not_found: "未找到匹配规格，请核对颜色、型号或尺寸。",
    product_query_failed: "商品查询失败，请重试或人工核实。",
    product_selection: "需要先确认咨询商品或具体规格。",
    product_not_found: "未找到对应商品，请核对商品信息。",
    inventory_unavailable: "平台未返回库存，需要人工核实。",
    product_specs_missing: "缺少该商品的参数资料。",
    product_shipping_unavailable: "商品发货或运费设置未取得，请人工核实。",
    shipping_policy_missing: "店铺尚未发布适用的发货规则。",
    after_sales_policy_missing: "店铺尚未发布适用的售后规则。",
  };
  const missingReason = Object.keys(missingLabels).find((code) =>
    run?.missing_requirements?.includes(code),
  );
  const engineLabel =
    run?.engine === "llm_agent"
      ? `模型编排 · ${run.model || "已配置模型"}`
      : run?.engine === "extractive"
        ? "规则摘录 · 未调用模型"
        : run?.engine === "extractive_fallback"
          ? "规则回退 · 模型调用失败"
          : run?.engine === "policy"
            ? "规则检查 · 未调用模型"
            : run?.engine === "llm_evidence_selection"
              ? "模型筛选依据 · 旧版流程"
              : "生成方式未记录";
  async function decide(decision: Decision) {
    if (submitting.current || !draft) return;
    submitting.current = true;
    try {
      await action(
        () =>
          api(`/reply-drafts/${draft.id}/decisions`, {
            decision,
            revision: draft.revision,
            final_content: decision === "approve" ? content : "",
            product_ids: decision === "approve" ? selectedCards : [],
          }),
        decision === "approve"
          ? "审批已记录，正在提交发送。"
          : decision === "transfer"
            ? "会话已转入人工接管。"
            : "已拒绝该草稿。",
      );
    } finally {
      submitting.current = false;
    }
  }
  return (
    <aside className="draft-panel">
      <div className="draft-heading">
        <div>
          <Sparkles size={20} />
          <h2>回复副驾驶</h2>
        </div>
        <details className="pending-menu">
          <summary className="pending-count" data-testid="pending-questions">
            {detail.pending_questions?.length
              ? `待回复 ${detail.pending_questions.length}`
              : "暂无待回复"}
          </summary>
          {!!detail.pending_questions?.length && (
            <div className="pending-menu-list">
              <small>旧咨询保留在此，不会自动并入本轮。</small>
              {detail.pending_questions.map((q) => {
                const decided = detail.drafts.some(
                  (d) =>
                    d.message_id === q.message_id &&
                    detail.send_commands.some((c) => c.draft_id === d.id),
                );
                return (
                  <div key={q.message_id}>
                    <p>{messagePreview(q.content, q.message_type)}</p>
                    <button
                      className="secondary"
                      disabled={
                        busy ||
                        activeRun ||
                        detail.state !== "open" ||
                        decided ||
                        detail.send_commands.some((c) =>
                          ["approved", "sending", "send_unknown"].includes(
                            c.state,
                          ),
                        )
                      }
                      onClick={() =>
                        action(
                          () =>
                            api(
                              `/messages/${q.message_id}/agent-runs?single=true`,
                              {},
                            ),
                          "已单独重新处理这条咨询。",
                        )
                      }
                    >
                      {decided ? "请人工补充回复" : "单独重试"}
                    </button>
                  </div>
                );
              })}
            </div>
          )}
        </details>
      </div>
      {draft ? (
        <>
          <div className="draft-body">
            <div className="draft-status">
              <span
                className={`status-tag ${draft.state === "sent" ? "sent" : draft.outcome !== "draft" ? "warning" : ""}`}
              >
                {label(draft.state)}
              </span>
              <span>版本 v{draft.revision}</span>
            </div>
            {detail.state === "transferred" && (
              <div className="callout warning">
                会话已由人工接管，后续消息不会自动触发 Agent。
              </div>
            )}
            {detail.state === "open" && draft.state === "transferred" && (
              <div className="callout">
                Agent
                已恢复。可点击下方「重新生成草稿」处理当前咨询，也可等待新消息。此前草稿不会自动发送。
              </div>
            )}
            {draft.outcome !== "draft" && detail.state !== "transferred" && (
              <div className="callout warning">
                <AlertTriangle size={16} />
                <span data-testid="verification-reason">
                  {rejectedReasons ||
                    failureReason ||
                    (draft.outcome === "needs_human"
                      ? "该咨询需人工处理，请接管会话。"
                      : missingLabels[missingReason || ""] ||
                        "尚缺少可验证的信息，请查看下方说明。")}
                </span>
              </div>
            )}
            {!online && (
              <div className="callout warning">
                <AlertTriangle size={16} />
                <span>店铺连接异常，恢复在线后才能发送。</span>
              </div>
            )}
            {draft.state === "send_unknown" && (
              <div className="callout warning">
                <AlertTriangle size={16} />
                <span>
                  平台是否送达尚未确认。请核对平台消息记录，暂不能重复发送。
                </span>
              </div>
            )}
            {!!draft.covered_questions?.length && (
              <details
                className="batch-questions"
                data-testid="draft-coverage"
                open={draft.covered_questions.length > 1}
              >
                <summary>
                  本次草稿覆盖 {draft.covered_questions.length} 条咨询
                </summary>
                <ol>
                  {draft.covered_questions.map((q) => (
                    <li key={q.message_id}>
                      {messagePreview(q.content, q.message_type)}
                    </li>
                  ))}
                </ol>
                <small>请确认最终回复已回答以上咨询，再点击发送。</small>
              </details>
            )}
            {!!unresolvedQuestions.length && (
              <details
                className="batch-questions"
                data-testid="unresolved-questions"
                open={draft.outcome !== "draft"}
              >
                <summary>
                  {draft.outcome === "draft" ? "另有 " : "待处理问题 · "}
                  {unresolvedQuestions.length} 条需补充处理
                </summary>
                <ul>
                  {unresolvedQuestions.map((q) => (
                    <li key={q.message_id}>
                      {messagePreview(
                        detail.pending_questions?.find(
                          (p) => p.message_id === q.message_id,
                        )?.content || "咨询",
                      )}{" "}
                      ·{" "}
                      {q.verification?.checks
                        ?.slice(-1)[0]
                        ?.issues.map(
                          (code) => checkReasons[code] || checkReasons.other,
                        )
                        .join("、") ||
                        q.risk_codes
                          ?.map((code) => failureReasons[code])
                          .find(Boolean) ||
                        (q.outcome === "insufficient_evidence"
                          ? "缺少资料"
                          : "需人工核实")}
                    </li>
                  ))}
                </ul>
                <small>发送本次回复不会将这些问题标为已回答。</small>
              </details>
            )}
            <div className="section-label">
              <span>建议回复</span>
              <span>{edited ? "已人工编辑" : "待你核对"}</span>
            </div>
            <div className="agent-execution" data-testid="agent-execution">
              <span>{engineLabel}</span>
              <IntentDetails run={run} />
              {run?.engine === "llm_agent" && (
                <small>模型调用 {run.model_calls || 0} 次 · 仍须人工核对</small>
              )}
              {run?.verification?.repair_attempted && check?.supported && (
                <small data-testid="verification-repaired">
                  已自动修正一次，依据检查通过
                </small>
              )}
              {!!run?.tool_trace?.length && (
                <details>
                  <summary>工具执行记录（{run.tool_trace.length}）</summary>
                  <ul>
                    {run.tool_trace.map((tool, index) => (
                      <li key={index}>
                        {tool.action === "catalog_price_comparison"
                          ? "目录商品比价"
                          : tool.action === "catalog_search"
                            ? "目录商品查询"
                            : (({
                            get_shop_products: "实时商品查询",
                            get_order_details: "订单只读查询",
                            get_product_shipping: "实时发货与运费查询",
                            get_product_skus: "实时规格价格与库存",
                            get_product_knowledge: "商品知识检索",
                            compare_catalog_prices: "跨商品实时价格比较",
                            search_customer_service_knowledge: "店铺规则检索",
                            transfer_conversation: "建议人工接管",
                          } as Record<string, string>)[tool.name] || "未知工具")}
                        <span>
                          {tool.status === "ok"
                            ? "完成"
                            : tool.status === "reused"
                              ? "复用查询结果"
                              : tool.status === "blocked"
                                ? "需确认对象"
                                : "失败"} ·{" "}
                          {tool.duration_ms} ms
                          {tool.retrieval &&
                            ` · ${tool.retrieval.mode === "hybrid" ? "混合召回＋重排" : tool.retrieval.mode === "bm25_degraded" ? "语义检索异常，已降级关键词" : "关键词检索"}`}
                        </span>
                      </li>
                    ))}
                  </ul>
                </details>
              )}
              {run?.history_truncated && (
                <small>本次使用最近会话上下文，较早内容未全部载入。</small>
              )}
            </div>
            <textarea
              aria-label="回复草稿"
              className="draft-editor"
              value={content}
              readOnly={!waiting || draft.outcome !== "draft"}
              onChange={(e) => {
                setContent(e.target.value);
                setEdited(true);
              }}
              maxLength={4000}
            />
            <div className="editor-foot">
              <span>{content.length} / 4000</span>
              <button
                disabled={!waiting || busy}
                onClick={() => {
                  setContent(draft.content);
                  setEdited(false);
                }}
              >
                恢复草稿
              </button>
            </div>
            <DraftProductCards
              key={`cards:${key}`}
              draft={draft}
              content={content}
              selected={
                waiting
                  ? selectedCards
                  : command?.product_cards?.map((c) => c.goods_id) || []
              }
              onSelect={setSelectedCards}
              editable={
                waiting && draft.outcome === "draft" && detail.state === "open"
              }
              busy={busy || activeRun}
              action={action}
            />
            {!!command?.receipt?.parts?.length && (
              <div
                className="reply-part-status"
                data-testid="reply-part-status"
              >
                {command.receipt.parts.map((part, i) => (
                  <p key={part.key}>
                    <span>
                      {part.kind === "text" ? "文字" : `商品卡片 ${i}`}
                    </span>
                    <span>
                      {part.state === "sent"
                        ? "已发送"
                        : part.state === "send_failed"
                          ? "发送失败"
                          : "结果待核实"}
                    </span>
                  </p>
                ))}
              </div>
            )}
            {command?.error_code?.startsWith("CARD_PRODUCT_") && (
              <p className="callout warning">
                {command.error_code === "CARD_PRODUCT_UNAVAILABLE"
                  ? "推荐商品已无库存或不在当前在售列表，请核实后处理。"
                  : "发送前商品查询失败，请稍后重试。"}
              </p>
            )}
            <DraftSources
              key={key}
              citations={draft.citations}
              checked={!!replyChecked}
              attempted={replyChecks.some(Boolean)}
              edited={
                edited ||
                !!draft.cards_edited ||
                (waiting &&
                  JSON.stringify(selectedCards) !==
                    JSON.stringify(
                      draft.product_cards?.map((c) => c.goods_id) || [],
                    ))
              }
              social={
                run?.understanding?.intents?.length === 1 &&
                run.understanding.intents[0] === "social"
              }
            />
            <details className="trace-note">
              <summary>查看处理编号</summary>
              <Activity size={14} />
              知识版本 {draft.knowledge_version || "未设置"}
              <br />
              <span>任务 {draft.run_id?.slice(0, 8)}</span>
            </details>
          </div>
          <div className="approval-actions">
            {command?.state === "send_failed" && detail.state === "open" && (
              <button
                className="primary"
                disabled={!online || busy || !sendEnabled}
                onClick={() =>
                  action(
                    () => api(`/send-commands/${command.id}/retry`, {}),
                    "正在重试已审批的回复。",
                  )
                }
              >
                <RefreshCw size={16} />
                重试发送
              </button>
            )}
            <button
              data-testid="approve-send"
              className="primary"
              disabled={!canApprove || activeRun}
              onClick={() => decide("approve")}
            >
              <Send size={16} />
              {busy ? "正在提交…" : "确认并发送"}
            </button>
            {(!canApprove || activeRun) && <p role="status">{sendReason}</p>}
            {waiting && (
              <>
                <div className="secondary-actions">
                  <button disabled={busy} onClick={() => decide("reject")}>
                    <X size={15} />
                    拒绝草稿
                  </button>
                </div>
              </>
            )}
            {detail.state === "open" && latestInbound && (
              <button
                className="secondary"
                disabled={busy || !canRegenerate}
                onClick={() =>
                  action(
                    () =>
                      api(
                        `/messages/${latestInbound?.id || draft.message_id}/agent-runs`,
                        {},
                      ),
                    "正在重新生成草稿，完成后仍需人工审批。",
                  )
                }
              >
                <RefreshCw size={15} />
                {activeRun
                  ? currentRun?.state === "queued"
                    ? "等待生成…"
                    : "正在生成草稿…"
                  : "重新生成草稿"}
              </button>
            )}
            {draft.state === "sent" && (
              <div className="sent-confirm" data-testid="send-success">
                <Check size={18} />
                回复已发送
              </div>
            )}
            <p>
              <ShieldCheck size={12} />
              以审批记录中的最终内容发送
            </p>
          </div>
        </>
      ) : (
        <div className="draft-empty">
          <div className="empty-spark">
            <Sparkles size={26} />
          </div>
          <h3>{activeRun ? "正在准备回复建议" : "等待回复草稿"}</h3>
          <p>收到咨询后，副驾驶将检索商品资料和店铺规则。</p>
          <button className="primary" data-testid="approve-send" disabled>
            <Send size={16} />
            确认并发送
          </button>
          <p role="status">{sendReason}</p>
          {latestInbound && (
            <button
              className="secondary"
              disabled={busy || !canRegenerate}
              onClick={() =>
                action(() =>
                  api(`/messages/${latestInbound.id}/agent-runs`, {}),
                )
              }
            >
              <RefreshCw size={15} />
              生成草稿
            </button>
          )}
        </div>
      )}
    </aside>
  );
}
