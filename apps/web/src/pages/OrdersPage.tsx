import { useState } from "react";
import {
  AlertCircle,
  Clock3,
  CreditCard,
  Package,
  RefreshCw,
  RotateCcw,
  Truck,
} from "lucide-react";
import { useOrderView } from "../hooks/useOrderView";
import type { Shop } from "../types";

interface OrderOverview {
  shop_id: string;
  fetched_at: string;
  counts: { unshipped: number; shipped: number; waitPay: number; refunding: number };
}

interface OrderRow {
  order_ref: string | null;
  goods_name: string | null;
  spec: string | null;
  quantity: number | null;
  status_text: string | null;
  order_time: string | null;
  amount: string | null;
  thumbnail: string | null;
}

interface OrderList {
  total: number;
  orders: OrderRow[];
  page: number;
  page_size: number;
  fetched_at: string;
}

const countCards = [
  { key: "unshipped", label: "待发货", icon: Package },
  { key: "shipped", label: "已发货", icon: Truck },
  { key: "waitPay", label: "待付款", icon: CreditCard },
  { key: "refunding", label: "退款中", icon: RotateCcw },
] as const;

const filters = [
  { value: 1, label: "待发货" },
  { value: 2, label: "已发货" },
  { value: 7, label: "待付款" },
] as const;

export default function OrdersPage({ shop }: { shop?: Shop }) {
  const [orderType, setOrderType] = useState(2);
  const [page, setPage] = useState(1);
  const base = shop?.id ? `/shops/${encodeURIComponent(shop.id)}/orders` : null;
  const overviewView = useOrderView<OrderOverview>(base ? `${base}/overview` : null);
  const listView = useOrderView<OrderList>(base ? `${base}?orderType=${orderType}&page=${page}` : null);
  const overview = overviewView.data;
  const list = listView.data;
  const loading = overviewView.loading || listView.loading;
  const refresh = () => { listView.refresh(); overviewView.refresh(); };
  const formatTime = (time?: string) => time
    ? new Date(time).toLocaleString("zh-CN", {
        month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit",
      })
    : "尚未更新";
  const pages = Math.max(1, Math.ceil((list?.total ?? 0) / (list?.page_size ?? 20)));

  return (
    <main className="orders-page">
      <header className="page-heading orders-heading">
        <div>
          <h1>订单与售后</h1>
          <p>{shop?.name || "当前店铺"} · 只读查看拼多多后台状态</p>
        </div>
        <div className="orders-heading-actions">
          <span className="orders-updated"><Clock3 size={14} />订单更新于 {formatTime(list?.fetched_at)}</span>
          <button className="secondary-button" onClick={refresh} disabled={loading}>
            <RefreshCw size={16} className={loading ? "orders-spin" : ""} />{loading ? "更新中" : "刷新"}
          </button>
        </div>
      </header>

      {listView.error && (
        <div className="orders-error" role="alert">
          <AlertCircle size={17} /><span>{listView.error}</span>
          <button onClick={listView.refresh} disabled={listView.loading}>重试</button>
        </div>
      )}
      {overviewView.error && (
        <div className="orders-error" role="alert">
          <AlertCircle size={17} /><span>统计更新失败：{overviewView.error}</span>
          <button onClick={overviewView.refresh} disabled={overviewView.loading}>重试</button>
        </div>
      )}

      <div className="orders-stat-time">统计更新于 {formatTime(overview?.fetched_at)}</div>
      <section className="order-count-grid" aria-label="订单状态数量">
        {countCards.map(({ key, label, icon: Icon }) => (
          <article className="order-count-card" key={key}>
            <div className="order-count-icon"><Icon size={18} /></div>
            <div className="order-count-copy">
              <span>{label}</span><strong>{overview ? overview.counts[key] : overviewView.loading ? "…" : "—"}</strong>
            </div>
          </article>
        ))}
      </section>

      <section className="order-list-card">
        <header className="order-list-heading">
          <div><h2>订单明细</h2><p>订单号仅显示末四位；不展示买家联系方式和收货地址</p></div>
          <nav className="order-filters" aria-label="订单状态筛选">
            {filters.map((filter) => (
              <button key={filter.value} className={orderType === filter.value ? "active" : ""}
                onClick={() => { setOrderType(filter.value); setPage(1); }}>
                {filter.label}
              </button>
            ))}
          </nav>
        </header>

        {listView.loading && !list ? (
          <div className="order-list-empty">正在读取订单…</div>
        ) : list?.orders.length ? (
          <div className="order-rows">
            {list.orders.map((order, index) => (
              <article className="order-row" key={`${order.order_ref ?? "order"}-${index}`}>
                {order.thumbnail ? <img src={order.thumbnail} alt="" loading="lazy" />
                  : <div className="order-row-placeholder"><Package size={20} /></div>}
                <div className="order-row-main">
                  <strong>{order.goods_name || "商品信息暂缺"}</strong>
                  <span>{order.spec || "默认规格"} · ×{order.quantity ?? "—"}</span>
                  <small>订单 {order.order_ref || "—"} · {order.order_time ? new Date(order.order_time).toLocaleString("zh-CN") : "时间未知"}</small>
                </div>
                <div className="order-row-side">
                  <strong>{order.status_text || "状态未知"}</strong>
                  <span>{order.amount ? `¥${order.amount}` : "金额未知"}</span>
                </div>
              </article>
            ))}
          </div>
        ) : (
          <div className="order-list-empty">{!list ? "订单尚未读取" : "当前筛选下没有订单"}</div>
        )}

        <footer className="order-list-footer">
          <span>{list ? `共 ${list.total} 条` : "尚无列表数据"} · 数据由拼多多商家后台读取</span>
          <div>
            <button disabled={page <= 1 || listView.loading} onClick={() => setPage((current) => current - 1)}>上一页</button>
            <span>{page} / {pages}</span>
            <button disabled={page >= pages || listView.loading} onClick={() => setPage((current) => current + 1)}>下一页</button>
          </div>
        </footer>
      </section>
    </main>
  );
}
