"""Build an offline paired review page from saved reports, without model calls."""

import argparse
import hashlib
import json
import random
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "services/agent-service"))

from evals.dataset import fingerprint, load  # noqa: E402
from evals.fixtures import context_for  # noqa: E402


def build(before_path, after_path, output, dataset):
    before = json.loads(before_path.read_text())
    after = json.loads(after_path.read_text())
    for field in ["dataset_hash", "fixtures_hash", "scorer_hash", "retrieval"]:
        if before["metadata"][field] != after["metadata"][field]:
            raise ValueError("REVIEW_REPORTS_NOT_COMPARABLE")
    for report in [before, after]:
        if report["metadata"]["status"] != "completed":
            raise ValueError("REVIEW_REQUIRES_COMPLETED_REPORTS")
        if hashlib.sha256(dataset.read_bytes()).hexdigest() != report["metadata"]["dataset_hash"]:
            raise ValueError("REVIEW_DATASET_MISMATCH")
        fixture_hash = hashlib.sha256((ROOT / "evals/fixtures.py").read_bytes()).hexdigest()
        if fixture_hash != report["metadata"]["fixtures_hash"]:
            raise ValueError("REVIEW_FIXTURE_MISMATCH")
        if any(
            fingerprint(row["result"]) != row["result_fingerprint"] for row in report["results"]
        ):
            raise ValueError("REVIEW_RESULT_CHANGED")
    old = {(r["case_id"], r["repeat"]): r for r in before["results"]}
    current_keys = {(r["case_id"], r["repeat"]) for r in after["results"]}
    if len(old) != len(before["results"]) or len(current_keys) != len(after["results"]):
        raise ValueError("REVIEW_DUPLICATE_CASE")
    if set(old) != current_keys:
        raise ValueError("REVIEW_UNPAIRED_CASE")
    cases = {c.id: c for c in load(dataset)}
    pairs = []
    for row in after["results"]:
        key = (row["case_id"], row["repeat"])
        if key not in old:
            raise ValueError("REVIEW_UNPAIRED_CASE")
        case = cases[row["case_id"]]
        if any(
            record["question"] != case.question or record["expected"] != case.expected.model_dump()
            for record in [row, old[key]]
        ):
            raise ValueError("REVIEW_INPUT_CHANGED")
        context = context_for(case)
        pairs.append(
            {
                "id": row["case_id"],
                "repeat": row["repeat"],
                "scenario": row["scenario"],
                "question": row["question"],
                "risk": row["expected"].get("risk", False),
                "context": {
                    k: context.get(k)
                    for k in (
                        "history",
                        "product_context",
                        "order_reference",
                        "platform_product_facts",
                        "knowledge",
                    )
                },
                "expected": row["expected"],
                "before": old[key],
                "after": row,
                "regressed": old[key]["score"]["passed"] and not row["score"]["passed"],
            }
        )
    rng = random.Random(20261109)
    sampled = set()
    for scenario in sorted({p["scenario"] for p in pairs}):
        passed = [p for p in pairs if p["scenario"] == scenario and p["after"]["score"]["passed"]]
        if passed:
            p = rng.choice(passed)
            sampled.add((p["id"], p["repeat"]))
    for pair in pairs:
        pair["sample"] = (pair["id"], pair["repeat"]) in sampled
    payload = {
        "pairs": pairs,
        "storage_key": "pdd-eval-review-"
        + hashlib.sha256((before_path.read_bytes() + after_path.read_bytes())).hexdigest(),
    }
    encoded = json.dumps(payload, ensure_ascii=False)
    for character, replacement in [
        ("<", "\\u003c"),
        (">", "\\u003e"),
        ("&", "\\u0026"),
        ("\u2028", "\\u2028"),
        ("\u2029", "\\u2029"),
    ]:
        encoded = encoded.replace(character, replacement)
    if output.exists():
        raise ValueError("REVIEW_OUTPUT_ALREADY_EXISTS")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(PAGE.replace("__REVIEW_DATA__", encoded))
    output.chmod(0o600)
    return {
        "pairs": len(pairs),
        "failed": sum(not p["after"]["score"]["passed"] for p in pairs),
        "regressed": sum(p["regressed"] for p in pairs),
        "sampled_passes": len(sampled),
    }


