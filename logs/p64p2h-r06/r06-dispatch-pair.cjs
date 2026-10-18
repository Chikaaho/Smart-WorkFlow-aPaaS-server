// P64 回执06 P2-04a：受控分配对象建立——1 父记录（2 行 owner=jia/yi）→ 运维通过 → 2 子派发
// 子记录经 submitOnBehalfOf 落库 create_by=被分配办理人（P2-04a 接缝）；
// 子任务分别派给 jia/yi。仅建对象与身份取证；办理/探针由后续脚本执行，取证后清理。
const fs = require('fs');
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const OUT = LOGS + '/p64p2h-r06';
const BASE = 'http://127.0.0.1:8080/api';
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
(async () => {
  const actors = JSON.parse(fs.readFileSync(LOGS + '/p64p2-actors.json', 'utf8'));
  const admin = fs.readFileSync(LOGS + '/p64p2h-r06-token-admin.txt', 'utf8').trim();
  const jiaId = actors.jiaId, yiId = actors.yiId;

  // 1. 父记录（2 行来源，负责人=jia/yi）
  const rec = await api(admin, 'POST', '/form/data/p64p2b_parent_form', {
    title: 'R06分配对象P2-04a',
    hosts: [
      { host_name: 'jia', owner: jiaId, feedback: '' },
      { host_name: 'yi', owner: yiId, feedback: '' },
    ],
  });
  step('1 parent submit', { code: rec.json.code, id: rec.json.data });
  if (rec.json.code !== 0) throw new Error('submit parent failed: ' + rec.json.msg);
  const recordId = String(rec.json.data && (rec.json.data.id || rec.json.data));
  await sleep(5000);

  // 2. admin 待办中找运维填报任务
  const todo = await api(admin, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
  const rows = (todo.json.data && (todo.json.data.records || todo.json.data.list)) || [];
  const opsRow = rows.find((t) => t.businessKey === recordId);
  step('2 ops task', { found: !!opsRow, taskId: opsRow && (opsRow.taskId || opsRow.id) });
  if (!opsRow) throw new Error('ops task not found');
  const opsTaskId = String(opsRow.taskId || opsRow.id);

  // 3. 运维节点表单必填填充 → 通过（触发 NODE_ROUND_COMPLETED 派发）
  const nf = await api(admin, 'GET', '/workflow/tasks/' + opsTaskId + '/node-form');
  const nodeFormData = {};
  if (nf.json.code === 0 && nf.json.data && nf.json.data.definition) {
    for (const f of (nf.json.data.definition.fields || [])) {
      if (f.type === 'TABLE') continue;
      nodeFormData[f.name] = f.type === 'NUMBER' ? 1 : 'R06-' + (f.label || f.name);
    }
  }
  const done = await api(admin, 'POST', '/workflow/commands/tasks/' + opsTaskId + '/complete', {
    action: 'APPROVE', nodeFormData,
  });
  step('3 ops complete', { code: done.json.code, msg: done.json.msg });
  if (done.json.code !== 0) throw new Error('complete failed: ' + done.json.msg);
  await sleep(7000); // 触发→派发→子记录/子实例/子任务

  // 4. jia/yi 待办各找子任务
  const identity = { at: new Date().toISOString(), parentRecordId: recordId, opsTaskId, children: {} };
  for (const [user, uid] of [['jia', jiaId], ['yi', yiId]]) {
    const token = fs.readFileSync(LOGS + '/p64p2h-r06-token-' + user + '.txt', 'utf8').trim();
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

  // 5. 实例身份（父+2子）
  const instQ = await api(admin, 'POST', '/workflow/instances/query', { pageNum: 1, pageSize: 20, filters: [] });
  const instRows = (instQ.json.data && (instQ.json.data.records || instQ.json.data.list)) || [];
  const mine = instRows.filter((i) => i.businessKey === recordId
    || Object.values(identity.children).some((c) => c.recordId === String(i.businessKey)))
    .map((i) => ({ id: i.processInstanceId, bk: i.businessKey, status: i.status, defVersion: i.defVersion }));
  step('5 instances', { mine });
  identity.instances = mine;

  // 6. 批次身份
  const batches = await api(admin, 'GET', '/workflow/instances/' + (mine.find((i) => i.bk === recordId) || {}).id + '/child-batches');
  step('6 batches', { code: batches.json.code, count: batches.json.data && batches.json.data.length });
  identity.batches = batches.json.data;

  fs.writeFileSync(OUT + '/06-dispatch-pair-identity.json', JSON.stringify({ ...identity, steps }, null, 2));
  console.log('SAVED identity');
})().catch((e) => { console.error('FATAL', e.message); process.exit(1); });
