import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import { errorMessage } from "../errors";
import type { Shop } from "../types";

interface PddLoginState {
  state: string;
  viewer_url: string;
  platform_shop_id: string;
  connector_account_id: string;
  error_code: string;
}

const activeStates = new Set([
  "starting",
  "waiting_login",
  "checking_identity",
  "saving",
  "declining",
  "cancelling",
  "verifying_connection",
]);

export default function PddLoginPage({
  shop,
  isAdmin,
}: {
  shop?: Shop;
  isAdmin: boolean;
}) {
  const [login, setLogin] = useState<PddLoginState | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const autoStarted = useRef(false);

  useEffect(() => {
    let active = true;
    if (!isAdmin || !shop) {
      return () => {
        active = false;
      };
    }
    async function load() {
      try {
        const current = await api<PddLoginState>("/admin/shop/pdd-login");
        if (!active) return;
        setLogin(current);
        const shouldStart =
          new URLSearchParams(window.location.search).get("start") === "1";
        if (
          shouldStart &&
          !autoStarted.current &&
          ["idle", "completed", "failed", "cancelled"].includes(current.state)
        ) {
          autoStarted.current = true;
          setBusy(true);
          setLogin(
            await api<PddLoginState>("/admin/shop/pdd-login/start", {}),
          );
          window.history.replaceState(null, "", "/pdd-login");
          setBusy(false);
        }
      } catch (cause) {
        if (active) {
          setError(errorMessage(cause));
          try {
            setLogin(await api<PddLoginState>("/admin/shop/pdd-login"));
          } catch {
            // Keep the original action error visible.
          }
          setBusy(false);
        }
      }
    }
    void load();
    return () => {
      active = false;
    };
  }, [isAdmin, shop?.id, shop?.binding_state]);

  useEffect(() => {
    if (!login || !activeStates.has(login.state)) return;
    const timer = window.setInterval(() => {
      api<PddLoginState>("/admin/shop/pdd-login")
        .then(setLogin)
        .catch(() => {});
    }, 1000);
    return () => window.clearInterval(timer);
  }, [login?.state]);

  async function submit(path: string, body?: unknown) {
    setBusy(true);
    setError("");
    try {
      setLogin(await api<PddLoginState>(path, body));
    } catch (cause) {
      setError(errorMessage(cause));
    } finally {
      setBusy(false);
    }
  }

  const isLiveShop = !!shop;
  const terminal = !login || ["idle", "failed", "cancelled"].includes(login.state);

  return (
    <main className="pdd-auth-page">
      <header className="pdd-auth-header">
        <a className="secondary" href="/">
          返回工作台
        </a>
        <div className="pdd-auth-heading">
          <strong>拼多多授权</strong>
          <span>{shop?.name || "当前店铺"}</span>
        </div>
        <span className="pdd-auth-state" role="status">
          {login?.state === "waiting_login" ? "等待登录" :
            login?.state === "confirm_identity" ? "核对店铺" :
              login?.state === "completed" ? "已完成" : "安全授权"}
        </span>
      </header>

      {!isAdmin || !isLiveShop ? (
        <section className="pdd-auth-message">
          <h1>当前店铺不能进行授权</h1>
          <p>请以管理员身份打开真实店铺完成授权。</p>
        </section>
      ) : login?.state === "waiting_login" ? (
        <section className="pdd-auth-viewer">
          <iframe
            title="拼多多商家登录"
            src={login.viewer_url}
            referrerPolicy="no-referrer"
          />
          <div className="pdd-auth-toolbar">
            <span>完成拼多多登录后，点击“已完成登录”继续核对店铺。</span>
            <button
              className="primary"
              disabled={busy}
              onClick={() => void submit("/admin/shop/pdd-login/continue", {})}
            >
              已完成登录
            </button>
            <button
              className="secondary"
              disabled={busy}
              onClick={() => void submit("/admin/shop/pdd-login/cancel", {})}
            >
              取消
            </button>
          </div>
        </section>
      ) : login?.state === "confirm_identity" ? (
        <section className="pdd-auth-message">
          <h1>请确认拼多多店铺</h1>
          <p>只有确认是当前店铺后，登录状态才会保存。</p>
          <dl>
            <div><dt>平台店铺编号</dt><dd>{login.platform_shop_id}</dd></div>
            <div><dt>客服账号</dt><dd>{login.connector_account_id}</dd></div>
          </dl>
          <div className="pdd-auth-actions">
            <button
              className="primary"
              disabled={busy}
              onClick={() => void submit("/admin/shop/pdd-login/confirm", { accepted: true })}
            >
              确认并保存
            </button>
            <button
              className="secondary"
              disabled={busy}
              onClick={() => void submit("/admin/shop/pdd-login/confirm", { accepted: false })}
            >
              不是这家店铺
            </button>
          </div>
        </section>
      ) : login?.state === "completed" ? (
        <section className="pdd-auth-message success">
          <h1>拼多多连接已核验</h1>
          <p>登录状态已加密保存，店铺连接正在恢复。可以返回工作台。</p>
          <a className="primary" href="/">返回工作台</a>
        </section>
      ) : login?.state === "failed" ? (
        <section className="pdd-auth-message">
          <h1>授权未完成</h1>
          <p>{login.error_code || error || "请重试授权流程。"}</p>
          <a className="primary" href="/pdd-login?start=1">重试授权</a>
        </section>
      ) : login?.state === "cancelled" || terminal ? (
        <section className="pdd-auth-message">
          <h1>连接拼多多店铺</h1>
          <p>将在此页面登录，并核对当前店铺后安全保存授权状态。</p>
          <button
            className="primary"
            disabled={busy}
            onClick={() => void submit("/admin/shop/pdd-login/start", {})}
          >
            开始授权
          </button>
        </section>
      ) : (
        <section className="pdd-auth-message" role="status">
          <h1>
            {login?.state === "checking_identity" ? "正在核对店铺身份…" :
              login?.state === "saving" || login?.state === "verifying_connection" ? "正在保存并检查连接…" :
                login?.state === "cancelling" || login?.state === "declining" ? "正在结束授权…" :
                  "正在准备登录页面…"}
          </h1>
          <p>请稍候，页面会自动更新状态。</p>
        </section>
      )}

      {error && <div className="pdd-auth-error" role="alert">{error}</div>}
    </main>
  );
}
