import { useEffect, useRef, useState } from "react";
import { X } from "lucide-react";
import { api } from "../api";
import { errorMessage } from "../errors";

type Session = { id: string; current: boolean; browser: string; created_at: number; last_seen_at: number };
type Event = { event_type: string; created_at: number };
const names: Record<string, string> = {
  "login.success": "登录成功", "login.failed": "登录失败", "login.limited": "登录受限",
  "logout.success": "退出登录", "session.expired": "会话过期", "session.revoked": "注销会话",
  "sessions.revoked": "注销全部会话", "password.changed": "修改密码",
  "reauth.success": "密码复核成功", "reauth.failed": "密码复核失败", "reauth.limited": "密码复核受限",
  "password.failed": "改密验证失败", "password.limited": "改密验证受限",
};
function browser(value: string) {
  const name = /Edg\//.test(value) ? "Edge" : /Firefox\//.test(value) ? "Firefox" : /Chrome\//.test(value) ? "Chrome" : /Safari\//.test(value) ? "Safari" : "浏览器";
  const device = /Android/.test(value) ? "Android" : /iPhone|iPad/.test(value) ? "iOS" : /Windows/.test(value) ? "Windows" : /Mac/.test(value) ? "macOS" : /Linux/.test(value) ? "Linux" : "其他设备";
  return `${name} · ${device}`;
}
export default function AccountSecurity({ onClose }: { onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [sessions, setSessions] = useState<Session[]>([]);
  const [events, setEvents] = useState<Event[]>([]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  async function refresh() {
    const [active, recent] = await Promise.all([api<Session[]>("/auth/sessions"), api<Event[]>("/auth/security-events")]);
    setSessions(active); setEvents(recent);
  }
  useEffect(() => {
    dialog.current?.showModal();
    refresh().catch((e) => setError(errorMessage(e)));
    return () => dialog.current?.close();
  }, []);
  async function run(action: () => Promise<void>) {
    if (busy) return;
    setBusy(true); setError("");
    try { await action(); } catch (e) { setError(errorMessage(e)); }
    finally { setBusy(false); }
  }
  return <dialog ref={dialog} className="security-dialog account-security" aria-labelledby="account-security-title"
    onCancel={(event) => { event.preventDefault(); if (!busy) onClose(); }}>
    <div className="security-title"><h2 id="account-security-title">账号安全</h2>
      <button aria-label="关闭账号安全" disabled={busy} onClick={onClose}><X size={20} /></button></div>
    {error && <p role="alert" className="callout warning">{error}</p>}
    <section><h3>已登录设备</h3>
      {sessions.map((session) => <div className="security-session" data-testid={`security-session-${session.id}`} key={session.id}>
        <div><strong>{browser(session.browser)}{session.current ? " · 当前会话" : ""}</strong>
          <small>最近访问 {new Date(session.last_seen_at).toLocaleString()}</small></div>
        {!session.current && <button disabled={busy} onClick={() => void run(async () => {
          await api(`/auth/sessions/${session.id}`, undefined, "DELETE"); await refresh();
        })}>注销</button>}
      </div>)}
      <button disabled={busy} onClick={() => {
        if (window.confirm("注销全部会话后，需要重新登录。继续吗？")) void run(async () => {
          await api("/auth/sessions/revoke-all", {}); window.location.reload();
        });
      }}>注销全部会话</button>
    </section>
    <section><h3>修改密码</h3><form onSubmit={(event) => {
      event.preventDefault();
      if (newPassword !== confirm) { setError("两次新密码不一致。"); return; }
      void run(async () => {
        await api("/auth/password", { current_password: currentPassword, new_password: newPassword });
        setCurrentPassword(""); setNewPassword(""); setConfirm(""); window.location.reload();
      });
    }}>
      <label>当前密码<input type="password" autoComplete="current-password" required maxLength={256}
        value={currentPassword} onChange={(e) => setCurrentPassword(e.target.value)} /></label>
      <label>新密码<input type="password" autoComplete="new-password" required minLength={12} maxLength={72}
        value={newPassword} onChange={(e) => setNewPassword(e.target.value)} /></label>
      <label>确认新密码<input type="password" autoComplete="new-password" required minLength={12} maxLength={72}
        value={confirm} onChange={(e) => setConfirm(e.target.value)} /></label>
      <p>至少 12 个字符。修改后，所有设备都需重新登录。</p>
      <button className="primary" disabled={busy}>保存新密码</button>
    </form></section>
    <section><h3>最近安全记录</h3>
      {events.map((event, index) => <div className="security-event" key={index}>
        <span>{names[event.event_type] || "账号安全事件"}</span>
        <small>{new Date(event.created_at).toLocaleString()}</small>
      </div>)}
    </section>
  </dialog>;
}
