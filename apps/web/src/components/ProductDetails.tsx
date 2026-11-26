import { useEffect, useRef, useState } from "react";
import { X } from "lucide-react";
import { api } from "../api";
import { errorMessage } from "../errors";
import type { Product, ProductDetail } from "../types";
import PlatformImage from "./PlatformImage";

export default function ProductDetails({
  shopId,
  product,
  onClose,
  onUpdated,
}: {
  shopId: string;
  product: Product;
  onClose: () => void;
  onUpdated: () => void;
}) {
  const [detail, setDetail] = useState<ProductDetail | null>(null),
    [loading, setLoading] = useState(true),
    [error, setError] = useState("");
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    panel.current?.querySelector<HTMLButtonElement>("button")?.focus();
    const keydown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
      }
      if (e.key === "Tab") {
        const controls = Array.from(
          panel.current?.querySelectorAll<HTMLElement>(
            'button:not(:disabled),a[href],input,select,textarea,[tabindex="0"]',
          ) || [],
        );
        const first = controls[0],
          last = controls[controls.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last?.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first?.focus();
        }
      }
    };
    document.addEventListener("keydown", keydown);
    return () => {
      document.body.style.overflow = overflow;
      document.removeEventListener("keydown", keydown);
      previous?.focus();
    };
  }, [onClose]);
  useEffect(() => {
    let active = true;
    api<{ detail: ProductDetail | null }>(
      `/shops/${shopId}/products/${encodeURIComponent(String(product.goods_id))}/details`,
    )
      .then((value) => {
        if (active) {
          setDetail(value.detail);
          onUpdated();
        }
      })
      .catch((e) => {
        if (active) setError(errorMessage(e));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [shopId, product.goods_id, onUpdated]);
  return (
    <div
      className="product-detail-overlay"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        ref={panel}
        className="product-detail-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="product-detail-heading"
      >
        <header>
          <div>
            <h2 id="product-detail-heading">商品资料</h2>
            <p>{product.goods_name}</p>
          </div>
          <button
            className="detail-close"
            aria-label="关闭商品资料"
            onClick={onClose}
          >
            <X size={20} />
          </button>
        </header>
        <div className="product-detail-body">
          {loading ? (
            <p role="status">正在读取商品资料…</p>
          ) : error ? (
            <p role="alert">{error}</p>
          ) : !detail ? (
            <p>
              此商品尚未补齐详情。关闭此窗口，点击「同步分类与规格」，完成后即可查看。
            </p>
          ) : (
            <>
              <dl className="product-facts">
                <div>
                  <dt>商品分类</dt>
                  <dd>
                    {detail.category_path.length
                      ? detail.category_path.join(" / ")
                      : "平台未返回分类"}
                  </dd>
                </div>
                {detail.goods_type && (
                  <div>
                    <dt>商品类型</dt>
                    <dd>{detail.goods_type}</dd>
                  </div>
                )}
                <div>
                  <dt>同步时间</dt>
                  <dd>{new Date(detail.fetched_at).toLocaleString("zh-CN")}</dd>
                </div>
              </dl>
              {!!detail.images.length && (
                <div className="product-detail-images">
                  {detail.images.map((src, i) => (
                    <PlatformImage
                      key={src}
                      src={src}
                      alt={`商品主图 ${i + 1}`}
                    />
                  ))}
                </div>
              )}
              <section>
                <h3>发货与运费</h3>
                <p>{detail.shipping?.summary || "发货设置尚未同步"}</p>
                {detail.freight?.status === "synced" ? (
                  <div className="product-shipping">
                    <details>
                      <summary>
                        包邮地区 · {detail.freight.free_regions.length}
                      </summary>
                      <p>{detail.freight.free_regions.join("、") || "无"}</p>
                    </details>
                    <details>
                      <summary>
                        需付运费地区 · {detail.freight.paid_rules.length} 组
                      </summary>
                      {detail.freight.paid_rules.map((r, i) => (
                        <p key={i}>
                          {r.regions.join("、")}：
                          {r.unit === "piece"
                            ? `${r.first_units}件`
                            : `${r.first_units / 1000}kg`}
                          内 ¥{(r.first_fee_cents / 100).toFixed(2)}，每增加
                          {r.unit === "piece"
                            ? `${r.additional_units}件`
                            : `${r.additional_units / 1000}kg`}
                          加 ¥{(r.additional_fee_cents / 100).toFixed(2)}
                          {r.free_min_count
                            ? `；满${r.free_min_count}件包邮`
                            : ""}
                          {r.free_min_amount_cents
                            ? `；满${(r.free_min_amount_cents / 100).toFixed(2)}元包邮`
                            : ""}
                        </p>
                      ))}
                      {!detail.freight.paid_rules.length && <p>无</p>}
                    </details>
                    <details>
                      <summary>
                        不配送地区 ·{" "}
                        {detail.freight.undeliverable_regions.length}
                      </summary>
                      <p>
                        {detail.freight.undeliverable_regions.join("、") ||
                          "无"}
                      </p>
                    </details>
                  </div>
                ) : (
                  <p className="subtle-text">
                    {detail.freight?.status === "unsupported"
                      ? "地区运费规则暂未解析，请人工核对"
                      : "运费规则尚未取得"}
                  </p>
                )}
              </section>
              <section>
                <h3>商品参数</h3>
                {detail.attributes.length ? (
                  <dl className="product-facts">
                    {detail.attributes.map((a, i) => (
                      <div key={i}>
                        <dt>{a.name}</dt>
                        <dd>{a.values.join("、") || "未填写"}</dd>
                      </div>
                    ))}
                  </dl>
                ) : (
                  <p className="subtle-text">
                    {detail.attributes_status === "synced"
                      ? "平台未返回更多商品参数。"
                      : "商品参数暂未取到，可重新同步。"}
                  </p>
                )}
              </section>
              <section>
                <h3>规格 · {detail.sku_count} 项</h3>
                <p className="subtle-text">
                  同步快照，不含优惠及运费；回复前实时查询。
                </p>
                {detail.skus.length ? (
                  <div className="sku-table-scroll">
                    <table className="sku-table">
                      <thead>
                        <tr>
                          <th>规格</th>
                          <th>价格快照</th>
                          <th>库存快照</th>
                          <th>发货设置</th>
                        </tr>
                      </thead>
                      <tbody>
                        {detail.skus.map((sku, i) => (
                          <tr key={sku.sku_id || i}>
                            <td>
                              {sku.specs
                                .map((s) => `${s.name}：${s.value}`)
                                .join("；") || "平台未填写规格名称"}
                            </td>
                            <td>
                              <div>
                                拼单：
                                {sku.group_price_cents != null
                                  ? `¥${(sku.group_price_cents / 100).toFixed(2)}`
                                  : "未知"}
                              </div>
                              <div>
                                单买：
                                {sku.single_price_cents != null
                                  ? `¥${(sku.single_price_cents / 100).toFixed(2)}`
                                  : "未知"}
                              </div>
                              {sku.on_sale === false && <small>已下架</small>}
                            </td>
                            <td>
                              {sku.quantity == null ? "未知" : sku.quantity}
                            </td>
                            <td>
                              {sku.presale_deadline
                                ? `预售至 ${new Date(sku.presale_deadline).toLocaleString("zh-CN")}`
                                : sku.shipment_limit_seconds?.length
                                  ? sku.shipment_limit_seconds
                                      .map((v) =>
                                        v === 1
                                          ? "当日发货"
                                          : v === 2
                                            ? "需核对规格"
                                            : v <= 172800
                                              ? `${v / 3600}小时内发货`
                                              : `${v / 86400}天内发货`,
                                      )
                                      .join("、")
                                  : detail.shipping?.mode === "by_sku"
                                    ? "待核对规格"
                                    : "按商品设置"}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <p className="subtle-text">平台未返回规格信息。</p>
                )}
                {detail.skus_truncated && (
                  <p className="subtle-text">规格较多，此处保留前 500 项。</p>
                )}
              </section>
              {detail.description && (
                <section>
                  <h3>商品说明</h3>
                  <p className="product-description">{detail.description}</p>
                </section>
              )}
              {!!detail.detail_images.length && (
                <section>
                  <h3>详情图片</h3>
                  <div className="product-detail-images">
                    {detail.detail_images.map((src, i) => (
                      <PlatformImage
                        key={src}
                        src={src}
                        alt={`详情图片 ${i + 1}`}
                      />
                    ))}
                  </div>
                </section>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
