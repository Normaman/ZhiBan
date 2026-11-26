/* Local curation only: no model generation or platform operations. */
const $ = (id) => document.getElementById(id);
const copy = (value) => structuredClone(value);
const labels = {draft:"生成草稿", needs_human:"转人工", insufficient_evidence:"资料不足"};
const sourceLabels = {synthetic:"人工合成", anonymized_real:"脱敏真实", public_reference:"公开资料"};
const tools = {get_shop_products:"商品查询",get_product_skus:"规格与库存",get_order_details:"订单查询",get_product_shipping:"发货与运费",get_product_knowledge:"商品知识",search_customer_service_knowledge:"规则检索",transfer_conversation:"建议人工处理"};
const fixtures = {inventory:"商品库存",linked:"关联商品",unit:"每件数量",sku:"规格库存与价格",price:"商品价格",shipping:"商品发货",presale:"预售",freight:"运费",faq:"常见问答",policy:"售后规则",generic_shipping:"店铺发货规则",cancelled:"已取消订单",unpaid:"待付款订单",logistics:"缺物流资料",risk:"风险咨询",social:"普通交流",unknown_stock:"库存未知",missing:"资料缺失",batch:"多条咨询",memory:"人工回复历史",custom:"自定义测试事实"};
let state, selected, view = "answers", editor, adjudication, importing;
let reviewQueue = Promise.resolve();
const loadingControls=[...document.querySelectorAll("header button, aside input, aside select, .actions button")];
for(const control of loadingControls)control.disabled=true;
function node(tag, text, cls) {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = text;
  if (cls) element.className = cls;
  return element;
}
function notice(text, href, caption="下载文件") {
  $("notice").replaceChildren(node("span", text));
  if (href) { const a=node("a",caption);a.href=href;a.download="";$("notice").append(a); }
}
async function api(path, body, method="POST") {
  const response = await fetch(path, body === undefined ? {} : {
    method, headers:{"Content-Type":"application/json","X-Curation-Token":state.token},
    body:JSON.stringify(body),
  });
  const value = await response.json();
  if (!response.ok) throw new Error(value.detail || "操作失败");
  if (value.revision !== undefined && state) state.revision=value.revision;
  return value;
}
async function refresh() {
  state=await api("/api/workspace");
  for(const control of loadingControls)control.disabled=false;
  try { $("legacy-reviews").hidden=!Object.keys(JSON.parse(localStorage.getItem(state.legacy_storage_key)||"{}")).some(k=>!state.reviews[k]); } catch { $("legacy-reviews").hidden=true; }
  if (!selected) selected=state.cases.find(c=>c.rows.after.some(r=>!r.score.passed))?.case.id || state.cases[0]?.case.id;
  render();
}
function current() { return state.cases.find(c=>c.case.id===selected); }
function scopedRows() {
  const filter=$("filter").value,query=$("search").value.toLowerCase();
  return state.cases.filter(c=>{
    const match=filter==="archived"?c.archived:!c.archived;
    return match && (!query||(c.case.id+" "+c.case.question).toLowerCase().includes(query)) && (
      filter==="all"||filter==="archived"||filter==="failed"&&c.rows.after.some(r=>!r.score.passed)||
      filter==="regressed"&&c.regressed||filter==="sample"&&c.sample||
      filter==="edited"&&c.edited||filter==="pending"&&c.requires_rerun||filter==="risk"&&c.case.expected.risk);
  });
}
function render() {
  if(!state)return;
  const active=state.cases.filter(c=>!c.archived);
  $("status").textContent=`本地已保存 · ${active.length} 条用例 · ${active.filter(c=>c.edited).length} 条修订 · ${active.filter(c=>c.requires_rerun).length} 条需重跑`;
  const rows=scopedRows();
  if (!rows.some(c=>c.case.id===selected)) selected=rows[0]?.case.id;
  $("case-list").replaceChildren();
  for(const c of rows){
    const b=node("button",c.case.id+" · "+c.case.question);
    b.setAttribute("aria-current",String(c.case.id===selected));
    b.onclick=()=>{selected=c.case.id;render();};$("case-list").append(b);
  }
  if(!rows.length)$("case-list").append(node("small","没有匹配的用例。"));
  const c=current();$("content").replaceChildren();
  for(const id of ["edit-case","clone-case","archive-case"])$(id).disabled=!c;
  if(!c){$("question").textContent="没有匹配的用例";$("case-meta").textContent="";$("input-change").textContent="";return;}
  $("question").textContent=c.case.question;
  $("case-meta").textContent=`${c.case.id} · ${c.case.scenario} · ${c.case.split==="test"?"预留测试集":"开发 / 回归集"} · ${sourceLabels[c.case.provenance.kind]}`;
  $("archive-case").disabled=c.archived;
  $("input-change").textContent=c.archived?"该用例已归档，不进入新数据集。编辑保存可恢复。":
    c.requires_rerun?"问题、上下文或测试事实有变化；历史输出只作参考，需要重新评测。":
      c.edited?"本次仅标注或来源说明有变化，可对保存的输出重算检查。":"";
  for(const button of document.querySelectorAll("[data-view]"))button.setAttribute("aria-selected",String(button.dataset.view===view));
  if(view==="facts")renderFacts(c);else if(view==="history")renderHistory(c);else renderAnswers(c);
}
function details(title, value) {
  const d=node("details");d.append(node("summary",title),node("pre",JSON.stringify(value,null,2)));return d;
}
function renderFacts(c) {
  const card=node("section",undefined,"card");
  card.append(node("h3","当前用例的参考答案"),node("p",c.case.reference_answer||"尚未填写参考答案。"));
  card.append(details("当前预期与自动检查",c.case.expected),details("当前测试事实",c.case.input||{template:fixtures[c.case.fixture]}),
    details("历史评测实际使用的上下文",c.original_context),details("来源与同源分组",c.case.provenance));
  $("content").append(card);
}
function renderHistory(c) {
  const entries=state.journal.filter(e=>e.id===c.case.id||e.id.includes(":"+c.case.id+":"));
  if(!entries.length){$("content").append(node("p","尚无修订记录。","empty"));return;}
  for(const entry of entries.toReversed()){
    const box=node("section",undefined,"journal-entry");
    box.append(node("small",new Date(entry.at).toLocaleString()),node("p",entry.reason),details("修订前后",{before:entry.before,after:entry.after}));
    $("content").append(box);
  }
}
function renderAnswers(c) {
  const columns=node("div",undefined,"columns");
  for(const version of ["before","after"]){
    const section=node("section",undefined,"version");section.setAttribute("aria-label",version==="before"?"改动前":"改动后");
    section.append(node("h3",version==="before"?"改动前":"改动后"));
    const stats=state.stats[version];section.append(node("p",`原自动检查 ${stats.original_pass}/${stats.total} · 校正后 ${stats.corrected_pass}/${stats.total} · 已复核判分 ${stats.reviewed} 条`,"stats"));
    if(!c.rows[version].length){section.append(node("p","该用例没有历史输出，需导出后运行新评测。"));columns.append(section);continue;}
    for(const row of c.rows[version])renderResult(section,c,version,row);
    columns.append(section);
  }
  $("content").append(columns);
}
function renderResult(section,c,version,row){
  const result=row.result,key=`${version}:${c.case.id}:${row.repeat}`,correction=state.adjudications[key];
  const passed=row.score.checks.every(check=>correction?.check_overrides[check.name]??check.passed);
  section.append(node("span",row.score.passed?"原检查通过":"原检查未通过","badge"+(row.score.passed?"":" warning")));
  if(correction)section.append(node("span",passed?"人工校正后通过":"人工校正后仍未通过","badge"+(passed?"":" warning")));
  section.append(node("small",labels[result.outcome]||result.outcome),node("p",result.content||"没有回复","answer"));
  const button=node("button","纠正自动判分");button.onclick=()=>openAdjudication(version,c,row);section.append(button);
  if(correction?.reason)section.append(node("p","补充说明："+correction.reason,"hint"));
  section.append(details("自动检查明细",row.score.checks),details("最终引用",result.citations||[]),
    details("理解与验证记录",{understanding:result.understanding,verification:result.verification,question_results:result.question_results}),
    details("原始检索记录",row.retrievals));
  const queries=node("details");queries.append(node("summary",`实际工具查询（${row.tools.length}）`));
  for(const t of row.tools){const d=node("details");d.append(node("summary",tools[t.name]||t.name),details("参数",t.effective_arguments||t.arguments));
    for(const e of t.response?.evidence||[])d.append(node("div",e.source_id+"\n"+e.evidence_text,"evidence"));
    d.append(details("完整结果",t.response));queries.append(d);}
  section.append(queries);
  const review=copy(state.reviews[key]||{factually_correct:null,grounded:null,usable_without_edit:null,notes:""});
  const reviewBox=node("details");reviewBox.append(node("summary","人工质量评分"));
  for(const [field,label] of [["factually_correct","结论是否正确"],["grounded","事实是否有实际依据"],["usable_without_edit","处理结果是否可直接采用"]]){
    const wrap=node("label",label,"review-label"),select=node("select");select.setAttribute("aria-label",label);
    for(const [value,text] of [["","未评"],["true","是"],["false","否"]]){const option=node("option",text);option.value=value;select.append(option);}
    select.value=typeof review[field]==="boolean"?String(review[field]):"";
    select.onchange=()=>{review[field]=select.value===""?null:select.value==="true";queueReview(version,c,row,review);};
    wrap.append(select);reviewBox.append(wrap);
  }
  const notes=node("textarea");notes.setAttribute("aria-label","评分说明");notes.placeholder="填写依据与待确认事项";notes.value=review.notes||"";
  notes.onchange=()=>{review.notes=notes.value;queueReview(version,c,row,review);};reviewBox.append(notes);
  const download=node("a","下载本版本已完成评分");download.href=`/api/reviews/${version}/download`;reviewBox.append(download);section.append(reviewBox);
}
function queueReview(version,c,row,labels){
  const value=copy(labels);
  reviewQueue=reviewQueue.then(async()=>{
    await api("/api/reviews",{revision:state.revision,version,case_id:c.case.id,repeat:row.repeat,result_fingerprint:row.result_fingerprint,labels:value});
    state.reviews[`${version}:${c.case.id}:${row.repeat}`]=value;notice("评分已保存到本地工作区。");
  }).catch(error=>notice(error.message));
}
function editorTab(name){
  for(const section of $("editor").querySelectorAll("[data-panel]"))section.hidden=section.dataset.panel!==name;
  for(const button of $("editor-tabs").children)button.setAttribute("aria-selected",String(button.dataset.tab===name));
}
function openEditor(mode){
  const base=copy(current()?.case||state.cases[0].case);
  editor={mode,base,templateId:current()?.case.id||state.cases[0].case.id};
  const fresh=mode!=="edit";
  if(mode==="new"){base.question="";base.reference_answer="";base.scenario="new_scenario";base.provenance={kind:"synthetic",reference:"",group_id:""};}
  if(fresh)base.id=(mode==="clone"?base.id+"-copy-":"case-")+Date.now().toString(36);
  $("editor-title").textContent=mode==="edit"?"编辑用例":mode==="clone"?"复制新增用例":"新增用例";
  for(const [id,value] of [["case-id",base.id],["case-scenario",base.scenario],["case-question",base.question],["case-split",base.split],["source-kind",base.provenance.kind],["source-reference",base.provenance.reference],["source-group",base.provenance.group_id],["expected-intents",base.expected.intents.join(", ")],["reference-answer",base.reference_answer],["max-characters",base.expected.max_characters]])$(id).value=value;
  $("case-id").readOnly=!fresh;
  for(const checkbox of $("case-form").querySelectorAll("[name=outcome]"))checkbox.checked=base.expected.outcomes.includes(checkbox.value);
  const advanced=copy(base.expected);for(const key of ["outcomes","intents","max_characters"])delete advanced[key];
  $("advanced-expected").value=JSON.stringify(advanced,null,2);
  $("fixture").replaceChildren();for(const [value,text] of Object.entries(fixtures)){const option=node("option",text);option.value=value;$("fixture").append(option);}$("fixture").value=base.fixture;
  $("case-input").value=base.input?JSON.stringify(base.input,null,2):"";$("edit-reason").value="";$("editor-error").textContent="";
  editorTab("question");$("editor").showModal();
}
function formCase(){
  const data=copy(editor.base);data.id=$("case-id").value.trim();data.scenario=$("case-scenario").value.trim();data.split=$("case-split").value;data.question=$("case-question").value.trim();
  data.provenance={kind:$("source-kind").value,reference:$("source-reference").value.trim(),group_id:$("source-group").value.trim()};
  data.reference_answer=$("reference-answer").value.trim();
  data.expected={...JSON.parse($("advanced-expected").value),outcomes:[...$("case-form").querySelectorAll("[name=outcome]:checked")].map(c=>c.value),intents:$("expected-intents").value.split(/[,，]/).map(v=>v.trim()).filter(Boolean),max_characters:Number($("max-characters").value)};
  data.fixture=$("fixture").value;data.input=data.fixture==="custom"?JSON.parse($("case-input").value):null;
  if(data.input?.context?.questions?.length)data.input.context.questions.at(-1).content=data.question;
  return data;
}
$("case-form").onsubmit=async(event)=>{event.preventDefault();try{
  const value=formCase();await api("/api/cases/"+encodeURIComponent(value.id),{revision:state.revision,case:value,reason:$("edit-reason").value,create:editor.mode!=="edit"},"PUT");
  selected=value.id;$("filter").value="all";$("search").value="";$("editor").close();await refresh();notice("用例修订已保存；基础数据集和历史报告没有被覆盖。");
}catch(error){$("editor-error").textContent=error.message;}};
$("materialize").onclick=async()=>{try{
  if($("fixture").value==="custom"&&$("case-input").value.trim())return;
  const value=copy(editor.base);value.fixture=$("fixture").value==="custom"?"inventory":$("fixture").value;value.input=null;value.question=$("case-question").value.trim()||value.question||"请补充问题";
  const data=await api("/api/template",{case:value});$("case-input").value=JSON.stringify(data,null,2);$("fixture").value="custom";
}catch(error){$("editor-error").textContent=error.message;}};
function checkLabel(check,index){
  const name=check.name;
  const maps={expected_outcome:"处理结果",intent_exact_match:"意图集合",valid_tool_strategy:"工具路径",reply_length:"回答长度",partial_batch_coverage:"各问题回答覆盖",citations_from_actual_tools:"引用来自实际工具",pinned_knowledge_version:"知识版本",required_knowledge_retrieved:"标注知识被检索到",risk_does_not_produce_draft:"风险咨询不生成草稿",no_foreign_product_citation:"没有引用其他商品"};
  return maps[name]||name.replace("required_fact_pattern:","回答要点 #").replace("forbidden_claim_pattern:","禁止断言 #").replace("required_source:","依据来源：").replace("required_tool:","必要工具：").replace("platform_source_scope:","商品范围：");
}
function openAdjudication(version,c,row){
  adjudication={version,c,row};const saved=state.adjudications[`${version}:${c.case.id}:${row.repeat}`];
  $("adjudication-reason").value=saved?.reason||"";$("adjudication-error").textContent="";$("checks").replaceChildren();$("other-checks").replaceChildren();
  $("adjudication-question").textContent=c.case.question;
  const addCheck=(check,target)=>{const wrap=node("label",checkLabel(check),"check-row"),select=node("select");select.dataset.check=check.name;select.setAttribute("aria-label",checkLabel(check));
    for(const [value,text] of [["",check.passed?"原判：通过":"原判：未通过"],["true","改为通过"],["false","改为未通过"]]){const option=node("option",text);option.value=value;select.append(option);}
    select.value=typeof saved?.check_overrides[check.name]==="boolean"?String(saved.check_overrides[check.name]):"";wrap.append(select);target.append(wrap);};
  row.score.checks.filter(check=>!check.passed).forEach(check=>addCheck(check,$("checks")));
  row.score.checks.filter(check=>check.passed).forEach(check=>addCheck(check,$("other-checks")));
  if(!$("checks").children.length)$("checks").append(node("p","自动检查全部通过。若发现漏判，可在“查看其他检查项”中改判。","hint"));
  $("adjudicator").showModal();
}
$("adjudication-form").onsubmit=async(event)=>{event.preventDefault();try{
  const overrides={};for(const select of $("adjudicator").querySelectorAll("select[data-check]"))if(select.value!=="")overrides[select.dataset.check]=select.value==="true";
  const {version,c,row}=adjudication;
  if(!Object.keys(overrides).length)throw new Error("请选择至少一个需要改判的检查项");
  await api("/api/adjudications",{revision:state.revision,version,case_id:c.case.id,repeat:row.repeat,result_fingerprint:row.result_fingerprint,report_hash:state.report_hashes[version],check_overrides:overrides,reason:$("adjudication-reason").value,category:"scorer_error"});
  $("adjudicator").close();await refresh();notice("判分校正已保存；原自动分数保留，未改动模型输出。");
}catch(error){$("adjudication-error").textContent=error.message;}};
$("export-form").onsubmit=async(event)=>{event.preventDefault();try{
  const value=await api("/api/datasets/export",{revision:state.revision,version:$("version-name").value.trim(),reason:$("version-reason").value});
  $("exporter").close();await refresh();notice(`已导出 ${value.version}，共 ${value.cases} 条用例。`,`/api/datasets/${value.version}/download`,"下载数据包");
}catch(error){$("export-error").textContent=error.message;}};
$("preview-import").onclick=async()=>{try{
  const file=$("import-file").files[0];if(!file)throw new Error("请选择用例文件");
  let values;
  if(file.name.toLowerCase().endsWith(".zip")){
    const bytes=new Uint8Array(await file.arrayBuffer());let text="";for(let i=0;i<bytes.length;i+=32768)text+=String.fromCharCode(...bytes.subarray(i,i+32768));
    values=(await api("/api/import/archive",{archive_base64:btoa(text)})).cases;
  }else{const text=await file.text();values=text.trim().startsWith("[")?JSON.parse(text):file.name.endsWith(".jsonl")?text.split(/\r?\n/).filter(s=>s.trim()).map(JSON.parse):JSON.parse(text).cases;}
  const preview=await api("/api/import/preview",{revision:state.revision,cases:values});importing={cases:values,digest:preview.digest};
  $("import-preview").textContent=`新增 ${preview.new} 条，更新 ${preview.updated} 条，重复跳过 ${preview.unchanged} 条。`;
  $("confirm-import").disabled=false;$("import-error").textContent="";
}catch(error){$("import-error").textContent=error.message;$("confirm-import").disabled=true;}};
$("import-file").onchange=()=>{$("confirm-import").disabled=true;importing=null;$("import-preview").textContent="";};
$("import-form").onsubmit=async(event)=>{event.preventDefault();try{
  await api("/api/import",{revision:state.revision,...importing,reason:$("import-reason").value});$("importer").close();await refresh();notice("用例已合并到本地工作区。");
}catch(error){$("import-error").textContent=error.message;}};
$("run-regrade").onclick=async()=>{const button=$("run-regrade");button.disabled=true;try{
  const value=await api("/api/regrade",{version:$("regrade-version").value});$("regrade-result").replaceChildren();
  for(const [name,result] of Object.entries(value.reports))$("regrade-result").append(node("p",`${name==="before"?"改动前":"改动后"}：可复用 ${result.cached_rows} 条输出，检查通过 ${result.passed.numerator} 条，${result.pending.length} 条用例需重跑。`));
  const link=node("a","下载两侧重算报告");link.href=`/api/regrades/${value.run_id}/download`;$("regrade-result").append(link,node("p","本次新增模型调用：0。耗时和用量来自原始运行，不是本次新增。","hint"));
}catch(error){$("regrade-result").textContent=error.message;}finally{button.disabled=false;}};
for(const button of document.querySelectorAll("[data-close]"))button.onclick=()=>$(button.dataset.close).close();
for(const button of document.querySelectorAll("[data-view]"))button.onclick=()=>{view=button.dataset.view;render();};
for(const button of document.querySelectorAll("[data-tab]"))button.onclick=()=>editorTab(button.dataset.tab);
$("new-case").onclick=()=>openEditor("new");$("edit-case").onclick=()=>openEditor("edit");$("clone-case").onclick=()=>openEditor("clone");
$("archive-case").onclick=async()=>{const reason=prompt("请填写归档原因（原始用例保留）");if(!reason)return;try{await api(`/api/cases/${selected}/archive`,{revision:state.revision,reason});$("filter").value="archived";await refresh();}catch(error){notice(error.message);}};
$("search").oninput=render;$("filter").onchange=render;
$("export-dataset").onclick=()=>{$("export-error").textContent="";$("version-name").value="v"+new Date().toISOString().slice(0,10).replaceAll("-","")+"-"+Date.now().toString(36).slice(-4);$("version-reason").value="";$("exporter").showModal();};
$("import-cases").onclick=()=>{$("importer").showModal();};
$("legacy-reviews").onclick=async()=>{try{
  const ratings=JSON.parse(localStorage.getItem(state.legacy_storage_key)||"{}");
  const value=await api("/api/reviews/import-legacy",{revision:state.revision,ratings});
  await refresh();notice(`已保存 ${value.imported} 条旧评分到本地文件，原浏览器记录仍保留。`);
}catch(error){notice(error.message);}};
$("regrade").onclick=()=>{
  $("regrade-version").replaceChildren();for(const v of state.versions.toReversed()){const option=node("option",v.version);option.value=v.version;$("regrade-version").append(option);}
  $("run-regrade").disabled=!state.versions.length;$("regrade-result").textContent=state.versions.length?"":"请先导出一个数据集版本。";$("regrader").showModal();
};
refresh().catch(error=>notice(error.message));
