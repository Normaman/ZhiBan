export const knowledgeTopics: Record<string, string> = {
  shipping: "发货规则",
  policy: "售后规则",
  spec: "商品参数与使用说明",
  recommendation: "商品选购说明",
  faq: "常见问答",
  response_guidelines: "客服回答规范",
};

export const isProductTopic = (topic: string) =>
  topic === "spec" || topic === "recommendation";
