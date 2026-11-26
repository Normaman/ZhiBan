import type { AgentRun } from "../types";
import { orderQueryNotice } from "../messages";

const intents: Record<string, string> = {
  inventory: "库存咨询",
  product: "商品咨询",
  price: "价格咨询",
  shipping: "发货咨询",
  shipping_origin: "发货地咨询",
  logistics: "物流咨询",
  after_sales_policy: "售后规则",
  after_sales_action: "售后操作诉求",
  social: "礼貌回应",
  unknown: "待澄清",
};

const risks: Record<string, string> = {
  direct_refund: "直接退款诉求",
  compensation: "补偿诉求",
  quality_dispute: "质量争议",
  escalation: "投诉或评价争议",
  aggression: "激烈情绪",
  address_change: "修改地址操作",
  configured_handoff: "命中店铺人工处理要求",
};

export default function IntentDetails({ run }: { run?: AgentRun }) {
  if (!run?.understanding) return null;
  const { understanding, order_context: order } = run;
  if (!understanding.intents?.length && !understanding.risk_flags?.length && !order?.order_id) return null;
  return (
    <details data-testid="question-understanding">
      <summary>
        诉求识别{understanding.order_verified ? " · 已关联订单" : ""}
      </summary>
      {!!understanding.intents?.length && (
        <p>{understanding.intents.map((code) => intents[code] || "待核实").join("、")}</p>
      )}
      {!!understanding.risk_flags?.length && (
        <p>需人工处理：{understanding.risk_flags.map((code) => risks[code] || "风险待核实").join("、")}</p>
      )}
      {understanding.product_scope === "catalog" && (
        <p>查询店铺商品：{understanding.product_keywords?.join("、")}</p>
      )}
      {order?.order_id && (
        <>
          <p>订单尾号 {order.order_id.slice(-4)} · {order.goods_name}</p>
          <p>{order.spec ? `${order.spec} · ` : ""}购买 {order.purchased_quantity ?? "待核实"} 件</p>
          <p>订单状态：{order.order_status && order.order_status !== "unknown" ? order.status_text : "待核实"}</p>
          {order.status_error_code && (
            <p>
              {orderQueryNotice(order.status_error_code)}
            </p>
          )}
        </>
      )}
    </details>
  );
}
