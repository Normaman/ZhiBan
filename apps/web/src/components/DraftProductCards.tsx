import { useState } from "react";
import { api } from "../api";
import PlatformImage from "./PlatformImage";
import type { Product, ProductCard, ReplyDraft, PerformAction } from "../types";

export default function DraftProductCards({
  draft,
  content,
  selected,
  onSelect,
  editable,
  busy,
  action,
}: {
  content: string;
  draft: ReplyDraft;
  selected: string[];
  onSelect: (ids: string[]) => void;
  editable: boolean;
  busy: boolean;
  action: PerformAction;
}) {
  const [choosing, setChoosing] = useState(false);
  const [products, setProducts] = useState<Product[]>([]);
  const [query, setQuery] = useState("");
  const [replacement, setReplacement] = useState<string[]>([]);
  const cards: ProductCard[] = draft.product_cards || [];
  if (draft.product_cards === undefined || (!editable && !selected.length))
    return null;
  async function choose() {
    await action(async () => {
      const rows = await api<Product[]>(
        `/reply-drafts/${draft.id}/product-candidates`,
      );
      setProducts(rows);
      setReplacement(selected);
      setChoosing(true);
    });
  }
  return (
    <section className="draft-products" data-testid="draft-product-cards">
      <div className="draft-products-heading">
        <strong>
          商品卡片{cards.length ? ` · ${selected.length} 张` : ""}
        </strong>
        {editable && (
          <button className="text-button" disabled={busy} onClick={choose}>
            {cards.length ? "更换商品" : "添加商品"}
          </button>
        )}
      </div>
      {cards
        .filter((card) => editable || selected.includes(card.goods_id))
        .map((card) => (
          <div
            className={`draft-product ${selected.includes(card.goods_id) ? "" : "unselected"}`}
            key={card.goods_id}
          >
            <PlatformImage src={card.image_url} alt={card.title} />
            <div>
              <strong>{card.title}</strong>
              {card.price && <span>拼单价 ¥{card.price}</span>}
            </div>
            {editable && (
              <input
                type="checkbox"
                aria-label={`发送商品卡片：${card.title}`}
                checked={selected.includes(card.goods_id)}
                disabled={busy}
                onChange={(e) =>
                  onSelect(
                    cards
                      .filter((candidate) =>
                        candidate.goods_id === card.goods_id
                          ? e.target.checked
                          : selected.includes(candidate.goods_id),
                      )
                      .map((candidate) => candidate.goods_id),
                  )
                }
              />
            )}
          </div>
        ))}
      {!!cards.length && <small>图片与价格以买家收到的平台卡片为准</small>}
      {choosing && (
        <div
          className="draft-product-chooser"
          data-testid="product-card-chooser"
        >
          <input
            aria-label="搜索卡片商品"
            placeholder="搜索商品名称"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <div className="draft-product-options">
            {!products.length && <p>暂无商品，请先在知识库同步商品。</p>}
            {!!products.length &&
              !products.some((p) => p.goods_name.includes(query)) && (
                <p>未找到匹配商品。</p>
              )}
            {products
              .filter((p) => p.goods_name.includes(query))
              .map((p) => (
                <label key={p.goods_id}>
                  <input
                    type="checkbox"
                    checked={replacement.includes(String(p.goods_id))}
                    disabled={
                      busy ||
                      (!replacement.includes(String(p.goods_id)) &&
                        replacement.length >= 3)
                    }
                    onChange={(e) =>
                      setReplacement(
                        e.target.checked
                          ? [...replacement, String(p.goods_id)]
                          : replacement.filter(
                              (id) => id !== String(p.goods_id),
                            ),
                      )
                    }
                  />
                  <span>{p.goods_name}</span>
                </label>
              ))}
          </div>
          <div className="secondary-actions">
            <button
              className="primary"
              disabled={busy}
              onClick={() =>
                action(async () => {
                  await api(`/reply-drafts/${draft.id}/product-cards`, {
                    revision: draft.revision,
                    product_ids: replacement,
                    content,
                  });
                  setChoosing(false);
                }, "商品卡片已更新，请核对回复文字后发送。")
              }
            >
              应用 {replacement.length} 张
            </button>
            <button disabled={busy} onClick={() => setChoosing(false)}>
              取消
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
