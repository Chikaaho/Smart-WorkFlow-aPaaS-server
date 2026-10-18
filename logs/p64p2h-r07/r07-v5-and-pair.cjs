// P64 回执07 P2-04a：最小使能——v5(=v3图原样)发布恢复派发触发器 + v4中途实例收敛 + 新最小派发对
// 背景：P2-06b 已发布 v4（去触发器），新实例不再派发（07-dispatch-attempt1 留痕）。
// 本脚本：(1) admin 收敛 ce4df356(v4 复核) → APPROVED；(2) 发布 v5= v3 图原样（trg_child 恢复）；
//         (3) 新父记录 1 条（2 行 owner=jia/yi）→ 运维通过 → 2 子派发；登记全部新旧 ID。
const fs = require('fs');
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const OUT = LOGS + '/p64p2h-r07';
const BASE = 'http://127.0.0.1:8080/api';
const PARENT_DEF_ID = '2108817690357526529';
const OLD_RECORD = 'ce4df356-eaba-4817-b47c-18187e97d47c'; // v4 中途实例（无触发器，仅复核待办）
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const steps = [];
function step(name, data) { steps.push({ name, data }); console.log(name, JSON.stringify(data).slice(0, 300)); }
async function api(token, method, path, bodyObj) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: bodyObj ? JSON.stringify(bodyObj) : undefined,
  });
  const text = await res.text();
  let json = {}; try { json = JSON.parse(text); } catch { json = { raw: text.slice(0, 300) }; }
  return { status: res.status, json };
}
async function findTask(admin, recordId) {
  const todo = await api(admin, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
  const rows = (todo.json.data && (todo.json.data.records || todo.json.data.list)) || [];
  return rows.find((t) => t.businessKey === recordId);
}
async function fillAndComplete(token, task, prefix) {
  const taskId = String(task.taskId || task.id);
  const nf = await api(token, 'GET', '/workflow/tasks/' + taskId + '/node-form');
  const nodeFormData = {};
  for (const f of ((nf.json.data && nf.json.data.definition && nf.json.data.definition.fields) || [])) {
    if (f.type === 'TABLE') continue;
    nodeFormData[f.name] = f.type === 'NUMBER' ? 1 : prefix + '-' + (f.label || f.name);
  }
  return api(token, 'POST', '/workflow/commands/tasks/' + taskId + '/complete', { action: 'APPROVE', nodeFormData });
}
(async () => {
  const actors = JSON.parse(fs.readFileSync(LOGS + '/p64p2-actors.json', 'utf8'));
  const admin = fs.readFileSync(LOGS + '/p64p2h-r07-token-admin.txt', 'utf8').trim();
  const jiaId = actors.jiaId, yiId = actors.yiId;
  const result = { at: new Date().toISOString(), parentDefId: PARENT_DEF_ID, oldRecordId: OLD_RECORD };

  // ===== 1. v4 中途实例收敛（复核 APPROVE → APPROVED） =====
  const rv = await findTask(admin, OLD_RECORD);
  if (rv) {
    const done = await fillAndComplete(admin, rv, 'R07V4R');
    step('1 v4 leftover review complete', { taskId: rv.taskId || rv.id, code: done.json.code, msg: done.json.msg });
    result.v4LeftoverReview = { taskId: String(rv.taskId || rv.id), code: done.json.code, msg: done.json.msg };
    await sleep(5000);
  } else { step('1 v4 leftover review task not found', {}); result.v4LeftoverReview = { found: false }; }

  // ===== 2. 发布 v5 = v3 图原样（触发器恢复；v4 历史事实不动） =====
  const v3Graph = JSON.parse(fs.readFileSync(OUT + '/07-v3-graph.json', 'utf8'));
  const save = await api(admin, 'PUT', '/workflow/defs/' + PARENT_DEF_ID + '/graph', v3Graph);
  step('2a v5 draft saved (=v3 graph)', { code: save.json.code, msg: save.json.msg });
  if (save.json.code !== 0) throw new Error('draft save failed: ' + save.json.msg);
  const pub = await api(admin, 'POST', '/workflow/defs/' + PARENT_DEF_ID + '/publish');
  step('2b publish v5', { code: pub.json.code, msg: pub.json.msg, data: pub.json.data });
  if (pub.json.code !== 0) throw new Error('publish failed: ' + pub.json.msg);
  result.v5Publish = { savedGraph: { elements: v3Graph.elements.length, triggers: v3Graph.triggers }, publish: pub.json.data };
  fs.writeFileSync(OUT + '/07-v5-publish.json', JSON.stringify(result, null, 2));
  await sleep(2000);

  // ===== 3. 新最小派发对（1 父记录 2 行 owner=jia/yi） =====
  const rec = await api(admin, 'POST', '/form/data/p64p2b_parent_form', {
    title: 'R07分配对象P2-04a',
    hosts: [
      { host_name: 'jia', owner: jiaId, feedback: '' },
      { host_name: 'yi', owner: yiId, feedback: '' },
    ],
  });
  step('3a parent submit', { code: rec.json.code, id: rec.json.data });
  if (rec.json.code !== 0) throw new Error('submit parent failed: ' + rec.json.msg);
  const recordId = String(rec.json.data && (rec.json.data.id || rec.json.data));
  await sleep(5000);
  const opsRow = await findTask(admin, recordId);
  if (!opsRow) throw new Error('ops task not found');
  const opsTaskId = String(opsRow.taskId || opsRow.id);
  const done = await fillAndComplete(admin, opsRow, 'R07O');
  step('3b ops complete', { code: done.json.code, msg: done.json.msg });
  if (done.json.code !== 0) throw new Error('complete failed: ' + done.json.msg);
  await sleep(8000);

  // ===== 4. 子任务身份（jia/yi 各 1） =====
  const identity = { at: new Date().toISOString(), parentRecordId: recordId, opsTaskId, children: {} };
  for (const [user, uid] of [['jia', jiaId], ['yi', yiId]]) {
    const token = fs.readFileSync(LOGS + '/p64p2h-r07-token-' + user + '.txt', 'utf8').trim();
    const td = await api(token, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
    const trows = (td.json.data && (td.json.data.records || td.json.data.list)) || [];
    const childRow = trows.find((t) => (t.processName || '').includes('子反馈'));
    step('4 ' + user + ' child task', {
      found: !!childRow, taskId: childRow && (childRow.taskId || childRow.id),
      bk: childRow && childRow.businessKey,
    });
    if (childRow) identity.children[user] = {
      taskId: String(childRow.taskId || childRow.id),
      recordId: String(childRow.businessKey || ''),
    };
  }
  if (!identity.children.yi || !identity.children.jia) throw new Error('child tasks missing');

  // ===== 5. 实例+批次身份 =====
  const instQ = await api(admin, 'POST', '/workflow/instances/query', { pageNum: 1, pageSize: 20, filters: [] });
  const instRows = (instQ.json.data && (instQ.json.data.records || instQ.json.data.list)) || [];
  const mine = instRows.filter((i) => i.businessKey === recordId
    || Object.values(identity.children).some((c) => c.recordId === String(i.businessKey)))
    .map((i) => ({ id: i.processInstanceId, bk: i.businessKey, status: i.status, defVersion: i.defVersion }));
  identity.instances = mine;
  const parentInst = mine.find((i) => i.bk === recordId) || {};
  const batches = await api(admin, 'GET', '/workflow/instances/' + parentInst.id + '/child-batches');
  identity.batches = batches.json.data;
  step('5 identity', { mine, batchCount: (batches.json.data || []).length });

  fs.writeFileSync(OUT + '/07-dispatch-pair-identity.json', JSON.stringify({ ...identity, result, steps }, null, 2));
  console.log('SAVED identity');
})().catch((e) => { console.error('FATAL', e.message); process.exit(1); });
