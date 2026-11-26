import { useState } from "react";
import { api } from "../api";
import { errorMessage } from "../errors";
import {
  BookOpen,
  Activity,
  ArrowUpRight,
  ShieldCheck,
  MessageSquare,
  LoaderCircle,
} from "lucide-react";

export default function Login({
  onLogin,
  error,
}: {
  onLogin: (u: string, p: string) => Promise<void>;
  error: string;
}) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [invite, setInvite] = useState("");
  const [confirm, setConfirm] = useState("");
  const [localError, setLocalError] = useState("");
  const [user, setUser] = useState("reviewer"),
    [password, setPassword] = useState(""),
    [busy, setBusy] = useState(false);
  return (
    <main className="login-page">
      <div className="login-story">
        <div className="brand light">
          <MessageSquare />
          知伴 <span>客服副驾驶</span>
        </div>
        <div>
          <h1>
            把时间留给客户，
            <br />
            把依据交给知伴。
          </h1>
          <p>
            在同一个工作台查看咨询、核对商品与店铺规则，
            <br />
            审核每一条回复，再从容发送。
          </p>
          <div className="login-benefits">
            <span>
              <BookOpen size={17} />
              回复有依据
            </span>
            <span>
              <ShieldCheck size={17} />
              人工做决定
            </span>
            <span>
              <Activity size={17} />
              过程可追溯
            </span>
          </div>
        </div>
        <small>拼多多客服副驾驶 · 第一阶段</small>
      </div>
      <div className="login-form-wrap">
        <form
          className="login-form"
          onSubmit={async (e) => {
            e.preventDefault();
            setBusy(true);
            setLocalError("");
            try {
              if (mode === "register") {
                if (password !== confirm) {
                  setLocalError("两次输入的密码不一致。");
                  return;
                }
                await api("/auth/register", {
                  username: user,
                  password,
                  invite_code: invite,
                });
                setMode("login");
              }
              await onLogin(user, password);
            } catch (e) {
              setLocalError(errorMessage(e));
            } finally {
              setBusy(false);
            }
          }}
        >
          <h2>{mode === "login" ? "欢迎回来" : "创建账号"}</h2>
          <p>
            {mode === "login"
              ? "从一条有依据的回复，开始高效的一天。"
              : "使用商家开通码创建独立空间，或使用客服邀请码加入团队。"}
          </p>
          <div className="auth-tabs" role="tablist" aria-label="登录或注册">
            {(["login", "register"] as const).map((value) => (
              <button
                type="button"
                role="tab"
                key={value}
                aria-selected={mode === value}
                className={mode === value ? "selected" : ""}
                onClick={() => {
                  setMode(value);
                  setLocalError("");
                  if (value === "register") setUser("");
                }}
              >
                {value === "login" ? "登录" : "邀请码注册"}
              </button>
            ))}
          </div>
          <label>
            账号
            <input
              autoComplete="username"
              pattern={
                mode === "register" ? "[A-Za-z0-9_.\\-]{3,48}" : undefined
              }
              title={
                mode === "register"
                  ? "3–48 位字母、数字、点、横线或下划线"
                  : undefined
              }
              value={user}
              onChange={(e) => setUser(e.target.value)}
              required
            />
          </label>
          <label>
            密码
            <input
              type="password"
              autoComplete={
                mode === "login" ? "current-password" : "new-password"
              }
              minLength={mode === "register" ? 12 : undefined}
              maxLength={72}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </label>
          {mode === "register" && (
            <>
              <label>
                确认密码
                <input
                  type="password"
                  autoComplete="new-password"
                  value={confirm}
                  onChange={(e) => setConfirm(e.target.value)}
                  required
                />
              </label>
              <label>
                邀请码
                <input
                  autoComplete="off"
                  value={invite}
                  onChange={(e) => setInvite(e.target.value)}
                  required
                />
              </label>
              <p className="field-hint">
                密码至少 12
                个字符。商家开通码创建独立空间；客服邀请码加入对应团队。
              </p>
            </>
          )}
          {(localError || (mode === "login" && error)) && (
            <div role="alert" className="login-error">
              {localError || error}
            </div>
          )}
          <button className="primary" disabled={busy}>
            {busy ? (
              <LoaderCircle className="spin" size={18} />
            ) : (
              <>
                {mode === "login" ? "登录工作台" : "注册并进入工作台"}
                <ArrowUpRight size={18} />
              </>
            )}
          </button>
          <div className="login-note">
            <ShieldCheck size={15} />
            账号权限仅限绑定店铺；回复须经过人工审批。
          </div>
        </form>
      </div>
    </main>
  );
}
