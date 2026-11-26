import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../api";
import { errorMessage } from "../errors";
import type { Product, ProductSnapshot, ProductSync } from "../types";
import PlatformImage from "./PlatformImage";
import ProductDetails from "./ProductDetails";

export default function ProductCatalog({
  shopId,
  revision,
  onSyncStateChange,
}: {
  shopId: string;
  revision: number;
  onSyncStateChange: (running: boolean) => void;
}) {
  const [products, setProducts] = useState<Product[]>([]);
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [error, setError] = useState("");
  const [updated, setUpdated] = useState<number | null>(null);
  const [category, setCategory] = useState("");
  const [sync, setSync] = useState<ProductSync>({
    state: "idle",
    total: 0,
    completed: 0,
    failed: 0,
    skipped: 0,
  });
  const [selected, setSelected] = useState<Product | null>(null);
  const closeDetails = useCallback(() => setSelected(null), []);
  const refreshAfterDetail = useCallback(() => {
    void refreshCatalog.current();
  }, []);
  const refreshCatalog = useRef<() => Promise<void>>(async () => {});
  useEffect(() => {
    let active = true;
    const load = async () => {
      await api<ProductSnapshot[]>(`/shops/${shopId}/products`)
        .then((rows) => {
          if (!active) return;
          setProducts(rows[0] ? JSON.parse(rows[0].products) : []);
          setUpdated(rows[0]?.checked_at || rows[0]?.created_at || null);
          setError("");
        })
        .catch((e) => {
          if (active) setError(errorMessage(e));
        });
    };
    refreshCatalog.current = load;
    setPage(0);
    void load();
    return () => {
      active = false;
    };
  }, [shopId, revision]);
  useEffect(() => {
    let active = true;
    async function refreshVisibleCatalog() {
      if (!active || document.visibilityState !== "visible") return;
      await refreshCatalog.current();
    }
    const timer = setInterval(refreshVisibleCatalog, 30000);
    document.addEventListener("visibilitychange", refreshVisibleCatalog);
    return () => {
      active = false;
      clearInterval(timer);
      document.removeEventListener("visibilitychange", refreshVisibleCatalog);
    };
  }, [shopId]);
  useEffect(() => {
    let active = true,
      inFlight = false,
      seen = "";
    async function poll() {
      if (inFlight || document.visibilityState !== "visible") return;
      inFlight = true;
      try {
        const state = await api<ProductSync>(
          `/shops/${shopId}/products/metadata-syncs`,
        );
        if (!active) return;
        setSync(state);
        onSyncStateChange(state.state === "running");
        const signature = `${state.id}:${state.completed}:${state.skipped}:${state.failed}:${state.updated_at}`;
        if (
          signature !== seen &&
          (state.completed > 0 || (state.skipped || 0) > 0)
        )
          await refreshCatalog.current();
        seen = signature;
      } catch (e) {
        if (active) setError(errorMessage(e));
      } finally {
        inFlight = false;
      }
    }
    void poll();
    const timer = setInterval(poll, 2500);
    document.addEventListener("visibilitychange", poll);
    return () => {
      active = false;
      clearInterval(timer);
      document.removeEventListener("visibilitychange", poll);
    };
  }, [shopId, onSyncStateChange]);
  const matches = products.filter(
    (p) =>
      (p.goods_name.includes(query) ||
        String(p.goods_id).includes(query) ||
        p.category_path?.join(" / ").includes(query)) &&
      (!category ||
        (category === "__missing__"
          ? !p.category_path?.length
          : p.category_path?.join(" / ") === category)),
  );
  const categories = Array.from(
    new Set(
      products
        .filter((p) => p.category_path?.length)
        .map((p) => p.category_path!.join(" / ")),
    ),
  ).sort((a, b) => a.localeCompare(b, "zh-CN"));
  const pages = Math.max(1, Math.ceil(matches.length / 12));
  const currentPage = Math.min(page, pages - 1);
  return (
    <section className="card product-catalog" aria-label="平台商品资料">
      <div className="card-heading">
        <div>
          <h2>平台商品</h2>
          <small>
            {products.length} 件 ·{" "}
            {updated
              ? `列表核对于 ${new Date(updated).toLocaleString("zh-CN")}`
              : "尚未同步"}
          </small>
        </div>
        <input
          aria-label="搜索平台商品"
          placeholder="搜索商品名称或编号"
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setPage(0);
          }}
        />
      </div>
      <div className="catalog-controls">
        <label>
          商品分类
          <select
            aria-label="筛选商品分类"
            value={category}
            onChange={(e) => {
              setCategory(e.target.value);
              setPage(0);
            }}
          >
            <option value="">全部分类</option>
            <option value="__missing__">未取得分类</option>
            {categories.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </label>
      </div>
      {sync.state !== "idle" && (
        <div
          className={`catalog-sync-progress ${sync.failed ? "warning" : ""}`}
        >
          <p>
            {sync.state === "running"
              ? "商品同步中"
              : sync.failed
                ? "部分商品详情未同步，可重新同步"
                : "商品已同步"}
            <span>
              {sync.completed + sync.failed + (sync.skipped || 0)} /{" "}
              {sync.total}
              {` · 更新 ${sync.completed} 件 · 复用 ${sync.skipped || 0} 件`}
              {sync.failed ? ` · ${sync.failed} 件失败` : ""}
            </span>
          </p>
          {sync.updated_at && (
            <small>
              更新于 {new Date(sync.updated_at).toLocaleString("zh-CN")}
            </small>
          )}
          <progress
            aria-label="商品详情同步进度"
            value={sync.completed + sync.failed + (sync.skipped || 0)}
            max={Math.max(sync.total, 1)}
          />
        </div>
      )}
      {error && <p role="alert">{error}</p>}
      {!products.length && (
        <p className="subtle-text">点击「同步商品」获取平台图片与资料。</p>
      )}
      <div className="product-grid">
        {matches.slice(currentPage * 12, (currentPage + 1) * 12).map((p) => (
          <article key={p.goods_id} className="catalog-product">
            <PlatformImage src={p.thumb_url} alt={p.goods_name} />
            <strong title={p.goods_name}>{p.goods_name}</strong>
            <small
              className="product-category"
              title={p.category_path?.join(" / ")}
            >
              {p.category_path?.length
                ? p.category_path.join(" / ")
                : p.detail_updated_at
                  ? "平台未返回分类"
                  : "分类待同步"}
            </small>
            <span>
              {p.price == null ? "价格未知" : `¥ ${p.price}`} ·{" "}
              {p.quantity == null ? "库存未知" : `库存 ${p.quantity}`}
            </span>
            {p.sku_count != null && (
              <small>
                {p.sku_count} 项规格 · {p.attributes_count || 0} 项参数
              </small>
            )}
            <button
              className="secondary product-detail-button"
              disabled={!p.detail_updated_at}
              onClick={() => setSelected(p)}
            >
              {p.detail_updated_at ? "查看详情" : "待同步详情"}
            </button>
          </article>
        ))}
      </div>
      {!!matches.length && (
        <div className="catalog-pagination">
          <button
            className="secondary"
            disabled={currentPage === 0}
            onClick={() => setPage(currentPage - 1)}
          >
            上一页
          </button>
          <span>
            {currentPage + 1} / {pages}
          </span>
          <button
            className="secondary"
            disabled={currentPage + 1 >= pages}
            onClick={() => setPage(currentPage + 1)}
          >
            下一页
          </button>
        </div>
      )}
      {selected && (
        <ProductDetails
          shopId={shopId}
          product={selected}
          onClose={closeDetails}
          onUpdated={refreshAfterDetail}
        />
      )}
    </section>
  );
}
