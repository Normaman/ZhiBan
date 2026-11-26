import { lazy, Suspense, useState, startTransition } from "react";
import {
  X,
  Inbox,
  BookOpen,
  Activity,
  Sparkles,
  Check,
  LogOut,
  MessageSquare,
  AlertTriangle,
  Settings,
  Store,
  Package,
} from "lucide-react";
import { label } from "./format";
import { connectionErrorMessage } from "./errors";
import { useWorkbench } from "./hooks/useWorkbench";
import Login from "./components/Login";
import PasswordVerification from "./components/PasswordVerification";
import AccountSecurity from "./components/AccountSecurity";
import DraftPanel from "./components/DraftPanel";
import ConversationList from "./components/ConversationList";
import ConversationView from "./components/ConversationView";
import ShopAvatar from "./components/ShopAvatar";
const Knowledge = lazy(() => import("./pages/KnowledgePage"));
const Audit = lazy(() => import("./pages/AuditPage"));
const ModelSettings = lazy(() => import("./pages/ModelSettingsPage"));
const Workspace = lazy(() => import("./pages/WorkspacePage"));
const PddLoginPage = lazy(() => import("./pages/PddLoginPage"));
const CamoufoxLoginPage = lazy(() => import("./pages/CamoufoxLoginPage"));
const OrdersPage = lazy(() => import("./pages/OrdersPage"));

const tabs = [
  { id: "inbox", icon: Inbox, title: "会话工作台" },
  { id: "orders", icon: Package, title: "订单与售后" },
  { id: "knowledge", icon: BookOpen, title: "知识库" },
  { id: "audit", icon: Activity, title: "操作记录" },
  { id: "settings", icon: Settings, title: "模型配置" },
  { id: "workspace", icon: Store, title: "店铺与成员" },
] as const;
type Tab = (typeof tabs)[number]["id"];

export default function App() {
  return <><PasswordVerification /><WorkbenchApp /></>;
}

