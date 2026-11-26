import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "../api";
import { errorMessage } from "../errors";
import { registerPasswordVerification } from "../security";

export default function PasswordVerification() {
  const dialog = useRef<HTMLDialogElement>(null);
  const request = useRef<{ resolve: () => void; reject: (e: Error) => void } | null>(null);
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => registerPasswordVerification(() => new Promise<void>((resolve, reject) => {
    request.current = { resolve, reject };
    setPassword(""); setError("");
    dialog.current?.showModal();
  })), []);
  function cancel() {
    request.current?.reject(new ApiError(403, "REAUTHENTICATION_CANCELLED"));
    request.current = null; setPassword(""); dialog.current?.close();
  }
  return <dialog ref={dialog} className="security-dialog" aria-labelledby="verify-password-title"
    onCancel={(event) => { event.preventDefault(); if (!busy) cancel(); }}>
    <form onSubmit={async (event) => {
      event.preventDefault(); if (busy) return;
      setBusy(true); setError("");
      try {
        await api("/auth/reauthenticate", { password });
        request.current?.resolve(); request.current = null;
        setPassword(""); dialog.current?.close();
      } catch (e) { setError(errorMessage(e)); }
      finally { setBusy(false); }
    }}>
      <h2 id="verify-password-title">确认是你本人</h2>
      <p>此操作需要再次验证当前账号密码。</p>
      <label>登录密码<input type="password" autoComplete="current-password" required maxLength={256}
        autoFocus value={password} onChange={(event) => setPassword(event.target.value)} /></label>
      {error && <p role="alert" className="callout warning">{error}</p>}
      <div className="security-actions">
        <button type="button" disabled={busy} onClick={cancel}>取消</button>
        <button className="primary" disabled={busy}>{busy ? "验证中…" : "验证并继续"}</button>
      </div>
    </form>
  </dialog>;
}