PAGE = r"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'none'; base-uri 'none'; form-action 'none'">
<title>Agent 评测复核</title><style>
*{box-sizing:border-box}body{margin:0;background:#f5f5f7;color:#202124;font:14px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
header{padding:14px 22px;background:#fff;border-bottom:1px solid #ddd;display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}
h1{font-size:20px;margin:0}h2{font-size:17px;margin:0 0 10px}h3{font-size:15px;margin:12px 0 6px}p{margin:6px 0}small{color:#62666c}button,input,select,textarea{font:inherit;border:1px solid #d5d7db;border-radius:8px;padding:7px 10px;background:#fff;color:inherit}
button{cursor:pointer}button:hover{background:#f1f5ff}input,select,textarea{max-width:100%}main{display:grid;grid-template-columns:270px minmax(0,1fr);max-width:1800px;margin:auto;min-height:90vh}
aside{padding:14px;border-right:1px solid #ddd}aside input,aside select{width:100%;margin-bottom:9px}.case-list{display:grid;gap:7px;max-height:76vh;overflow:auto}.case-list button{text-align:left;overflow-wrap:anywhere}.case-list button[aria-current=true]{border-color:#3979df;background:#edf4ff}
article{padding:18px;min-width:0}.columns{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.version{min-width:0;background:#fff;border:1px solid #ddd;border-radius:12px;padding:16px;overflow-wrap:anywhere}
.answer{white-space:pre-wrap;padding:12px;background:#f7f8fa;border-radius:8px}.badge{display:inline-block;padding:1px 8px;border-radius:8px;background:#ecf7ef;color:#23653b;margin-right:6px}.failed{background:#fff0e9;color:#943b1c}
label{display:flex;align-items:center;justify-content:space-between;gap:8px;margin:8px 0}textarea{width:100%;min-height:70px;resize:vertical}.evidence{border-left:3px solid #bed0ed;padding:6px 10px;margin:8px 0;white-space:pre-wrap}
details{margin:10px 0}summary{cursor:pointer}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;background:#f7f8fa;padding:10px;border-radius:8px}ul{padding-left:20px}#notice{padding:0 22px;color:#45546d}#reference{background:#fff;padding:8px 12px;border-radius:8px}#question{white-space:pre-wrap;overflow-wrap:anywhere}
@media(max-width:1000px){main{grid-template-columns:210px minmax(0,1fr)}.columns{grid-template-columns:1fr}}
@media(max-width:650px){main{display:block}aside{border-right:0;border-bottom:1px solid #ddd}.case-list{max-height:180px}article{padding:12px}header{padding:12px}.version{padding:12px}}
</style></head><body>
<header><div><h1>Agent 评测复核</h1><small>对照问题、测试资料和实际工具依据；人工评分默认留空，本页不调用模型。</small></div>
<div><button id="export-before">导出改动前复核</button> <button id="export-after">导出改动后复核</button><p><small id="review-count"></small></p></div></header>
<p id="notice" role="status"></p><main><aside><input id="search" aria-label="搜索咨询" placeholder="搜索问题或编号">
<select id="filter" aria-label="筛选范围"><option value="failed">新版评分未通过</option><option value="regressed">前后退步</option><option value="risk">风险咨询</option><option value="sample">通过用例抽样</option><option value="all">全部咨询</option></select><div id="list" class="case-list"></div></aside>
<article><h2 id="question"></h2><small id="case-meta"></small><details id="reference"><summary>测试参考资料与预期</summary><p><small>这里是合成测试输入；“有依据”应以各版本实际工具结果为准，不能用未查询的参考资料补证据。</small></p><pre id="reference-content"></pre></details><div id="columns" class="columns"></div></article></main>
<script type="application/json" id="data">__REVIEW_DATA__</script><script>
const data=JSON.parse(document.getElementById('data').textContent),pairs=data.pairs;
let ratings={};try{ratings=JSON.parse(localStorage.getItem(data.storage_key)||'{}')}catch{}
let selected=pairs.find(p=>!p.after.score.passed)||pairs[0];
const byId=id=>document.getElementById(id),labels={draft:'待审批草稿',needs_human:'人工核实',insufficient_evidence:'资料不足'};
const toolNames={get_shop_products:'实时商品查询',get_product_skus:'规格与库存查询',get_product_shipping:'发货与运费查询',get_product_knowledge:'商品知识查询',get_order_details:'订单查询',search_customer_service_knowledge:'规则检索',transfer_conversation:'建议人工处理'};
function node(tag,text,cls){const el=document.createElement(tag);if(text!==undefined)el.textContent=text;if(cls)el.className=cls;return el}
function key(p,v){return v+':'+p.id+':'+p.repeat}
function saved(p,v){return ratings[key(p,v)]||{factually_correct:null,grounded:null,usable_without_edit:null,notes:''}}
function complete(r){return ['factually_correct','grounded','usable_without_edit'].every(k=>typeof r[k]==='boolean')}
function save(p,v,value){ratings[key(p,v)]=value;try{localStorage.setItem(data.storage_key,JSON.stringify(ratings))}catch{byId('notice').textContent='浏览器未允许本地保存，请及时导出复核文件。'}counts()}
function counts(){byId('review-count').textContent='已完整复核：改动前 '+pairs.filter(p=>complete(saved(p,'before'))).length+' 条，改动后 '+pairs.filter(p=>complete(saved(p,'after'))).length+' 条'}
function visible(){const value=byId('filter').value,q=byId('search').value.toLowerCase();return pairs.filter(p=>(value==='all'||value==='failed'&&!p.after.score.passed||value==='regressed'&&p.regressed||value==='risk'&&p.risk||value==='sample'&&p.sample)&&(!q||(p.id+' '+p.question).toLowerCase().includes(q)))}
function listing(){const list=byId('list');list.replaceChildren();const rows=visible();if(!rows.length)selected=null;if(!rows.some(p=>p===selected)&&rows.length){selected=rows[0];render()}for(const p of rows){const b=node('button',p.id+' · '+p.question);b.setAttribute('aria-current',String(p===selected));b.onclick=()=>{selected=p;listing();render()};list.append(b)}if(!rows.length)list.append(node('small','没有匹配的咨询。'))}
function detail(title,value){const d=node('details');d.append(node('summary',title),node('pre',JSON.stringify(value,null,2)));return d}
function render(){const p=selected;if(!p){byId('question').textContent='没有匹配的咨询';byId('case-meta').textContent='';byId('reference-content').textContent='';byId('columns').replaceChildren();return;}byId('question').textContent=p.question;byId('case-meta').textContent=p.id+' · 第 '+p.repeat+' 次 · '+(p.regressed?'本轮退步':'前后对照');byId('reference-content').textContent=JSON.stringify({context:p.context,expected:p.expected},null,2);byId('columns').replaceChildren();
for(const v of ['before','after']){const row=p[v],result=row.result,section=node('section',undefined,'version');section.setAttribute('aria-label',v==='before'?'改动前':'改动后');section.append(node('h2',v==='before'?'改动前':'改动后'));const badge=node('span',row.score.passed?'自动检查通过':'自动检查未通过','badge'+(row.score.passed?'':' failed'));section.append(badge,node('small',labels[result.outcome]||result.outcome),node('p',result.content||'没有可用回复','answer'));
const issues=row.score.checks.filter(c=>!c.passed).map(c=>c.name);if(issues.length)section.append(detail('评分未通过的检查',issues));section.append(detail('问题理解与草稿检查',{understanding:result.understanding,question_results:result.question_results,verification:result.verification,answered_message_ids:result.answered_message_ids}));
section.append(detail('草稿实际引用',result.citations||[]),detail('检索器召回记录',row.retrievals||[]));section.append(node('h3','实际查询与依据'));if(!row.tools.length)section.append(node('small','本版本没有执行查询工具。'));for(const t of row.tools){const d=node('details');d.append(node('summary',toolNames[t.name]||t.name));d.append(node('small','参数：'+JSON.stringify(t.effective_arguments||t.arguments)));for(const e of t.response?.evidence||[]){d.append(node('div',(e.source_id||'')+' · '+(e.source_version||'')+'\n'+e.evidence_text,'evidence'))}d.append(detail('完整工具结果',t.response));section.append(d)}
section.append(node('h3','人工复核'));for(const [field,label] of [['factually_correct','结论是否正确'],['grounded','事实是否有实际依据'],['usable_without_edit','处理结果是否可直接采用']]){const wrap=node('label',label),select=node('select');select.setAttribute('aria-label',label);for(const [value,text] of [['','未评'],['true','是'],['false','否']]){const option=node('option',text);option.value=value;select.append(option)}const value=saved(p,v)[field];select.value=typeof value==='boolean'?String(value):'';select.onchange=()=>save(p,v,{...saved(p,v),[field]:select.value===''?null:select.value==='true'});wrap.append(select);section.append(wrap)}const notes=node('textarea');notes.setAttribute('aria-label','复核说明');notes.placeholder='记录错误、评分误伤或资料缺口；不能仅按自动分数判断。';notes.value=saved(p,v).notes||'';notes.oninput=()=>save(p,v,{...saved(p,v),notes:notes.value});section.append(notes);byId('columns').append(section)}counts()}
function download(v){const rows=pairs.filter(p=>complete(saved(p,v))).map(p=>({case_id:p.id,repeat:p.repeat,result_fingerprint:p[v].result_fingerprint,...saved(p,v)}));if(!rows.length){byId('notice').textContent='尚无完整复核；请先选择三项判断。';return}const url=URL.createObjectURL(new Blob([rows.map(r=>JSON.stringify(r)).join('\n')+'\n'],{type:'application/x-ndjson'}));const a=node('a');a.href=url;a.download='generalization-'+v+'-reviews.jsonl';document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);byId('notice').textContent='已导出 '+rows.length+' 条复核。原评测分数和报告没有改变。'}
byId('filter').onchange=()=>{listing();render()};byId('search').oninput=()=>{listing();render()};byId('export-before').onclick=()=>download('before');byId('export-after').onclick=()=>download('after');listing();render();
</script></body></html>"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", type=Path, required=True)
    parser.add_argument("--after", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--dataset", type=Path, default=ROOT / "evals/agent_cases.jsonl")
    args = parser.parse_args()
    print(json.dumps(build(args.before, args.after, args.output, args.dataset), ensure_ascii=False))


if __name__ == "__main__":
    main()