function WorkbenchApp() {
  const [tab, setTab] = useState<Tab>("inbox");
  const [accountOpen, setAccountOpen] = useState(false);
  const {
    session,
    setReadingLatest,
    detail,
    selected,
    error,
    notice,
    busy,
    restoring,
    switching,
    switchShop,
    login,
    logout,
    selectConversation,
    action,
    notify,
    clearError,
  } = useWorkbench();
  if (restoring)
    return (
      <main className="session-loading" role="status">
        正在恢复登录状态…
      </main>
    );
  if (!session) return <Login onLogin={login} error={error} />;
  const isAdmin = session.roles?.includes("ADMIN");
  const shop = session.shops[0];
  if (window.location.pathname === "/pdd-camoufox-login")
    return <Suspense fallback={<main className="session-loading">正在加载授权页…</main>}>
      <CamoufoxLoginPage shop={shop} isAdmin={!!isAdmin} />
    </Suspense>;
  if (window.location.pathname === "/pdd-login")
    return (
      <Suspense fallback={<main className="session-loading">正在加载授权页…</main>}>
        <PddLoginPage shop={shop} isAdmin={!!isAdmin} />
      </Suspense>
    );
  const visibleTab =
    ["settings", "workspace"].includes(tab) && !isAdmin ? "inbox" : tab;
  const online = shop?.connection_state === "online";
  return (
    <div className="app-shell">
      {accountOpen && <AccountSecurity onClose={() => setAccountOpen(false)} />}
      <aside className="rail">
        <div className="brand-mark">
          <MessageSquare size={24} />
        </div>
        <div className="rail-nav">
          {tabs
            .filter(
              (item) => !["settings", "workspace"].includes(item.id) || isAdmin,
            )
            .map(({ id, icon: Icon, title }) => (
              <button
                key={id}
                title={title}
                aria-label={title}
                disabled={switching}
                className={visibleTab === id ? "active" : ""}
                onClick={() => startTransition(() => setTab(id))}
              >
                <Icon size={22} />
              </button>
            ))}
        </div>
        <button
          className="rail-bottom"
          title="退出登录"
          aria-label="退出登录"
          onClick={logout}
          disabled={switching}
        >
          <LogOut size={20} />
        </button>
        <button className="avatar reviewer" aria-label="账号安全" title="账号安全"
          onClick={() => setAccountOpen(true)}>{session.user[0].toUpperCase()}</button>
      </aside>
      <div
        className={`workspace ${visibleTab === "inbox" ? "inbox-workspace" : ""}`}
      >
        <header className="topbar">
          <div className="brand">
            知伴 <span>客服副驾驶</span>
          </div>
          <div className="top-right">
            <label className="shop-selector">
              <ShopAvatar shop={shop} />
              <span>店铺</span>
              <select
                aria-label="当前店铺"
                className="shop-switcher"
                value={session.active_shop_id}
                disabled={busy || switching}
                onChange={(e) => {
                  setTab("inbox");
                  void switchShop(e.target.value);
                }}
              >
                {session.shops.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </select>
            </label>
            {isAdmin && (
              <button
                className="manage-shops"
                disabled={busy || switching}
                onClick={() => startTransition(() => setTab("workspace"))}
              >
                <Store size={16} />
                管理店铺
              </button>
            )}
            <div className="topbar-status">
              <span
                className={`connection ${online ? "good" : "bad"}`}
                data-testid="connection"
                title={
                  shop?.connection_error_code
                    ? connectionErrorMessage(shop.connection_error_code)
                    : undefined
                }
              >
                <i />
                {switching
                  ? "切换中"
                  : label(shop?.connection_state || "disconnected")}
              </span>
              <span className="ai-status" data-testid="ai-service-status">
                {session.ai?.enabled ? "AI 已启用" : "AI 未启用"}
              </span>
            </div>
          </div>
        </header>
        <div className="notification-stack">
          {error && (
            <div role="alert" className="notification error">
              <AlertTriangle size={17} />
              {error}
              <button aria-label="关闭错误" onClick={clearError}>
                <X size={16} />
              </button>
            </div>
          )}
          {notice && (
            <div role="status" className="notification success">
              <Check size={17} />
              {notice}
            </div>
          )}
        </div>
        {switching ? (
          <div className="shop-loading" role="status">
            正在切换店铺…
          </div>
        ) : (
          <Suspense
            fallback={
              <div className="empty" role="status">
                正在加载…
              </div>
            }
            key={session.active_shop_id}
          >
            {visibleTab === "inbox" ? (
              <>
                <div className="inbox-layout">
                  <ConversationList
                    items={session.conversations}
                    selected={selected}
                    onSelect={selectConversation}
                  />
                  {detail && detail.id === selected ? (
                    <>
                      <ConversationView
                        onAtLatestChange={setReadingLatest}
                        key={`chat-${selected}`}
                        detail={detail}
                        action={action}
                        busy={busy}
                        online={online}
                      />
                      <DraftPanel
                        key={`draft-${selected}`}
                        detail={detail}
                        online={online}
                        busy={busy}
                        action={action}
                        notify={notify}
                        sendEnabled={detail.send_enabled === true}
                      />
                    </>
                  ) : selected ? (
                    <>
                      <section
                        className="chat conversation-placeholder"
                        aria-busy="true"
                        aria-label="正在读取会话"
                      >
                        <div className="chat-header">正在读取会话…</div>
                        <div className="chat-scroll" />
                      </section>
                      <aside className="draft-panel conversation-placeholder">
                        <div className="draft-heading">回复副驾驶</div>
                      </aside>
                    </>
                  ) : (
                    <div className="empty welcome">
                      <div className="empty-art">
                        <MessageSquare size={42} />
                        <Sparkles size={22} />
                      </div>
                      <h2>等待买家咨询</h2>
                      <p>收到新的平台消息后，会话会自动出现在左侧。</p>
                    </div>
                  )}
                </div>
              </>
            ) : visibleTab === "orders" ? (
              <OrdersPage shop={shop} />
            ) : visibleTab === "knowledge" ? (
              <Knowledge shop={shop} action={action} canTransfer={!!isAdmin} username={session.user} />
            ) : visibleTab === "settings" && isAdmin ? (
              <ModelSettings action={action} busy={busy} />
            ) : visibleTab === "workspace" && isAdmin ? (
              <Workspace
                shop={shop}
                action={action}
                busy={busy}
                onSwitch={async (id) => {
                  setTab("workspace");
                  await switchShop(id);
                }}
              />
            ) : (
              <Audit />
            )}
          </Suspense>
        )}
      </div>
    </div>
  );
}
