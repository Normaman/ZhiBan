import { useEffect, useState } from "react";
import { Copy, Plus, Trash2, UserPlus } from "lucide-react";
import { api } from "../api";
import { errorMessage } from "../errors";
import { label, time } from "../format";
import ShopAvatar from "../components/ShopAvatar";
import type { PerformAction, Shop } from "../types";

interface Overview {
  workspace: { id: string; name: string; owner_user_id: string | null };
  shops: Shop[];
  members: {
    id: string;
    username: string;
    role: string;
    enabled: boolean;
    membership_enabled: boolean;
    revision: number;
  }[];
  invitations: {
    id: string;
    created_by: string;
    expires_at: number;
    consumed_at: number | null;
    revoked: boolean;
  }[];
}

interface PddLoginState {
  state: string;
  viewer_url: string;
  platform_shop_id: string;
  connector_account_id: string;
  error_code: string;
}

export default function WorkspacePage({
  shop,
  action,
  busy,
  onSwitch,
}: {
  shop: Shop;
  action: PerformAction;
  busy: boolean;
  onSwitch: (id: string) => Promise<void>;
}) {
  const [overview, setOverview] = useState<Overview | null>(null);
  const [error, setError] = useState("");
  const [name, setName] = useState("");
  const [shopName, setShopName] = useState(shop.name);
  const [nameEdited, setNameEdited] = useState(false);
  const [pddLogin, setPddLogin] = useState<PddLoginState | null>(null);
  const [camoufoxConfigured, setCamoufoxConfigured] = useState(false);
  useEffect(() => {
    let active = true;
    setCamoufoxConfigured(false);
    if (shop.binding_state === "bound") {
      api<{ configured: boolean }>("/admin/shop/camoufox-login")
        .then((value) => {
          if (active) setCamoufoxConfigured(value.configured);
        })
        .catch(() => {});
    }
    return () => {
      active = false;
    };
  }, [shop.id, shop.binding_state]);
  useEffect(() => {
    if (!nameEdited) setShopName(shop.name);
  }, [shop.name, nameEdited]);
  const [invite, setInvite] = useState<{
    invite_code: string;
    expires_at: number;
  } | null>(null);
  async function reload() {
    setOverview(await api<Overview>("/admin/workspace"));
  }
  useEffect(() => {
    let active = true;
    api<Overview>("/admin/workspace")
      .then((v) => {
        if (active) setOverview(v);
      })
      .catch((e) => {
        if (active) setError(errorMessage(e));
      });
    return () => {
      active = false;
    };
  }, []);
  useEffect(() => {
    let active = true;
    api<PddLoginState>("/admin/shop/pdd-login")
      .then((value) => {
        if (active) setPddLogin(value);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [shop.id, shop.binding_state]);
  useEffect(() => {
    if (
      !pddLogin ||
      ![
        "starting",
        "waiting_login",
        "checking_identity",
        "saving",
        "declining",
        "cancelling",
        "verifying_connection",
      ].includes(pddLogin.state)
    )
      return;
    const timer = window.setInterval(() => {
      api<PddLoginState>("/admin/shop/pdd-login")
        .then(setPddLogin)
        .catch(() => {});
    }, 1000);
    return () => window.clearInterval(timer);
  }, [pddLogin?.state]);
  const pddLoginActive =
    !!pddLogin &&
    !["idle", "completed", "failed", "cancelled"].includes(pddLogin.state);
  const pddLoginLabel: Record<string, string> = {
    starting: "准备中",
    waiting_login: "等待登录",
    checking_identity: "核对账号",
    confirm_identity: "确认店铺",
    saving: "保存中",
    declining: "结束中",
    cancelling: "结束中",
    verifying_connection: "核验中",
    completed: "已连接",
    failed: "授权失败",
  };
  const pddConnected =
    shop.binding_state === "bound" && shop.connection_state === "online";
  const pddStatusText = pddLoginActive
    ? pddLoginLabel[pddLogin?.state || ""] || "授权中"
    : pddConnected
      ? "在线"
      : pddLogin?.state === "completed"
        ? "授权完成"
        : pddLogin?.state === "failed"
          ? "授权失败"
          : label(shop.connection_state);
  return (
    <main className="settings-page workspace-settings">
      <section className="page-heading">
        <div>
          <h1>店铺与成员</h1>
          <p>{overview?.workspace.name || "管理你的店铺团队"}</p>
        </div>
      </section>
      {error && <p role="alert">{error}</p>}
      <section className="settings-card shop-guide">
        <h2>如何切换拼多多店铺</h2>
        <p className="field-hint">
          {overview?.shops.length === 1
            ? "当前账号只有一家店铺，接入第二家后即可切换。"
            : "从顶栏选择店铺，或点击下方列表中的切换按钮。"}
        </p>
        <ol className="shop-steps">
          <li>
            <span>1</span>
            <div>
              <strong>添加店铺</strong>
              <p>填写另一家店铺的名称。</p>
            </div>
          </li>
          <li>
            <span>2</span>
            <div>
              <strong>完成拼多多授权</strong>
              <p>
                {shop.binding_state === "bound"
                  ? "当前店铺已绑定，可在下方重新授权。"
                  : "在下方登录并核对店铺后即可完成绑定。"}
              </p>
            </div>
          </li>
          <li>
            <span>3</span>
            <div>
              <strong>选择店铺</strong>
              <p>切换后查看这家店铺的咨询和资料。</p>
            </div>
          </li>
        </ol>
        <p className="field-hint">
          添加拼多多店铺后，在下方登录并确认店铺身份即可完成连接。
        </p>
      </section>
      <section className="settings-card">
        <div className="shop-profile-heading">
          <ShopAvatar shop={shop} />
          <div>
            <h2>{shop.name}</h2>
            {shop.platform_name && shop.platform_name !== shop.name && (
              <p>平台店名：{shop.platform_name}</p>
            )}
            {shop.main_category && <p>主营类目：{shop.main_category}</p>}
          </div>
          <span className="subtle-tag">
            {shop.binding_state === "unbound" ? "待授权" : "当前店铺"}
          </span>
        </div>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void action(async () => {
              await api("/admin/shop/name", { name: shopName });
              setNameEdited(false);
              await reload();
            }, "店铺名称已更新。");
          }}
        >
          <label>
            店铺名称
            <input
              aria-label="店铺名称"
              value={shopName}
              maxLength={100}
              required
              onChange={(e) => {
                setShopName(e.target.value);
                setNameEdited(true);
              }}
            />
          </label>
          <button
            className="secondary"
            disabled={busy || !shopName.trim() || shopName === shop.name}
          >
            保存名称
          </button>
        </form>
        <p className="field-hint">
          {shop.binding_state === "unbound"
            ? "尚未绑定拼多多。请让部署管理员协助完成店铺授权登录。"
            : "平台连接、会话、知识库和模型配置按店铺独立管理。"}
        </p>
        <details>
          <summary>
            {shop.binding_state === "unbound"
              ? "授权时使用的店铺编号"
              : "接入店铺编号"}
          </summary>
          <code>{shop.id}</code>
          <button
            className="secondary"
            onClick={() =>
              void navigator.clipboard
                .writeText(shop.id)
                .catch(() => setError("复制失败，请手动复制店铺编号。"))
            }
          >
            <Copy size={14} />
            复制编号
          </button>
        </details>
      </section>
      {
        <section className="settings-card pdd-login-card">
          <h2>拼多多连接</h2>
          <a
            className="secondary pdd-open-link"
            href={`/pdd-login${pddLoginActive ? "" : "?start=1"}`}
            target="_blank"
            rel="noreferrer"
          >
            {pddLoginActive
              ? "打开授权页面"
              : pddConnected || pddLogin?.state === "completed"
                ? "重新授权"
                : pddLogin?.state === "failed"
                  ? "重试授权"
                  : "连接拼多多"}
          </a>
          {pddStatusText && (
            <span
              className={`pdd-connection-state ${pddLogin?.state === "failed" ? "error" : ""}`}
              role="status"
            >
              {pddStatusText}
            </span>
          )}
          {pddLogin?.state === "failed" && (
            <p className="pdd-login-error" role="alert">
              {pddLogin.error_code || "PDD_LOGIN_FAILED"}
            </p>
          )}
        </section>
      }
      {shop?.binding_state === "bound" && camoufoxConfigured && (
        <section className="settings-card">
          <h2>订单旁路授权</h2>
          <a
            className="secondary pdd-open-link"
            href="/pdd-camoufox-login"
            target="_blank"
            rel="noreferrer"
          >
            独立扫码授权
          </a>
        </section>
      )}
      <section className="settings-card">
        <h2>添加拼多多店铺</h2>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            let created = "";
            void (async () => {
              await action(async () => {
                const result = await api<{ shop_id: string }>(
                  "/admin/shops/live",
                  { name },
                );
                created = result.shop_id;
                setName("");
                await reload();
              }, "拼多多店铺已添加，请完成登录授权。");
              if (created) await onSwitch(created);
            })();
          }}
        >
          <label>
            新店铺名称
            <input
              aria-label="新店铺名称"
              value={name}
              maxLength={100}
              required
              onChange={(e) => setName(e.target.value)}
            />
          </label>
          <button className="secondary" disabled={busy || !name.trim()}>
            <Plus size={16} />
            添加真实店铺
          </button>
        </form>
        <ul className="workspace-list">
          {overview?.shops.map((s) => (
            <li key={s.id}>
              <div className="shop-list-identity">
                <ShopAvatar shop={s} />
                <div>
                  <strong>{s.name}</strong>
                  <small>
                    {s.binding_state === "unbound"
                      ? "待授权 · 完成登录绑定后接收咨询"
                      : "已绑定拼多多"}
                  </small>
                </div>
              </div>
              <button
                className="secondary"
                disabled={busy || s.id === shop.id}
                aria-label={s.id === shop.id ? "已选店铺" : `切换到${s.name}`}
                onClick={() => void onSwitch(s.id)}
              >
                {s.id === shop.id
                  ? "当前店铺"
                  : s.binding_state === "unbound"
                    ? "查看授权说明"
                    : "切换店铺"}
              </button>
              {s.id !== shop.id && (
                <button
                  className="text-button"
                  disabled={busy || overview.shops.length <= 1}
                  aria-label={`移除${s.name}`}
                  onClick={() => {
                    if (
                      !window.confirm(
                        `移除“${s.name}”？该店铺的会话、消息、知识库、商品资料和模型配置都会永久删除。`,
                      )
                    )
                      return;
                    void action(async () => {
                      await api(
                        `/admin/shops/${encodeURIComponent(s.id)}`,
                        undefined,
                        "DELETE",
                      );
                      await reload();
                    }, "店铺已移除。");
                  }}
                >
                  <Trash2 size={15} />
                  移除
                </button>
              )}
            </li>
          ))}
        </ul>
      </section>
      <section className="settings-card">
        <h2>
          <UserPlus size={18} />
          团队成员
        </h2>
        <p className="field-hint">
          受邀客服可处理本空间内的店铺咨询。停用或移除只影响当前工作空间，历史记录保留。
        </p>
        <ul className="workspace-list workspace-members">
          {overview?.members.map((m) => (
            <li key={m.id}>
              <span>{m.username}</span>
              <small>
                {m.role === "OWNER"
                  ? "所有者"
                  : m.role === "ADMIN"
                    ? "管理员"
                    : "客服"}
                {!m.enabled ? " · 已停用" : ""}
              </small>
              {m.role === "REVIEWER" &&
                m.id !== overview.workspace.owner_user_id && (
                  <div className="member-actions">
                    <button
                      className="secondary"
                      disabled={busy}
                      onClick={() =>
                        void action(
                          async () => {
                            try {
                              await api(
                                `/admin/workspace/members/${encodeURIComponent(m.id)}`,
                                {
                                  revision: m.revision,
                                  enabled: !m.membership_enabled,
                                },
                                "PATCH",
                              );
                            } finally {
                              await reload();
                            }
                          },
                          m.membership_enabled
                            ? "成员已停用，无法再访问本工作空间。"
                            : "成员已启用。",
                        )
                      }
                    >
                      {m.membership_enabled ? "停用" : "启用"}
                    </button>
                    <button
                      className="secondary danger-text"
                      disabled={busy}
                      onClick={() => {
                        if (
                          !window.confirm(
                            `从当前工作空间移除“${m.username}”？会保留账户和历史审批记录。`,
                          )
                        )
                          return;
                        void action(async () => {
                          try {
                            await api(
                              `/admin/workspace/members/${encodeURIComponent(m.id)}`,
                              { revision: m.revision },
                              "DELETE",
                            );
                          } finally {
                            await reload();
                          }
                        }, "成员已移除。");
                      }}
                    >
                      移除
                    </button>
                  </div>
                )}
            </li>
          ))}
        </ul>
        <button
          className="secondary"
          disabled={busy}
          onClick={() =>
            void action(async () => {
              setInvite(await api("/admin/workspace/invitations", {}));
              await reload();
            })
          }
        >
          生成客服邀请码
        </button>
        {invite && (
          <div className="invite-result" role="status">
            <p>邀请码仅供一人注册使用，48 小时后失效。</p>
            <input
              aria-label="客服邀请码"
              readOnly
              value={invite.invite_code}
            />
            <button
              className="secondary"
              onClick={() =>
                void navigator.clipboard
                  .writeText(invite.invite_code)
                  .catch(() => setError("复制失败，请手动复制邀请码。"))
              }
            >
              <Copy size={14} />
              复制邀请码
            </button>
          </div>
        )}
        <ul className="workspace-list">
          {overview?.invitations.map((i) => (
            <li key={i.id}>
              <span>
                {i.revoked
                  ? "已撤销"
                  : i.consumed_at
                    ? "已使用"
                    : i.expires_at <= Date.now()
                      ? "已过期"
                      : "待使用"}
                <small> · 有效至 {time(i.expires_at)}</small>
              </span>
              {!i.revoked && !i.consumed_at && i.expires_at > Date.now() && (
                <button
                  className="text-button"
                  disabled={busy}
                  onClick={() =>
                    void action(async () => {
                      await api(
                        `/admin/workspace/invitations/${i.id}/revoke`,
                        {},
                      );
                      await reload();
                      if (invite) setInvite(null);
                    })
                  }
                >
                  撤销
                </button>
              )}
            </li>
          ))}
        </ul>
      </section>
    </main>
  );
}
