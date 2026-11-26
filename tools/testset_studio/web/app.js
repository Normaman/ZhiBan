const $ = (selector) => document.querySelector(selector);
const state = { suite: "agent", suites: {}, token: "", selected: null, draft: null, dirty: false, advancedInvalid: false };
const editor = $("#json-editor");

function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char]);
}
async function request(url, options = {}) {
  const response = await fetch(url, { ...options, headers: { ...(options.body ? { "Content-Type": "application/json", "X-Studio-Token": state.token } : {}), ...options.headers } });
  if (!response.ok) { const body = await response.json().catch(() => ({})); throw new Error(body.detail || `请求失败 (${response.status})`); }
  return response.json();
}
function activeSuite() { return state.suites[state.suite]; }
function markDirty(value) {
  state.dirty = value;
  const badge = $("#dirty-state"); badge.textContent = value ? "有未保存修改" : "已保存"; badge.className = value ? "saved unsaved" : "saved";
}
function showMessage(text = "", type = "") { const box = $("#message"); box.textContent = text; box.className = `message ${type}`; }
function friendlyError(message) {
  const hints = [
    [/SCENARIO_SPLIT_LEAKAGE|场景不能同时出现在开发集和测试集/, "同一个测试场景不能同时放在日常调试和验收检查中，请统一它的用途。"],
    [/CONVERSATION_GROUP_SPLIT_LEAKAGE|资料组不能同时出现在开发集和测试集/, "同一组相关对话或资料需要放在相同用途里。"],
    [/EMPTY_OR_DUPLICATE_CASES|EMPTY_OR_DUPLICATE_KNOWLEDGE_CASES|测试集不能为空/, "测试集中至少保留一条用例，并确保每条用例内容不同。"],
    [/CUSTOM_FIXTURE_REQUIRES_EXPLICIT_INPUT/, "自定义测试需要补充可用商品或知识资料。"],
    [/UNKNOWN_BUSINESS_FIXTURE/, "当前用例的测试方式无法识别，请在高级设置中修正。"],
    [/DUPLICATE_DOCUMENT_VERSION/, "同一测试中不能添加编号和版本都相同的两份资料。"],
    [/GOLD_ENTRY_SOURCE_NOT_IN_INPUT|条目引用的资料不存在/, "请检查“应识别出的知识”是否选中了当前存在的资料。"],
    [/用例编号与请求不一致|用例编号不能为空/, "用例信息不完整，请刷新后重试。"],
  ];
  for (const [pattern, hint] of hints) if (pattern.test(message)) return hint;
  return message.replaceAll("expected_entries", "期望知识条目").replaceAll("source_id", "资料").replaceAll("version", "版本").replaceAll("scenario", "场景").replaceAll("split", "用途");
}
function getAt(obj, path) { return path.split(".").reduce((value, key) => value == null ? undefined : value[key], obj); }
function setAt(obj, path, value) {
  const keys = path.split("."); let current = obj;
  keys.slice(0, -1).forEach((key) => { if (current[key] == null) current[key] = /^\d+$/.test(keys[keys.indexOf(key) + 1]) ? [] : {}; current = current[key]; });
  current[keys.at(-1)] = value;
}
function friendlyPattern(value) {
  return String(value).replace(/\\s[*+?]?/g, " ").replace(/\\d/g, "数字").replace(/\\./g, ".").replace(/\s+/g, " ").trim();
}
function field(label, path, value, { type = "text", hint = "", options = null, rows = 0, kind = "string" } = {}) {
  const values = Array.isArray(value) ? value : [];
  const val = Array.isArray(value) ? values.map((item) => kind === "pattern-lines" ? friendlyPattern(item) : item).join(kind.endsWith("lines") ? "\n" : ", ") : kind === "currency-cents" && value != null && value !== "" ? Number(value) / 100 : value;
  const help = hint ? `<small>${esc(hint)}</small>` : "";
  if (options) return `<label class="form-field"><span>${esc(label)}</span><select data-path="${esc(path)}" data-kind="string">${options.map(([v, name]) => `<option value="${esc(v)}" ${String(v) === String(value) ? "selected" : ""}>${esc(name)}</option>`).join("")}</select>${help}</label>`;
  if (type === "checkbox") return `<label class="check-field"><input type="checkbox" data-path="${esc(path)}" data-kind="boolean" ${value ? "checked" : ""}><span>${esc(label)}</span></label>`;
  const control = rows ? `<textarea rows="${rows}" data-path="${esc(path)}" data-kind="${kind}">${esc(val)}</textarea>` : `<input type="${type}" ${kind === "currency-cents" ? 'step="0.01"' : ""} data-path="${esc(path)}" data-kind="${kind}" value="${esc(val)}">`;
  return `<label class="form-field"><span>${esc(label)}</span>${control}${help}</label>`;
}
function options(labels) { return Object.entries(labels).map(([value, label]) => [value, label]); }
function renderAgentForm(row) {
  const outcomes = ["draft", "needs_human", "insufficient_evidence"];
  const outcomeLabels = { draft: "应正常回答", needs_human: "应转人工处理", insufficient_evidence: "现有资料不足以回答" };
  const expected = row.expected || {};
  let html = `<section class="form-section"><h3>买家问题</h3>${field("用途", "split", row.split, { options: [["dev", "日常调试"], ["test", "验收检查"]] })}${field("买家怎么问", "question", row.question, { rows: 3, hint: "写一句真实买家可能发送的话" })}</section>`;
  html += `<section class="form-section"><h3>期望回答</h3><p class="section-intro">标记这条问题应该如何处理，并写下一个理想答案供评测对照。</p><div class="choice-grid">${outcomes.map((value) => `<label class="check-card"><input type="checkbox" data-choice-path="expected.outcomes" data-option="${value}" ${expected.outcomes?.includes(value) ? "checked" : ""}><span>${outcomeLabels[value]}</span></label>`).join("")}</div>${field("理想回答示例", "reference_answer", row.reference_answer || "", { rows: 3, hint: "简洁写出事实和要点，不需要写成提示词" })}<div class="form-grid">${field("回答里应该提到", "expected.contains", expected.contains || [], { rows: 2, hint: "每行写一个要点，例如：2 张；不需要写格式代码", kind: "pattern-lines" })}${field("回答里不应出现", "expected.forbids", expected.forbids || [], { rows: 2, hint: "每行写一种不合适的承诺或说法", kind: "pattern-lines" })}</div><div class="check-line">${field("这类问题必须交由人工处理", "expected.risk", expected.risk ?? false, { type: "checkbox" })}</div></section>`;
  if (row.fixture === "custom") {
    const input = row.input || { context: { buyer_ref: "test-buyer", knowledge: [], history: [], product_context: {}, platform_product_facts: {}, order_reference: {}, questions: [], question_contexts: {}, question_orders: {} }, products: [], product_details: {}, orders: [] };
    row.input = input; input.context ||= {}; input.context.knowledge ||= []; input.products ||= [];
    html += `<section class="form-section"><div class="section-heading"><div><h3>可用商品</h3><p>填写商品名称、库存和价格。多规格情况可添加资料，按规格写清价格和库存。</p></div><button type="button" class="small-button" data-action="add-product">添加商品</button></div>`;
    html += input.products.length ? input.products.map((product, index) => {
      const details = input.product_details?.[product.goods_id] || {}, skus = details.skus || [];
      return `<div class="data-card"><div class="data-card-title"><strong>${esc(product.goods_name || `商品 ${index + 1}`)}</strong><button type="button" class="text-danger" data-action="remove-product" data-index="${index}">移除商品</button></div><div class="form-grid">${field("商品名称", `input.products.${index}.goods_name`, product.goods_name || "")}${field("商品总库存", `input.products.${index}.quantity`, product.quantity ?? "", { type: "number" })}${field("商品价格", `input.products.${index}.price`, product.price || "")}</div><div class="sku-heading"><strong>不同规格的价格和库存（选填）</strong><button type="button" class="small-button" data-action="add-sku" data-product-index="${index}">添加规格</button></div>${skus.map((sku, skuIndex) => `<div class="sku-row"><div class="sku-row-head"><span>规格 ${skuIndex + 1}</span><button type="button" class="text-danger" data-action="remove-sku" data-product-index="${index}" data-sku-index="${skuIndex}">移除</button></div><div class="form-grid">${field("规格名称", `input.product_details.${product.goods_id}.skus.${skuIndex}.specs.0.value`, sku.specs?.[0]?.value || "")}${field("库存", `input.product_details.${product.goods_id}.skus.${skuIndex}.quantity`, sku.quantity ?? "", { type: "number" })}${field("拼单价（元）", `input.product_details.${product.goods_id}.skus.${skuIndex}.group_price_cents`, sku.group_price_cents ?? "", { type: "number", kind: "currency-cents" })}${field("单买价（元）", `input.product_details.${product.goods_id}.skus.${skuIndex}.single_price_cents`, sku.single_price_cents ?? "", { type: "number", kind: "currency-cents" })}</div></div>`).join("") || `<p class="sku-empty">如果商品有多个规格，可在这里分别填写价格和库存。</p>`}</div>`;
    }).join("") : `<div class="empty-data">无需商品资料时可以留空；库存、规格或价格问题建议添加商品。</div>`;
    html += `<div class="section-heading knowledge-heading"><div><h3>可用知识资料</h3><p>例如商品规格、发货时效、运费说明或售后规则。</p></div><button type="button" class="small-button" data-action="add-knowledge">添加资料</button></div>`;
    html += input.context.knowledge.length ? input.context.knowledge.map((item, index) => {
      const products = [["", "不关联商品"], ...input.products.map((product) => [product.goods_id || "", product.goods_name || "未命名商品"])];
      const topics = [["spec", "规格参数"], ["price", "价格"], ["inventory", "库存"], ["shipping", "发货时间"], ["freight", "运费"], ["policy", "售后规则"], ["faq", "常见问答"], ["response_guidelines", "客服回答规范"]];
      return `<div class="data-card"><div class="data-card-title"><strong>${esc(item.title || `知识资料 ${index + 1}`)}</strong><button type="button" class="text-danger" data-action="remove-knowledge" data-index="${index}">移除</button></div><div class="form-grid">${field("资料名称", `input.context.knowledge.${index}.title`, item.title || "")}${field("资料类型", `input.context.knowledge.${index}.source_type`, item.source_type || "product", { options: [["product", "商品信息"], ["shop_policy", "店铺规则"], ["policy", "店铺规则"], ["faq", "常见问答"], ["response_guidelines", "客服回答规范"], ["shipping", "发货规则"], ["after_sales", "售后规则"]] })}${field("关联商品", `input.context.knowledge.${index}.goods_id`, item.goods_id || "", { options: products })}</div><div class="topic-picker"><span>这份资料涉及</span><div class="choice-grid">${topics.map(([value, label]) => `<label class="check-card"><input type="checkbox" data-topic-index="${index}" data-option="${value}" ${(item.topics || []).includes(value) ? "checked" : ""}><span>${label}</span></label>`).join("")}</div></div>${field("资料内容", `input.context.knowledge.${index}.text`, item.text || "", { rows: 3 })}</div>`;
    }).join("") : `<div class="empty-data">还没有知识资料。点击“添加资料”，写入本条测试可以依据的商品信息或店铺规则。</div>`;
    const products = [["", "不指定商品"], ...input.products.map((product) => [product.goods_id || "", product.goods_name || "未命名商品"])];
    input.context.history ||= [];
    html += `<div class="form-grid context-fields">${field("买家正在咨询的商品", "input.context.product_context.goods_id", input.context.product_context?.goods_id || "", { options: products })}</div>`;
    html += `<div class="section-heading knowledge-heading"><div><h3>此前对话（选填）</h3><p>用于测试 Agent 是否记住前面聊过的内容。</p></div><button type="button" class="small-button" data-action="add-history">添加对话</button></div>`;
    html += input.context.history.length ? input.context.history.map((message, index) => `<div class="data-card"><div class="data-card-title"><strong>此前消息 ${index + 1}</strong><button type="button" class="text-danger" data-action="remove-history" data-index="${index}">移除</button></div><div class="form-grid">${field("谁说的", `input.context.history.${index}.direction`, message.direction || "inbound", { options: [["inbound", "买家"], ["outbound", "客服"]] })}</div>${field("说了什么", `input.context.history.${index}.content`, message.content || "", { rows: 2 })}</div>`).join("") : `<div class="empty-data">如果问题不依赖前文，可以留空。</div>`;
    html += `</section>`;
  } else {
    html += `<section class="form-section"><h3>商品与知识资料</h3><div class="notice">这条用例目前使用预设背景资料。需要换成自己的商品或规则时，可以切换为自定义资料并填写。</div><button type="button" class="small-button" data-action="customize-context">编辑这条用例的资料</button></section>`;
  }
  return html;
}
function renderKnowledgeForm(row) {
  const operationLabels = { parse: "解析资料", deduplicate: "重复资料处理", replace: "更新资料", delete: "删除资料", conflict: "冲突识别" };
  const formatLabels = { txt: "纯文本", md: "Markdown", pdf: "PDF", docx: "Word", xlsx: "Excel", csv: "CSV", image: "图片", binary: "其他" };
  const categoryLabels = { product: "商品信息", shipping: "发货规则", after_sales: "售后规则", faq: "常见问答", response_guidelines: "客服回答规范", other: "其他" };
  let html = `<section class="form-section"><h3>测试资料如何处理</h3><div class="form-grid">${field("用途", "split", row.split, { options: [["dev", "日常调试"], ["test", "验收检查"]] })}${field("要模拟的情况", "operation", row.operation, { options: options(operationLabels) })}</div></section>`;
  row.documents ||= [];
  html += `<section class="form-section"><div class="section-heading"><div><h3>输入资料</h3><p>模拟上传给知识库的原始文件。</p></div><button type="button" class="small-button" data-action="add-document">添加资料</button></div>`;
  html += row.documents.map((doc, index) => `<div class="data-card"><div class="data-card-title"><strong>资料 ${index + 1}</strong><button type="button" class="text-danger" data-action="remove-document" data-index="${index}">移除</button></div><div class="form-grid">${field("文件格式", `documents.${index}.format`, doc.format, { options: options(formatLabels) })}${field("资料是否有效", `documents.${index}.state`, doc.state || "active", { options: [["active", "有效"], ["deleted", "已删除"]] })}</div>${field("资料内容", `documents.${index}.content`, doc.content || "", { rows: 5, hint: "粘贴文件中的文字内容；评分时会按所选格式解析" })}</div>`).join("") || `<div class="empty-data">请至少添加一份测试资料。</div>`;
  html += `</section><section class="form-section"><div class="section-heading"><div><h3>期望解析结果</h3><p>填写解析器应该提取出的条目，系统会据此自动评分。</p></div><button type="button" class="small-button" data-action="add-entry">添加期望条目</button></div>`;
  row.expected_entries ||= [];
  html += row.expected_entries.map((entry, index) => {
    const docIndex = row.documents.findIndex((doc) => doc.source_id === entry.source_id && doc.version === entry.version);
    const sources = row.documents.map((doc, docNo) => [String(docNo), `资料 ${docNo + 1}（${formatLabels[doc.format] || doc.format}）`]);
    return `<div class="data-card"><div class="data-card-title"><strong>应该识别出的知识 ${index + 1}</strong><button type="button" class="text-danger" data-action="remove-entry" data-index="${index}">移除</button></div><div class="form-grid"><label class="form-field"><span>知识来自哪份资料</span><select data-entry-doc="${index}">${sources.map(([value, label]) => `<option value="${value}" ${Number(value) === Math.max(0, docIndex) ? "selected" : ""}>${esc(label)}</option>`).join("")}</select></label>${field("知识分类", `expected_entries.${index}.category`, entry.category || "other", { options: options(categoryLabels) })}${field("大致位置（可不填）", `expected_entries.${index}.location`, entry.location || "", { hint: "例如：第 2 段" })}</div>${field("解析结果应该表达什么", `expected_entries.${index}.text`, entry.text || "", { rows: 2 })}</div>`;
  }).join("") || `<div class="empty-data">还没有标准答案。添加一条应该识别出的知识，之后才能自动判断解析是否正确。</div>`;
  const removalChoices = row.documents.map((doc, index) => `<label class="check-card"><input type="checkbox" data-choice-path="expected_deleted_sources" data-option="${esc(doc.source_id)}" ${(row.expected_deleted_sources || []).includes(doc.source_id) ? "checked" : ""}><span>资料 ${index + 1}</span></label>`).join("");
  html += `</section><details class="form-section less-common"><summary>特殊情况（通常不用改）</summary><div class="check-line">${field("资料中存在矛盾，应该提示人工核对", "expected_conflict", row.expected_conflict || false, { type: "checkbox" })}${field("需要人工复核", "expected_review_required", row.expected_review_required || false, { type: "checkbox" })}</div>${row.operation === "delete" ? `<div class="form-field"><span>这次操作应该移除哪些资料</span><div class="choice-grid">${removalChoices}</div></div>` : ""}<div class="form-grid">${field("预期结果", "expected_error", row.expected_error || "", { options: [["", "正常解析"], ["empty_document", "资料为空"], ["unreadable_content", "内容无法读取"], ["unsupported_format", "不支持的格式"]] })}</div></details>`;
  return html;
}
function renderForm() {
  const host = $("#form-editor");
  if (!state.draft) { host.innerHTML = `<div class="empty-editor">从左侧选择一条测试用例，或点击“新建用例”。</div>`; editor.value = ""; editor.disabled = true; return; }
  editor.disabled = false;
  host.innerHTML = state.suite === "agent" ? renderAgentForm(state.draft) : renderKnowledgeForm(state.draft);
  editor.value = JSON.stringify(state.draft, null, 2);
  $("#validation-state").textContent = state.advancedInvalid ? "高级数据格式有误" : "等待保存校验";
  $("#validation-state").className = state.advancedInvalid ? "invalid" : "";
}
function render() {
  const suite = activeSuite(); if (!suite) return;
  const labels = { agent: "客服回答测试", knowledge: "知识资料整理测试" };
  $("#suite-title").textContent = labels[state.suite]; $("#data-location").textContent = "本地测试副本 · 不影响线上客服";
  $("#total-count").textContent = suite.counts.total; $("#dev-count").textContent = suite.counts.dev; $("#test-count").textContent = suite.counts.test;
  $("#agent-count").textContent = state.suites.agent.counts.total; $("#knowledge-count").textContent = state.suites.knowledge.counts.total;
  document.querySelectorAll(".suite").forEach((button) => button.classList.toggle("active", button.dataset.suite === state.suite));
  $("#score-button").hidden = state.suite !== "knowledge";
  const query = $("#search").value.trim().toLowerCase(), split = $("#split-filter").value;
  const rows = suite.cases.filter((row) => (split === "all" || row.split === split) && (!query || JSON.stringify(row).toLowerCase().includes(query)));
  $("#visible-count").textContent = `${rows.length} 条`;
  const list = $("#case-list"); list.replaceChildren();
  rows.forEach((row) => {
    const button = document.createElement("button"); button.className = `case-row${state.selected === row.id ? " active" : ""}`;
    const title = document.createElement("strong");
    title.textContent = state.suite === "agent" ? (row.question || "尚未填写买家问题") : (row.documents?.[0]?.content?.split(/\n/)[0]?.slice(0, 54) || row.scenario || "尚未填写资料");
    const meta = document.createElement("small"), identity = document.createElement("span"), badge = document.createElement("span");
    identity.textContent = state.suite === "agent" ? (row.reference_answer ? `参考答案：${row.reference_answer.slice(0, 36)}` : "尚未填写理想回答") : ({ parse: "整理为知识条目", deduplicate: "处理重复资料", replace: "更新已有资料", delete: "删除资料", conflict: "检查内容冲突" }[row.operation] || "知识资料测试"); badge.className = `badge ${row.split}`; badge.textContent = row.split === "dev" ? "日常调试" : "验收检查";
    meta.append(identity, badge); button.append(title, meta); button.addEventListener("click", () => selectCase(row.id)); list.append(button);
  });
}
function selectCase(id) {
  if (state.dirty && !confirm("当前编辑内容尚未保存，切换后会丢弃。继续吗？")) return;
  const row = activeSuite().cases.find((item) => item.id === id); if (!row) return;
  state.selected = id; state.draft = structuredClone(row); state.advancedInvalid = false;
  $("#editor-heading").textContent = state.suite === "agent" ? (row.question || "客服回答测试") : (row.documents?.[0]?.content?.split(/\n/)[0]?.slice(0, 54) || "知识资料测试");
  $("#editor-subtitle").textContent = row.split === "dev" ? "日常调试用例" : "验收检查用例";
  $("#save-case").disabled = false; $("#delete-case").disabled = false; markDirty(false); showMessage(); renderForm(); render();
}
function clearEditor() {
  state.selected = null; state.draft = null; state.advancedInvalid = false;
  $("#editor-heading").textContent = "选择一个用例"; $("#editor-subtitle").textContent = "选择用例后可查看和编辑内容";
  $("#save-case").disabled = true; $("#delete-case").disabled = true; $("#validation-state").textContent = "—"; $("#validation-state").className = "";
  markDirty(false); renderForm();
}
function newTemplate() {
  if (state.dirty && !confirm("当前编辑内容尚未保存，继续会丢弃。继续吗？")) return;
  const id = `new-${Date.now().toString(36)}`;
  state.draft = state.suite === "agent" ? {
    id, scenario: id, split: "dev", fixture: "custom", question: "", expected: { outcomes: ["draft"], intents: [], tools: [], sources: [], contains: [], forbids: [], risk: false, max_characters: 180 },
    input: { context: { buyer_ref: "test-buyer", knowledge: [], history: [], product_context: {}, platform_product_facts: {}, order_reference: {}, questions: [], question_contexts: {}, question_orders: {} }, products: [], product_details: {}, orders: [] },
    reference_answer: "", provenance: { kind: "synthetic", reference: "", group_id: "" },
  } : { id, scenario: id, group_id: "", split: "dev", operation: "parse", documents: [{ source_id: `source-${Date.now()}`, version: "v1", format: "md", content: "", state: "active" }], expected_entries: [], expected_conflict: false, expected_review_required: false, expected_deleted_sources: [], expected_error: null };
  state.selected = id; state.advancedInvalid = false;
  $("#editor-heading").textContent = "新建测试用例"; $("#editor-subtitle").textContent = "填写内容后点击保存用例";
  $("#save-case").disabled = false; $("#delete-case").disabled = true; markDirty(true); showMessage("填写表单，完成后保存。", "success"); renderForm();
}
function applyField(target) {
  const kind = target.dataset.kind || "string", path = target.dataset.path;
  let value;
  if (kind === "boolean") value = target.checked;
  else if (kind === "number") value = target.value === "" ? null : Number(target.value);
  else if (kind === "currency-cents") value = target.value === "" ? null : Math.round(Number(target.value) * 100);
  else if (kind === "tags") value = target.value.split(/[\n,，]/).map((item) => item.trim()).filter(Boolean);
  else if (kind.endsWith("lines")) {
    const next = target.value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean);
    if (kind === "pattern-lines") {
      const previous = getAt(state.draft, path) || [];
      value = next.map((item, index) => item === friendlyPattern(previous[index] ?? "") ? previous[index] : item);
    } else value = next;
  }
  else value = target.value;
  setAt(state.draft, path, value);
  if (path === "fixture") {
    if (value === "custom" && !state.draft.input) state.draft.input = { context: { buyer_ref: "test-buyer", knowledge: [], history: [], product_context: {}, platform_product_facts: {}, order_reference: {}, questions: [], question_contexts: {}, question_orders: {} }, products: [], product_details: {}, orders: [] };
    if (value !== "custom") state.draft.input = null;
    renderForm();
  } else if (path.startsWith("id")) { /* identity is fixed after creation */ }
  state.advancedInvalid = false; markDirty(true);
  if (document.activeElement !== editor) editor.value = JSON.stringify(state.draft, null, 2);
  $("#editor-heading").textContent = state.suite === "agent" ? (state.draft.question || "客服回答测试") : (state.draft.documents?.[0]?.content?.split(/\n/)[0]?.slice(0, 54) || "知识资料测试");
  $("#editor-subtitle").textContent = state.draft.split === "dev" ? "日常调试用例" : "验收检查用例";
  $("#validation-state").textContent = "已编辑；保存时检查"; $("#validation-state").className = "";
}
function addOrRemove(action, index, skuIndex = 0) {
  const row = state.draft;
  if (action === "customize-context") {
    row.fixture = "custom";
    row.input ||= { context: { buyer_ref: "test-buyer", knowledge: [], history: [], product_context: {}, platform_product_facts: {}, order_reference: {}, questions: [], question_contexts: {}, question_orders: {} }, products: [], product_details: {}, orders: [] };
    row.input.context ||= {}; row.input.context.knowledge ||= []; row.input.products ||= [];
  }
  if (action === "add-product") row.input.products.push({ goods_id: `test-product-${Date.now()}`, goods_name: "", quantity: null, price: "" });
  if (action === "remove-product") {
    const product = row.input.products[index];
    if (product) {
      row.input.products.splice(index, 1);
      delete row.input.product_details?.[product.goods_id];
      if (row.input.context.product_context?.goods_id === product.goods_id) row.input.context.product_context.goods_id = "";
      row.input.context.knowledge.forEach((item) => { if (item.goods_id === product.goods_id) item.goods_id = ""; });
    }
  }
  if (action === "add-knowledge") row.input.context.knowledge.push({ source_id: `knowledge-${Date.now()}`, source_type: "product", title: "", topics: [], text: "" });
  if (action === "remove-knowledge") row.input.context.knowledge.splice(index, 1);
  if (action === "add-sku") {
    const product = row.input.products[Number(index)], details = row.input.product_details ||= {};
    details[product.goods_id] ||= { goods_id: product.goods_id, skus: [] };
    details[product.goods_id].skus ||= [];
    details[product.goods_id].skus.push({ sku_id: `test-sku-${Date.now()}`, specs: [{ name: "规格", value: "" }], quantity: null, group_price_cents: null, single_price_cents: null, on_sale: true });
  }
  if (action === "remove-sku") {
    const product = row.input.products[Number(index)], detail = row.input.product_details?.[product.goods_id];
    detail?.skus?.splice(Number(skuIndex), 1);
  }
  if (action === "add-history") row.input.context.history.push({ id: `history-${Date.now()}`, direction: "inbound", message_type: "text", content: "" });
  if (action === "remove-history") row.input.context.history.splice(index, 1);
  if (action === "add-document") row.documents.push({ source_id: `source-${Date.now()}`, version: "v1", format: "md", content: "", state: "active" });
  if (action === "remove-document") {
    if (row.documents.length <= 1) return showMessage("每条知识资料测试至少需要保留一份输入资料。", "error");
    const removed = row.documents[index];
    row.documents.splice(index, 1);
    row.expected_entries = row.expected_entries.filter((entry) => entry.source_id !== removed.source_id || entry.version !== removed.version);
  }
  if (action === "add-entry") row.expected_entries.push({ source_id: row.documents[0]?.source_id || "source-01", version: row.documents[0]?.version || "v1", category: "other", text: "", location: "", attributes: {}, also_from: [], must_contain: [] });
  if (action === "remove-entry") row.expected_entries.splice(index, 1);
  markDirty(true); renderForm();
}
async function saveCurrent() {
  if (state.advancedInvalid) return showMessage("高级设置里的数据格式有误，请修正或恢复后再保存。", "error");
  const row = structuredClone(state.draft);
  if (!row || !row.id || row.id !== state.selected) return showMessage("用例信息不完整，请刷新页面后重试。", "error");
  const suite = activeSuite(), index = suite.cases.findIndex((item) => item.id === row.id), cases = suite.cases.slice();
  if (index < 0) cases.push(row); else cases[index] = row;
  try {
    const result = await request(`/api/suites/${state.suite}`, { method: "PUT", body: JSON.stringify({ revision: suite.revision, cases }) });
    suite.cases = cases; suite.revision = result.revision; suite.counts = { total: cases.length, dev: cases.filter((x) => x.split === "dev").length, test: cases.filter((x) => x.split === "test").length };
    state.draft = row; markDirty(false); showMessage("已保存并通过整套数据校验；旧版本已备份。", "success");
    $("#editor-heading").textContent = state.suite === "agent" ? (row.question || "客服回答测试") : (row.documents?.[0]?.content?.split(/\n/)[0]?.slice(0, 54) || "知识资料测试"); $("#editor-subtitle").textContent = row.split === "dev" ? "日常调试用例" : "验收检查用例"; render();
  } catch (error) { showMessage(friendlyError(error.message), "error"); }
}
async function deleteCurrent() {
  if (!state.selected || !confirm(`确定删除用例 ${state.selected}？删除前会自动备份。`)) return;
  const suite = activeSuite(), cases = suite.cases.filter((row) => row.id !== state.selected);
  try {
    const result = await request(`/api/suites/${state.suite}`, { method: "PUT", body: JSON.stringify({ revision: suite.revision, cases }) });
    suite.cases = cases; suite.revision = result.revision; suite.counts = { total: cases.length, dev: cases.filter((x) => x.split === "dev").length, test: cases.filter((x) => x.split === "test").length };
    clearEditor(); showMessage("用例已删除。", "success"); render();
  } catch (error) { showMessage(friendlyError(error.message), "error"); }
}
function parseImport(text) {
  try { const value = JSON.parse(text); if (Array.isArray(value)) return value; if (value && Array.isArray(value.cases)) return value.cases; return [value]; }
  catch { return text.split(/\r?\n/).filter((line) => line.trim()).map((line, index) => { try { return JSON.parse(line); } catch { throw new Error(`第 ${index + 1} 行不是有效 JSON`); } }); }
}
async function importFile(file) {
  try {
    const cases = parseImport(await file.text()); if (!cases.length) throw new Error("文件中没有测试用例");
    const replace = confirm(`读到 ${cases.length} 条用例。\n\n确定：覆盖当前测试集\n取消：按编号合并（同编号更新）`), suite = activeSuite();
    const result = await request(`/api/suites/${state.suite}/import`, { method: "POST", body: JSON.stringify({ revision: suite.revision, cases, mode: replace ? "replace" : "merge" }) });
    await loadState(false); showMessage(`导入完成，共 ${result.count} 条；数据已校验并备份。`, "success");
  } catch (error) { showMessage(friendlyError(error.message), "error"); }
}
async function loadState(ask = true) {
  if (ask && state.dirty && !confirm("当前编辑内容尚未保存，刷新会丢弃修改。继续吗？")) return;
  try {
    const response = await fetch("/api/state", { cache: "no-store" }); if (!response.ok) throw new Error("读取测试集失败");
    const result = await response.json(); state.token = result.token; state.dataDir = result.data_dir; state.suites = result.suites; clearEditor(); render(); showMessage("数据已从本地文件加载。", "success");
  } catch (error) { showMessage(friendlyError(error.message), "error"); }
}
async function scoreKnowledge() {
  if (state.dirty && !confirm("当前用例尚未保存，评分会读取磁盘上的版本。继续吗？")) return;
  const button = $("#score-button"); button.disabled = true; button.textContent = "评分中…"; showMessage("正在调用实际资料解析逻辑，不调用模型。", "");
  try {
    const result = await request("/api/suites/knowledge/score", { method: "POST", body: "{}" }), panel = $("#score-results");
    panel.hidden = false; panel.replaceChildren(); const summary = document.createElement("div"); summary.className = "score-summary";
    summary.textContent = `已检查 ${result.scored_cases} 条可解析资料 · 通过 ${result.auto_pass} · 需查看 ${result.auto_fail} · 去重和删除类用例暂未支持自动检查 ${result.not_connected_cases} 条`; panel.append(summary);
    const list = document.createElement("div"); list.className = "score-list";
    result.results.forEach((item, index) => {
      const row = document.createElement("details"); row.className = "score-row"; const heading = document.createElement("summary"); const name = document.createElement("strong"); name.textContent = item.case_id; const detail = document.createElement("span");
      if (item.status === "scored") {
        const score = item.score, tested = activeSuite().cases.find((testCase) => testCase.id === item.case_id); row.classList.add(score.passed ? "pass" : "fail");
        name.textContent = `${index + 1}. ${tested?.documents?.[0]?.content?.split(/\n/)[0]?.slice(0, 38) || tested?.scenario || "资料检查"}`;
        detail.textContent = `${score.passed ? "符合预期" : "需要修改"} · 知识找全 ${Math.round((score.entry_recall || 0) * 100)}% · 分类正确 ${Math.round((score.category_accuracy || 0) * 100)}%`;
        const checkLabels = { entry_recall: "没有识别出全部应有知识", category_labels: "部分知识分类不正确", no_unexpected_entries: "多识别了不应出现的知识", ambiguous_content_requires_review: "资料有歧义但未提示人工复核", expected_parse_error: "解析错误与预期不一致", all_documents_parsed: "有资料解析失败" };
        const evidence = document.createElement("pre"), failed = Object.keys(score.checks || {}).filter((key) => !score.checks[key]).map((key) => checkLabels[key] || key), predicted = (score.predicted || []).map((entry) => `· ${entry.category || "未分类"}：${entry.text}${entry.location ? `（${entry.location}）` : ""}`);
        evidence.textContent = [...(failed.length ? [`未通过检查：${failed.join("、")}`] : []), ...(predicted.length ? ["解析结果：", ...predicted] : ["没有解析出条目"]), ...(score.actual_errors || [])].join("\n"); row.append(heading, evidence);
      } else { const tested = activeSuite().cases.find((testCase) => testCase.id === item.case_id); name.textContent = `${index + 1}. ${tested?.scenario || "资料更新用例"}`; detail.textContent = "尚未支持自动检查"; row.append(heading); }
      heading.append(name, detail); list.append(row);
    });
    panel.append(list); showMessage("评分完成。点击结果可查看实际解析内容。", "success");
  } catch (error) { showMessage(friendlyError(error.message), "error"); }
  finally { button.disabled = false; button.textContent = "检查解析效果"; }
}

