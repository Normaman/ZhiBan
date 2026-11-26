import React from "react";
import { createRoot } from "react-dom/client";
import OrderMessage from "../src/components/OrderMessage";
import "../src/styles.css";

createRoot(document.getElementById("root")!).render(
  <main style={{ padding: 16, width: "100%", maxWidth: 500 }}>
    <OrderMessage
      buyerRef="synthetic-buyer"
      content={JSON.stringify({ order_id: "123456-123456789", goods_name: "合成订单商品" })}
      orderStatus={{ order_id: "123456-123456789", order_status: "unknown", status_error_code: "ORDER_QUERY_VERIFICATION_REQUIRED" }}
    />
  </main>,
);
