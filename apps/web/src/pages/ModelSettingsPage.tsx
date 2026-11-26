import { useEffect, useState } from "react";
import { Settings, RefreshCw, ShieldCheck, LoaderCircle } from "lucide-react";
import { api } from "../api";
import { errorMessage } from "../errors";
import type { ModelSettings, PerformAction } from "../types";

export default function ModelSettingsPage({
  action,
  busy,
}: {
  action: PerformAction;
  busy: boolean;
}) {
  const [configuration, setConfiguration] = useState<ModelSettings | null>(
    null,
  );
  const [baseUrl, setBaseUrl] = useState("");
  const [model, setModel] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [rounds, setRounds] = useState(4);
  const [timeout, setTimeoutSeconds] = useState(70);
  const [error, setError] = useState("");

  function apply(value: ModelSettings) {
    setConfiguration(value);
    setBaseUrl(value.latest?.base_url || "");
    setModel(value.latest?.model || "");
    setRounds(value.latest?.max_rounds || 4);
    setTimeoutSeconds(value.latest?.timeout_seconds || 70);
    setApiKey("");
    setError("");
  }
  async function load() {
    try {
      apply(await api<ModelSettings>("/admin/model-config"));
    } catch (error) {
      setError(errorMessage(error));
    }
  }
  useEffect(() => {
    let active = true;
    api<ModelSettings>("/admin/model-config")
      .then((value) => {
        if (active) apply(value);
      })
      .catch((error) => {
        if (active) setError(errorMessage(error));
      });
    return () => {
      active = false;
    };
  }, []);
  const latest = configuration?.latest;
  const dirty =
    baseUrl !== (latest?.base_url || "") ||
    model !== (latest?.model || "") ||
    rounds !== (latest?.max_rounds || 4) ||
    timeout !== (latest?.timeout_seconds || 70) ||
    !!apiKey;
  async function operation(path: string, message: string) {
    if (!configuration) return;
    await action(async () => {
      apply(
        await api<ModelSettings>(path, { revision: configuration.revision }),
      );
    }, message);
  }
  return (
    <>
      <section className="page-heading">
        <div>
          <h1>模型配置</h1>
          <p>使用你自己的模型服务，为店铺启用回复副驾驶。</p>
        </div>
        <button className="secondary" onClick={load} disabled={busy}>
          <RefreshCw size={16} />
          刷新配置
        </button>
      </section>
      {error && (
        <div className="callout warning" role="alert">
          {error}
        </div>
      )}
      <div className="model-settings-layout">
        <form
          className="card model-settings-form"
          onSubmit={(event) => {
            event.preventDefault();
            if (!configuration) return;
            action(async () => {
              apply(
                await api<ModelSettings>("/admin/model-config", {
                  revision: configuration.revision,
                  base_url: baseUrl,
                  model,
                  api_key: apiKey || null,
                  max_rounds: rounds,
                  timeout_seconds: timeout,
                }),
              );
            }, "配置已保存，请测试通过后启用。");
          }}
        >
          <div className="card-heading">
            <h2>连接模型服务</h2>
            <ShieldCheck size={18} />
          </div>
          <p className="field-hint">保存并测试通过后，再启用配置。</p>
          <label>
            接口基础地址
            <input
              type="url"
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
              placeholder="https://模型服务域名/v1"
              required
              maxLength={1000}
              disabled={busy || !configuration}
            />
          </label>
          <p className="field-hint">
            填写服务商提供的 Base URL，不含 /chat/completions。
          </p>
          <label>
            模型名称
            <input
              value={model}
              onChange={(e) => setModel(e.target.value)}
              required
              maxLength={200}
              placeholder="填写支持工具调用的模型名称"
              disabled={busy || !configuration}
            />
          </label>
          <label>
            API Key
            <input
              type="password"
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
              autoComplete="new-password"
              required={!latest}
              maxLength={4096}
              placeholder={
                latest
                  ? `已保存 ${latest.key_hint}，留空保留`
                  : "输入模型服务密钥"
              }
              disabled={busy || !configuration}
            />
          </label>
          <p className="field-hint">
            保存后不回显完整密钥。更换服务域名时需要重新填写。
          </p>
          <details className="model-advanced">
            <summary>高级设置</summary>
            <label>
              模型决策轮数
              <input
                type="number"
                min={1}
                max={8}
                value={rounds}
                onChange={(e) => setRounds(Number(e.target.value))}
                required
                disabled={busy || !configuration}
              />
            </label>
            <label>
              任务总超时（秒）
              <input
                type="number"
                min={5}
                max={70}
                value={timeout}
                onChange={(e) => setTimeoutSeconds(Number(e.target.value))}
                required
                disabled={busy || !configuration}
              />
            </label>
          </details>
          {!configuration?.storage_ready && configuration && (
            <div className="callout warning">
              配置服务暂不可用，请联系系统部署管理员。
            </div>
          )}
          <button
            className="primary"
            disabled={busy || !configuration?.storage_ready || !dirty}
          >
            {busy && <LoaderCircle size={16} className="spin" />}保存配置
          </button>
        </form>
        <section className="card model-settings-status">
          <div className="card-heading">
            <h2>启用状态</h2>
            <Settings size={18} />
          </div>
          <span
            className={`status-tag ${configuration?.status.enabled ? "sent" : "warning"}`}
            data-testid="model-active-status"
          >
            {configuration?.status.enabled ? "AI 已启用" : "AI 未启用"}
          </span>
          <p>
            {configuration?.status.enabled
              ? `当前模型：${configuration.status.model}`
              : "客服仍可查看咨询、审核规则摘录或接管会话。"}
          </p>
          {latest && (
            <>
              <div className="model-version">
                <strong>待配置版本 v{latest.version}</strong>
                <span>{latest.model}</span>
                <small>
                  {latest.test_state === "passed"
                    ? "连接与工具调用测试通过"
                    : latest.test_state === "failed"
                      ? "测试未通过，请检查地址、密钥及模型工具调用支持。"
                      : "尚未测试"}
                </small>
              </div>
              {dirty && (
                <p className="field-hint">表单有未保存修改，请保存后再测试。</p>
              )}
              <button
                className="secondary"
                disabled={busy || dirty}
                onClick={() =>
                  operation(
                    `/admin/model-config/${latest.id}/test`,
                    "模型测试已完成，请查看测试结果。",
                  )
                }
              >
                {busy && <LoaderCircle size={16} className="spin" />}
                测试连接与工具调用
              </button>
              <button
                className="primary"
                disabled={
                  busy ||
                  dirty ||
                  latest.test_state !== "passed" ||
                  configuration?.active_id === latest.id
                }
                onClick={() =>
                  operation(
                    `/admin/model-config/${latest.id}/activate`,
                    "模型已启用，新咨询将使用此配置，无需重启。",
                  )
                }
              >
                启用此配置
              </button>
            </>
          )}
          <button
            className="secondary"
            disabled={busy || !configuration?.status.enabled}
            onClick={() =>
              operation(
                "/admin/model-config/disable",
                "模型已停用，新任务不再调用模型。",
              )
            }
          >
            停用模型
          </button>
          <p className="field-hint">
            测试只发送内置样例，不包含买家消息。启用后，必要的会话与检索资料会发送给你配置的服务商，请确认其数据保留策略。
          </p>
          <p className="field-hint">
            配置切换对新任务生效；已经开始的任务继续使用原配置，已有草稿不会自动改写。
          </p>
        </section>
      </div>
    </>
  );
}