document.querySelectorAll(".suite").forEach((button) => button.addEventListener("click", () => {
  if (state.suite === button.dataset.suite || (state.dirty && !confirm("当前编辑内容尚未保存，切换后会丢弃。继续吗？"))) return;
  state.suite = button.dataset.suite; clearEditor(); render();
}));
$("#new-case").addEventListener("click", newTemplate); $("#save-case").addEventListener("click", saveCurrent); $("#delete-case").addEventListener("click", deleteCurrent);
$("#refresh-button").addEventListener("click", () => loadState(true)); $("#search").addEventListener("input", render); $("#split-filter").addEventListener("change", render);
$("#import-button").addEventListener("click", () => $("#file-input").click()); $("#file-input").addEventListener("change", (event) => { if (event.target.files[0]) importFile(event.target.files[0]); event.target.value = ""; });
$("#export-button").addEventListener("click", () => { window.location.href = `/api/suites/${state.suite}/export`; }); $("#score-button").addEventListener("click", scoreKnowledge);
$("#form-editor").addEventListener("input", (event) => {
  const target = event.target;
  if (target.dataset.topicIndex !== undefined) {
    const item = state.draft.input.context.knowledge[Number(target.dataset.topicIndex)], topics = item.topics || [];
    item.topics = target.checked ? [...new Set([...topics, target.dataset.option])] : topics.filter((topic) => topic !== target.dataset.option);
    markDirty(true); editor.value = JSON.stringify(state.draft, null, 2); return;
  }
  if (target.dataset.choicePath) {
    const path = target.dataset.choicePath, values = getAt(state.draft, path) || [];
    setAt(state.draft, path, target.checked ? [...new Set([...values, target.dataset.option])] : values.filter((item) => item !== target.dataset.option));
    state.advancedInvalid = false; markDirty(true); editor.value = JSON.stringify(state.draft, null, 2); return;
  }
  if (target.dataset.path) applyField(target);
});
$("#form-editor").addEventListener("change", (event) => {
  const target = event.target;
  if (target.dataset.path) applyField(target);
  if (target.dataset.entryDoc !== undefined) {
    const entry = state.draft.expected_entries[Number(target.dataset.entryDoc)], document = state.draft.documents[Number(target.value)];
    if (entry && document) { entry.source_id = document.source_id; entry.version = document.version; markDirty(true); editor.value = JSON.stringify(state.draft, null, 2); }
  }
});
$("#form-editor").addEventListener("click", (event) => {
  const button = event.target.closest("[data-action]");
  if (button) addOrRemove(button.dataset.action, Number(button.dataset.index ?? button.dataset.productIndex ?? 0), Number(button.dataset.skuIndex ?? 0));
});
editor.addEventListener("input", () => {
  try { const value = JSON.parse(editor.value); if (!value || Array.isArray(value)) throw new Error(); state.draft = value; state.advancedInvalid = false; $("#validation-state").textContent = "高级数据格式正确"; $("#validation-state").className = "valid"; markDirty(true); }
  catch { state.advancedInvalid = true; $("#validation-state").textContent = "JSON 格式有误"; $("#validation-state").className = "invalid"; markDirty(true); }
});
editor.addEventListener("blur", () => { if (!state.advancedInvalid && state.draft) renderForm(); });
window.addEventListener("beforeunload", (event) => { if (state.dirty) { event.preventDefault(); event.returnValue = ""; } });
loadState(false);
