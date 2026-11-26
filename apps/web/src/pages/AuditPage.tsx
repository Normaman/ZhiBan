import { useEffect, useState } from "react";
import {
  Activity,
  CheckCircle2,
  AlertTriangle,
  BookOpen,
  Search,
} from "lucide-react";
import { api } from "../api";
import type { AuditEvent } from "../types";

const actions: Record<string, [string, string]> = {
  "products.checked": ["核对商品（无变化）", "资料"],
  "products.details_started": ["开始同步商品分类与规格", "资料"],
  "message.persisted": ["收到买家或平台消息", "接收"],
  "draft.ready": ["回复草稿已生成", "生成"],
  "draft.insufficient_evidence": ["资料不足，等待核实", "生成"],
  "draft.needs_human": ["咨询需要人工处理", "生成"],
  "agent.failed": ["回复生成或依据检查失败", "生成"],
  "draft.regenerate": ["重新生成回复草稿", "生成"],
  "draft.cards_changed": ["更换草稿商品卡片", "审批"],
  "draft.approve": ["人工批准回复", "审批"],
  "draft.reject": ["人工拒绝草稿", "审批"],
  "draft.transfer": ["转为人工接管", "审批"],
  "conversation.transferred": ["人工接管会话", "审批"],
  "conversation.resumed": ["恢复客服助手", "审批"],
  "manual_reply.submitted": ["人工提交回复", "审批"],
  "send.sent": ["回复发送成功", "发送"],
  "send.send_failed": ["回复发送失败", "发送"],
  "send.send_unknown": ["回复送达情况待核实", "发送"],
  "send.retry": ["人工重试发送", "发送"],
  "knowledge.published": ["发布知识资料", "资料"],
  "member.enabled": ["启用工作空间成员", "账号"],
  "member.disabled": ["停用工作空间成员", "账号"],
  "member.removed": ["移除工作空间成员", "账号"],
  "knowledge.draft_updated": ["修改知识变更草稿", "资料"],
  "knowledge.draft_created": ["保存知识变更草稿", "资料"],
  "knowledge.submit": ["提交知识变更审核", "资料"],
  "knowledge.withdraw": ["撤回知识变更", "资料"],
  "knowledge.approve": ["审核通过知识变更", "资料"],
  "knowledge.reject": ["拒绝知识变更", "资料"],
  "knowledge.migrated": ["迁移知识资料", "资料"],
  "products.synced": ["同步平台商品", "资料"],
  "model.saved": ["保存模型配置", "配置"],
  "model.test_passed": ["模型连接测试通过", "配置"],
  "model.test_failed": ["模型连接测试失败", "配置"],
  "model.activated": ["启用模型配置", "配置"],
  "model.disabled": ["停用模型配置", "配置"],
  "user.registered": ["注册账号", "账号"],
  "shop.created": ["添加店铺", "账号"],
  "shop.renamed": ["修改店铺名称", "账号"],
  "shop.connector_bound": ["绑定拼多多店铺", "账号"],
  "invitation.created": ["生成客服邀请码", "账号"],
  "invitation.revoked": ["撤销客服邀请码", "账号"],
};
const actors: Record<string, string> = {
  connector: "拼多多",
  agent: "客服助手",
  bootstrap: "系统",
};
const isProblem = (event: AuditEvent) =>
  [
    "send.send_failed",
    "send.send_unknown",
    "model.test_failed",
    "agent.failed",
  ].includes(event.event_type);

