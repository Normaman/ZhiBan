import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import { errorMessage } from "../errors";
import { knowledgeTopics } from "../knowledge";
import type { KnowledgeEntry, Product, Shop } from "../types";

interface Result {
  knowledge_version: string; requested_mode: string; scope_count: number; duration_ms: number;
  reason: string | null; retrieval: { mode: string; reranked?: number; reason?: string; rerank_scores?: Record<string,number> };
  hits: (KnowledgeEntry & { rank: number; source_version: string })[];
}
const modeName: Record<string,string> = {bm25:"关键词",hybrid:"语义＋关键词＋重排",bm25_degraded:"语义不可用，已降级关键词"};
export default function KnowledgeSearch({ shop,products }: { shop: Shop; products: Product[] }) {
  const [query,setQuery]=useState("");const [topic,setTopic]=useState("");const [goods,setGoods]=useState("");
  const [mode,setMode]=useState("current");const [result,setResult]=useState<Result | null>(null);
  const [error,setError]=useState("");const [busy,setBusy]=useState(false);const epoch=useRef(0);
  useEffect(() => {epoch.current++;setResult(null);setError("");setBusy(false);setGoods("");return () => {epoch.current++;};},[shop.id,shop.knowledge_version]);
  return <details className="card knowledge-retrieval-debug">
    <summary>检索调试</summary>
    <form onSubmit={async event => {
      event.preventDefault();const current=++epoch.current;setBusy(true);setError("");setResult(null);
      try { const value=await api<Result>(`/shops/${shop.id}/knowledge/search`,{
        query:query.trim(),mode,topic:topic || null,goods_id:goods || null,
      });if (current===epoch.current) setResult(value); }
      catch (cause) {if (current===epoch.current) setError(errorMessage(cause));}
      finally {if (current===epoch.current) setBusy(false);}
    }}>
      <label>买家问题<input value={query} onChange={event => setQuery(event.target.value)} maxLength={1000} required /></label>
      <div className="knowledge-search-controls">
        <label>资料分类<select value={topic} onChange={event => setTopic(event.target.value)}><option value="">全部分类</option>{Object.entries(knowledgeTopics).map(([key,name]) => <option key={key} value={key}>{name}</option>)}</select></label>
        <label>商品范围<select value={goods} onChange={event => setGoods(event.target.value)}><option value="">全部商品及店铺规则</option>{products.map(product => <option key={product.goods_id} value={String(product.goods_id)}>{product.goods_name}</option>)}</select></label>
        <label>检索方式<select value={mode} onChange={event => setMode(event.target.value)}><option value="current">当前配置</option><option value="bm25">仅关键词</option><option value="hybrid">混合检索</option></select></label>
        <button className="primary" disabled={busy || !query.trim()}>{busy?"检索中…":"查询依据"}</button>
      </div>
    </form>
    {error && <p role="alert" className="callout warning">{error}</p>}
    {result && <section>
      <p>{modeName[result.retrieval.mode] || "状态未知"} · 范围 {result.scope_count} 条 · 命中 {result.hits.length} 条 · {result.duration_ms} ms</p>
      <small>知识版本：{result.knowledge_version || "尚未发布"}</small>
      {result.retrieval.mode==="bm25_degraded" && <p className="callout warning">混合检索未完成，本次只使用关键词结果。</p>}
      {!result.hits.length && <p>{result.reason==="NO_ENTRIES_IN_SCOPE"?"当前范围没有已发布且在有效期内的资料。":"当前问题未命中资料，请核对商品范围、分类或补充知识。"}</p>}
      {topic==="response_guidelines" && <p>回答规范在生成时直接加载，不依赖检索命中。</p>}
      {result.hits.map(hit => <details key={hit.source_id} className="knowledge-search-hit">
        <summary>{hit.rank}. {hit.title || hit.source_id}</summary><p className="knowledge-diff-text">{hit.text}</p>
        <small>{hit.document_name || "手动资料"} · {hit.source_location || hit.source_id}</small>
      </details>)}
    </section>}
  </details>;
}
