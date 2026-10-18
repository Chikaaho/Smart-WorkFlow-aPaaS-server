// P64 阶段Ⅱ回执03：A07 聚合下一会签实机链 v2（NODE_FORM_AGGREGATE 并集去重 → CONSENSUS 会签）
// 场景：node_a（APPROVAL 候选=甲/乙）→ node_b（APPROVAL 候选=丙）→ node_agg（CONSENSUS，
//       participant=NODE_FORM_AGGREGATE{node_a, handlers, CURRENT}）→ end
// 断言：node_agg 恰为 丁+丙 两会签任务（甲填 [丁,丙,丁] 显式重复→去重；丙在 node_b 填 [甲] 为负控→甲不得出现）
const fs = require('fs');
const BASE = 'http://127.0.0.1:8080/api';
const OUT = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const EV = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-03/raw/live';
const actors = JSON.parse(fs.readFileSync(OUT + '/p64p2-actors.json', 'utf8'));
const tokenOf = (u) => fs.readFileSync(OUT + '/p64p2-token-' + u + '.txt', 'utf8').trim();

function safeParse(text) {
  const quoted = text.replace(/([:\[,]\s*)(-?\d{16,})(?=\s*[,\}\]])/g, '$1"$2"');
  return JSON.parse(quoted);
}
async function api(token, method, path, body) {
  const r = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await r.text();
  let json = {};
  try { json = safeParse(text); } catch { json = { raw: text.slice(0, 300) }; }
  return { status: r.status, json };
}
function must(label, r) {
  if (r.json.code !== 0) throw new Error(label + ' -> ' + JSON.stringify(r.json).slice(0, 300));
  return r.json.data;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const FORM = 'p64p2h_agg_form';
const DEF_NAME = 'P64H聚合会签验证流程';
const INSTANCE_OF = async (user) => {
  const r = await api(tokenOf(user), 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
  const rows = (r.json.data && (r.json.data.records || r.json.data.list)) || [];
  return rows.filter((t) => (t.processName || '') === DEF_NAME);
};

async function ensureForm(admin) {
  const page = await api(admin, 'GET', '/form/def/page?pageNum=1&pageSize=50');
  const rows = (page.json.data && (page.json.data.records || page.json.data.list)) || [];
  let id;
  const found = rows.find((x) => x.formKey === FORM);
  if (found) id = String(found.id);
  else {
    const c = must('create form', await api(admin, 'POST', '/form/def', { formKey: FORM, name: 'P64H聚合会签表单', logicalTableName: FORM, description: 'P64阶段Ⅱ回执03 聚合验证' }));
    id = String(typeof c === 'object' ? (c.id ?? c.defId) : c);
  }
  if (!(found && found.status === 'PUBLISHED')) {
    must('saveConfig', await api(admin, 'POST', '/form/def/' + id + '/config', {
      definition: JSON.stringify({
        title: 'P64H聚合会签表单',
        fields: [
          { name: 'subject', type: 'TEXT', label: '事项' },
          { name: 'handlers', type: 'USER', label: '下一轮会签人', multiple: true },
        ],
      }),
    }));
    must('publish form', await api(admin, 'POST', '/form/def/' + id + '/publish'));
  }
  return id;
}

async function main() {
  const admin = tokenOf('admin');
  const trace = { form: null, def: null, steps: [] };
  trace.form = await ensureForm(admin);

  // def：更新既有定义的图（含 CONSENSUS node_agg）并发布新版本
  const page = await api(admin, 'GET', '/workflow/defs?pageNum=1&pageSize=100');
  const rows = (page.json.data && (page.json.data.records || page.json.data.list)) || [];
  const found = rows.find((d) => d.name === DEF_NAME);
  const defId = String(found.id); const key = found.processKey;
  const graph = {
    processKey: key, name: DEF_NAME, formKey: FORM,
    elements: [
      { id: 'start', kind: 'node', type: 'START', config: { name: '开始' } },
      { id: 'node_a', kind: 'node', type: 'APPROVAL', config: { name: 'A岗会签填报', participant: { strategy: 'FIXED_USER', value: [actors.jiaId, actors.yiId] }, nodeForm: { formKey: FORM } } },
      { id: 'node_b', kind: 'node', type: 'APPROVAL', config: { name: 'B岗填报', participant: { strategy: 'FIXED_USER', value: actors.bingId }, nodeForm: { formKey: FORM } } },
      { id: 'node_agg', kind: 'node', type: 'CONSENSUS', config: { name: '聚合会签', mode: 'ALL', participant: { strategy: 'NODE_FORM_AGGREGATE', value: { nodeKey: 'node_a', formField: 'handlers', round: 'CURRENT' } }, nodeForm: { formKey: FORM } } },
      { id: 'end', kind: 'node', type: 'END', config: { name: '结束' } },
      { id: 'e1', kind: 'edge', source: 'start', target: 'node_a' },
      { id: 'e2', kind: 'edge', source: 'node_a', target: 'node_b' },
      { id: 'e3', kind: 'edge', source: 'node_b', target: 'node_agg' },
      { id: 'e4', kind: 'edge', source: 'node_agg', target: 'end' },
    ],
    variables: [], triggers: [],
  };
  must('save graph', await api(admin, 'PUT', '/workflow/defs/' + defId + '/graph', graph));
  const pub = must('publish def', await api(admin, 'POST', '/workflow/defs/' + defId + '/publish'));
  trace.def = { defId, key: pub.processKey || key };
  console.log('def=' + JSON.stringify(trace.def));
  await sleep(1000);

  // 0) 清理本定义既有 RUNNING 实例（自身验证遗留，防待办污染计数）
  const inst0 = await api(admin, 'POST', '/workflow/instances/page?pageNum=1&pageSize=50', {});
  const irows0 = (inst0.json.data && (inst0.json.data.records || inst0.json.data.list)) || [];
  for (const x of irows0.filter((v) => v.status === 'RUNNING' && String(v.processDefKey || '') === trace.def.key)) {
    await api(admin, 'POST', '/workflow/instances/' + String(x.processInstanceId) + '/discard', {});
  }
  await sleep(2000);

  // 1) 发起 → node_a 候选任务（甲/乙）
  const rec = await api(admin, 'POST', '/form/data/' + FORM, { subject: '回执03聚合下一会签v2' });
  if (rec.json.code !== 0) throw new Error('submit: ' + JSON.stringify(rec.json).slice(0, 250));
  const recordId = rec.json.data && (rec.json.data.id || rec.json.data.recordId) ? String(rec.json.data.id || rec.json.data.recordId) : null;
  await sleep(4000);
  const jia1 = await INSTANCE_OF('jia');
  const yi1 = await INSTANCE_OF('yi');
  trace.steps.push({ step: '1-node_a-candidate-task', recordId, jia: jia1, yi: yi1 });
  console.log('step1 jia=' + JSON.stringify(jia1.map((t) => t.taskId)) + ' yi=' + JSON.stringify(yi1.map((t) => t.taskId)));
  const aTask = (jia1[0] || yi1[0]).taskId;
  if (!jia1.length || !yi1.length || jia1[0].taskId !== yi1[0].taskId) throw new Error('node_a shared candidate task mismatch');
  const aDetail = (await api(admin, 'GET', '/workflow/tasks/' + aTask)).json.data || {};
  const curInst = String(aDetail.processInstanceId || '');
  if (!curInst) throw new Error('cannot resolve current instance id');
  const onCur = (t) => String(t.processInstanceId || '') === curInst;

  // 2) 甲办理 node_a：handlers=[丁,丙,丁]（显式重复 → 聚合须去重）
  const c1 = await api(tokenOf('jia'), 'POST', '/workflow/tasks/' + aTask + '/complete', { action: 'APPROVE', comment: '回执03聚合链-A', nodeFormData: { handlers: [actors.dingId, actors.bingId, actors.dingId] } });
  trace.steps.push({ step: '2-node_a-complete-by-jia', taskId: aTask, result: { code: c1.json.code, msg: c1.json.msg || null } });
  console.log('step2 complete=' + c1.json.code);
  await sleep(4000);

  // 3) node_b：丙填报 handlers=[甲]（负控：甲不得进入聚合源）
  const bing1 = await INSTANCE_OF('bing');
  trace.steps.push({ step: '3-node_b-task', bing: bing1 });
  if (!bing1.length) throw new Error('node_b task missing');
  const c3 = await api(tokenOf('bing'), 'POST', '/workflow/tasks/' + bing1[0].taskId + '/complete', { action: 'APPROVE', comment: '回执03聚合链-B', nodeFormData: { handlers: [actors.jiaId] } });
  trace.steps.push({ step: '3-node_b-complete', taskId: bing1[0].taskId, result: { code: c3.json.code, msg: c3.json.msg || null } });
  console.log('step3 complete=' + c3.json.code);
  await sleep(5000);

  // 4) 聚合读回：丁/丙各恰 1 个会签任务；甲/乙 0 个
  const ding2 = await INSTANCE_OF('ding');
  const bing2raw = await INSTANCE_OF('bing');
  const jia2 = await INSTANCE_OF('jia');
  const yi2 = await INSTANCE_OF('yi');
  const nodeOf = async (taskId, user) => {
    const d = await api(tokenOf(user), 'GET', '/workflow/tasks/' + taskId);
    return { taskId, taskName: d.json.data && d.json.data.taskName, assignee: d.json.data && d.json.data.assignee != null ? String(d.json.data.assignee) : null, processInstanceId: d.json.data && d.json.data.processInstanceId };
  };
  const dingAgg = [];
  for (const t of ding2) dingAgg.push(await nodeOf(t.taskId, 'ding'));
  const bingAgg = [];
  for (const t of bing2raw) bingAgg.push(await nodeOf(t.taskId, 'bing'));
  trace.steps.push({ step: '4-aggregate-consensus-tasks', currentInstance: curInst, ding: dingAgg, bing: bingAgg, jia_should_be_empty: jia2.map((t) => t.taskId), yi_should_be_empty: yi2.map((t) => t.taskId) });
  console.log('step4 ding=' + JSON.stringify(dingAgg) + ' bing=' + JSON.stringify(bingAgg) + ' jia=' + jia2.length + ' yi=' + yi2.length);
  const dingConsensus = dingAgg.filter((t) => t.taskName === '聚合会签' && onCur(t));
  const bingConsensus = bingAgg.filter((t) => t.taskName === '聚合会签' && onCur(t));
  const jiaCur = [];
  for (const t of jia2) { const d = await nodeOf(t.taskId, 'jia'); if (onCur(d)) jiaCur.push(d); }
  const yiCur = [];
  for (const t of yi2) { const d = await nodeOf(t.taskId, 'yi'); if (onCur(d)) yiCur.push(d); }
  if (dingConsensus.length !== 1 || bingConsensus.length !== 1 || jiaCur.length !== 0 || yiCur.length !== 0) throw new Error('aggregate union/dedup mismatch: ding=' + dingConsensus.length + ' bing=' + bingConsensus.length + ' jia=' + jiaCur.length + ' yi=' + yiCur.length);

  // 5) 会签收敛：丁、丙各完成 → 实例 APPROVED
  const d = await api(tokenOf('ding'), 'POST', '/workflow/tasks/' + dingConsensus[0].taskId + '/complete', { action: 'APPROVE', comment: '聚合会签-丁' });
  const b = await api(tokenOf('bing'), 'POST', '/workflow/tasks/' + bingConsensus[0].taskId + '/complete', { action: 'APPROVE', comment: '聚合会签-丙' });
  trace.steps.push({ step: '5-consensus-complete', ding: { code: d.json.code, msg: d.json.msg || null }, bing: { code: b.json.code, msg: b.json.msg || null } });
  await sleep(3000);
  const inst = await api(admin, 'POST', '/workflow/instances/page?pageNum=1&pageSize=20', {});
  const irows = (inst.json.data && (inst.json.data.records || inst.json.data.list)) || [];
  const mine = irows.find((x) => x.businessKey === recordId);
  trace.steps.push({ step: '5-instance-final', instance: mine ? { id: String(mine.id || mine.processInstanceId), status: mine.status, businessKey: recordId } : null });
  console.log('final instance=' + JSON.stringify(trace.steps[trace.steps.length - 1].instance));

  fs.writeFileSync(EV + '/05-aggregate-chain.json', JSON.stringify(trace, null, 2));
  console.log('AGGREGATE CHAIN DONE');
}
main().catch((e) => { console.error(String(e)); process.exit(1); });
