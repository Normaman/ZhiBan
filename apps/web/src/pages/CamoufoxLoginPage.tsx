import { useEffect, useState } from "react";
import { api, ApiError } from "../api";
import { errorMessage } from "../errors";
import type { Shop } from "../types";

interface LoginState {
  state: string;
  error_code: string;
  session_id: string;
  saved: boolean;
  configured: boolean;
  expires_in: number;
  platform_shop_id: string;
  connector_account_id: string;
}
const endpoint = "/admin/shop/camoufox-login";
const names: Record<string, string> = {
  idle: "待授权", starting: "准备登录页", waiting_login: "等待扫码",
  checking_identity: "核对店铺", confirm_identity: "等待确认",
  completed: "已保存", failed: "授权未完成", cancelled: "已取消",
  cancelling: "正在取消",
};

export default function CamoufoxLoginPage({shop, isAdmin}: {shop?: Shop; isAdmin: boolean}) {
  const [login, setLogin] = useState<LoginState | null>(null);
  const [image, setImage] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!isAdmin || !shop || shop.binding_state !== "bound") return;
    let active = true;
    let timer: number | undefined;
    setLogin(null); setImage(""); setError("");
    async function poll() {
      try {
        const value = await api<LoginState>(endpoint);
        if (!active) return;
        setLogin(value);
        if (value.state === "waiting_login") {
          try {
            const frame = await api<{image: string}>(`${endpoint}/frame?sessionId=${encodeURIComponent(value.session_id)}`);
            if (active) setImage(frame.image);
          } catch (cause) {
            if (active) setImage("");
            if (!(cause instanceof ApiError && ["CAMOUFOX_LOGIN_FRAME_UNAVAILABLE", "CAMOUFOX_LOGIN_SESSION_INVALID"].includes(cause.code))) {
              if (active) setError(errorMessage(cause));
            }
          }
        } else setImage("");
      } catch (cause) { if (active) setError(errorMessage(cause)); }
      finally { if (active) timer = window.setTimeout(() => void poll(), 1200); }
    }
    void poll();
    return () => { active = false; window.clearTimeout(timer); };
  }, [shop?.id, shop?.binding_state, isAdmin]);

  async function act(action: string, extra?: Record<string, number>) {
    if (busy) return;
    setBusy(true); setError("");
    try {
      const result = await api<LoginState>(`${endpoint}/${action}`,
        action === "start" ? {} : {session_id: login?.session_id, ...extra});
      setLogin(result);
      if (result.error_code) setError(errorMessage(new ApiError(502, result.error_code)));
    } catch (cause) { setError(errorMessage(cause)); }
    finally { setBusy(false); }
  }

  const allowed = isAdmin && shop?.binding_state === "bound";
  const terminal = !login || ["idle", "failed", "cancelled", "completed"].includes(login.state);
  return (
    <main className="pdd-auth-page camoufox-auth-page">
      <header className="pdd-auth-header">
        <a className="secondary" href="/">返回工作台</a>
        <div className="pdd-auth-heading"><strong>订单旁路授权</strong><span>{shop?.name}</span></div>
        <span className="pdd-auth-state" role="status">{names[login?.state || "idle"]}</span>
      </header>
      {!allowed ? <section className="pdd-auth-message"><h1>请先连接店铺</h1><p>由当前店铺管理员完成旁路扫码授权。</p></section>
        : login && !login.configured ? <section className="pdd-auth-message"><h1>尚未配置旁路网关</h1><p>请先按部署文档启动网关并配置服务地址。</p></section>
        : login?.state === "waiting_login" ? (
          <section className="camoufox-login-viewer">
            <p>在下方切换到拼多多二维码入口，使用商家手机扫码。剩余 {login.expires_in} 秒。</p>
            {image ? <button className="camoufox-login-screen" disabled={busy} aria-label="操作拼多多扫码登录页面"
              onClick={event => {
                if (event.detail === 0) return;
                const rect = event.currentTarget.querySelector("img")?.getBoundingClientRect();
                if (!rect || !rect.width || !rect.height) return;
                void act("click", {x: Math.min(.999999, Math.max(0, (event.clientX - rect.left) / rect.width)),
                  y: Math.min(.999999, Math.max(0, (event.clientY - rect.top) / rect.height))});
              }}><img src={image} alt="独立拼多多登录页面" draggable={false} /></button>
              : <p role="status">正在获取登录页面…</p>}
            <div className="pdd-auth-actions">
              <button className="primary" disabled={busy} onClick={() => void act("continue")}>已完成扫码，核对店铺</button>
              <button className="secondary" disabled={busy} onClick={() => void act("cancel")}>取消</button>
            </div>
          </section>
        ) : login?.state === "confirm_identity" ? (
          <section className="pdd-auth-message">
            <h1>确认旁路授权店铺</h1>
            <dl><div><dt>平台店铺编号</dt><dd>{login.platform_shop_id}</dd></div>
              <div><dt>客服账号</dt><dd>{login.connector_account_id}</dd></div></dl>
            <div className="pdd-auth-actions">
              <button className="primary" disabled={busy} onClick={() => void act("confirm")}>确认并保存</button>
              <button className="secondary" disabled={busy} onClick={() => void act("cancel")}>取消</button>
            </div>
          </section>
        ) : terminal ? (
          <section className="pdd-auth-message">
            <h1>{login?.saved ? "旁路登录态已保存" : "独立扫码授权"}</h1>
            <p>此授权仅用于订单旁路，与店铺主连接分别保存。</p>
            {login?.error_code && <p role="alert">{errorMessage(new ApiError(502, login.error_code))}</p>}
            <button className="primary" disabled={busy} onClick={() => void act("start")}>{login?.saved ? "重新授权" : "开始扫码授权"}</button>
          </section>
        ) : <section className="pdd-auth-message"><h1>{names[login?.state || "starting"]}…</h1>
          <button className="secondary" disabled={busy} onClick={() => void act("cancel")}>取消</button></section>}
      {error && <p className="pdd-auth-error" role="alert">{error}</p>}
    </main>
  );
}