export default function AuditPage() {
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [failed, setFailed] = useState(false);
  const [filter, setFilter] = useState("全部");
  const [search, setSearch] = useState("");
  useEffect(() => {
    let alive = true;
    const load = () =>
      api<AuditEvent[]>("/audit-events")
        .then((result) => {
          if (alive) {
            setEvents(result);
            setFailed(false);
          }
        })
        .catch(() => {
          if (alive) setFailed(true);
        });
    void load();
    const poll = () => {
      if (document.visibilityState === "visible") void load();
    };
    const timer = setInterval(poll, 3000);
    document.addEventListener("visibilitychange", poll);
    return () => {
      alive = false;
      clearInterval(timer);
      document.removeEventListener("visibilitychange", poll);
    };
  }, []);
  const visible = events.filter((event) => {
    const [title, category] = actions[event.event_type] || ["其他操作", "其他"];
    return (
      (filter === "全部" ||
        (filter === "异常" ? isProblem(event) : category === filter)) &&
      `${title} ${actors[event.actor] || event.actor}`.includes(search)
    );
  });
  const counters = [
    {
      title: "人工批准",
      count: events.filter((e) => e.event_type === "draft.approve").length,
      icon: CheckCircle2,
    },
    {
      title: "发送成功",
      count: events.filter((e) => e.event_type === "send.sent").length,
      icon: Activity,
    },
    {
      title: "异常记录",
      count: events.filter(isProblem).length,
      icon: AlertTriangle,
    },
    {
      title: "资料更新",
      count: events.filter((e) => actions[e.event_type]?.[1] === "资料").length,
      icon: BookOpen,
    },
  ];
  const distribution = [
    "接收",
    "生成",
    "审批",
    "发送",
    "资料",
    "配置",
    "账号",
    "其他",
  ]
    .map((name) => ({
      name,
      count: events.filter(
        (event) => (actions[event.event_type]?.[1] || "其他") === name,
      ).length,
    }))
    .filter((item) => item.count > 0);
  return (
    <>
      <section className="page-heading">
        <div>
          <h1>操作记录</h1>
          <p>查看谁批准了回复、发送是否异常，以及资料变更。</p>
        </div>
        <span className="subtle-tag">最近 {events.length} 条</span>
      </section>
      {failed && (
        <p role="alert" className="notification error">
          操作记录暂时无法加载。
        </p>
      )}
      <div className="audit-overview">
        {counters.map(({ title, count, icon: Icon }) => (
          <article className="card audit-metric" key={title}>
            <Icon size={18} />
            <strong>{count}</strong>
            <span>{title}</span>
          </article>
        ))}
      </div>
      <section className="card audit-card">
        <details className="audit-distribution">
          <summary>查看操作分布</summary>
          {distribution.map((item) => (
            <div key={item.name}>
              <span>{item.name}</span>
              <meter
                min={0}
                max={Math.max(1, events.length)}
                value={item.count}
                aria-label={`${item.name}记录数`}
              />
              <strong>{item.count}</strong>
            </div>
          ))}
        </details>
        <div className="audit-toolbar">
          <div className="record-filters" aria-label="筛选操作记录">
            {["全部", "审批", "发送", "资料", "异常"].map((item) => (
              <button
                key={item}
                aria-pressed={filter === item}
                className={filter === item ? "active" : ""}
                onClick={() => setFilter(item)}
              >
                {item}
              </button>
            ))}
          </div>
          <label className="record-search">
            <Search size={16} />
            <input
              aria-label="搜索操作记录"
              placeholder="搜索操作或执行人"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </label>
        </div>
        <ol className="audit-timeline">
          {visible.map((event) => (
            <li key={event.id} className={isProblem(event) ? "problem" : ""}>
              <span className="timeline-dot" />
              <div>
                <strong>{actions[event.event_type]?.[0] || "其他操作"}</strong>
                <p>
                  {actors[event.actor] || event.actor} ·{" "}
                  {new Date(event.created_at).toLocaleString("zh-CN")}
                </p>
                <details>
                  <summary>关联编号</summary>
                  <code>
                    记录：{event.subject_id}
                    <br />
                    追踪：{event.trace_id}
                  </code>
                </details>
              </div>
              <span className="subtle-tag">
                {actions[event.event_type]?.[1] || "其他"}
              </span>
            </li>
          ))}
        </ol>
        {!visible.length && (
          <div className="empty">
            <Activity />
            <p>没有匹配的操作记录</p>
          </div>
        )}
        <small className="record-scope">
          统计范围为当前返回的最近 100 条记录。
        </small>
      </section>
    </>
  );
}
