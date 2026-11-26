export const labels: Record<string, string> = {
  online: "在线",
  disconnected: "已断开",
  connecting: "连接中",
  authenticating: "认证中",
  reconnecting: "正在重连",
  failed: "连接失败",
  stopped: "已停止",
  waiting_human: "待审核",
  outdated: "草稿已过期",
  approved: "已批准",
  sending: "发送中",
  sent: "发送成功",
  send_failed: "发送失败",
  send_unknown: "发送结果待核实",
  rejected: "已拒绝",
  transferred: "人工接管",
  agent_running: "正在生成",
  queued: "等待生成",
  running: "正在生成",
  completed: "生成完成",
  generation_failed: "生成失败",
  superseded: "已被新消息替代",
  persisted: "已接收",
  needs_human: "需人工处理",
  insufficient_evidence: "依据不足",
};
export function label(value: string) {
  return labels[value] || value;
}
export function time(value: string | number | null) {
  return value
    ? new Date(value).toLocaleTimeString("zh-CN", {
        hour: "2-digit",
        minute: "2-digit",
      })
    : "时间未知";
}
export function buyer(value: string) {
  return `访客 · ${value.slice(-4)}`;
}
