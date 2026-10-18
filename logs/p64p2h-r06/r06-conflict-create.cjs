// P64 回执06 P2-02b-V-C 阶段A：制造真实未恢复冲突（挂起待处置）——不动恢复
// 父记录 → 运维通过 → 子派发 → 父主记录 PUT（版本抬升）→ 张三带授权行完成子任务
// → item=CONFLICT / batch=BLOCKED（挂起）；恢复由阶段B脚本执行
const fs = require('fs');
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const OUT = LOGS + '/p64p2h-r06';
const BASE = 'http://127.0.0.1:8080/api';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const steps = [];
function step(name, data) { steps.push({ name, data }); console.log(name, JSON.stringify(data).slice(0, 260)); }
async function api(token, method, path, bodyObj) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: bodyObj ? JSON.stringify(bodyObj) : undefined,
  });
  const text = await res.text();
  let json = {}; try { json = JSON.parse(text); } catch { json = { raw: text.slice(0, 400) }; }
  return { status: res.status, json };
}
(async () => {
  const actors = JSON.parse(fs.readFileSync(LOGS + '/p64p2-actors.json', 'utf8'));
  const admin = fs.readFileSync(LOGS + '/p64p2h-r06-token-admin.txt', 'utf8').trim();
  const zs = fs.readFileSync(LOGS + '/p64p2h-r06-token-zhangsan.txt', 'utf8').trim();

  const rec = await api(admin, 'POST', '/form/data/p64p2b_parent_form', {
    title: 'R06冲突挂起像素P2-02b-VC',
    hosts: [{ host_name: 'host-r06-p1', owner: actors.zhangsanId, feedback: '' }],
  });
  step('1 parent submit', { code: rec.json.code, id: rec.json.data });
  const recordId = String(rec.json.data && (rec.json.data.id || rec.json.data));
  await sleep(5000);

  const todo = await api(admin, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
  const rows = (todo.json.data && (todo.json.data.records || todo.json.data.list)) || [];
  const opsRow = rows.find((t) => t.businessKey === recordId);
  const opsTaskId = String(opsRow.taskId || opsRow.id);
  const nf = await api(admin, 'GET', '/workflow/tasks/' + opsTaskId + '/node-form');
  const nodeFormData = {};
  for (const f of ((nf.json.data && nf.json.data.definition && nf.json.data.definition.fields) || [])) {
    if (f.type === 'TABLE') continue;
    nodeFormData[f.name] = f.type === 'NUMBER' ? 1 : 'R06P-' + (f.label || f.name);
  }
  const done = await api(admin, 'POST', '/workflow/commands/tasks/' + opsTaskId + '/complete',
    { action: 'APPROVE', nodeFormData });
  step('2 ops complete', { code: done.json.code, msg: done.json.msg });
  await sleep(10000); // 派发消费含队列延迟

  const ztodo = await api(zs, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
  const zrows = (ztodo.json.data && (ztodo.json.data.records || ztodo.json.data.list)) || [];
  const childRow = zrows.find((t) => (t.processName || '').includes('子反馈'));
  if (!childRow) throw new Error('zhangsan child task not found');
  const childTaskId = String(childRow.taskId || childRow.id);
  const childRecordId = String(childRow.businessKey || '');
  step('3 child task', { taskId: childTaskId, recordId: childRecordId });

  const cur = await api(admin, 'GET', '/form/data/p64p2b_parent_form/' + recordId);
  const put = await api(admin, 'PUT', '/form/data/p64p2b_parent_form/' + recordId, {
    data: { title: 'R06冲突挂起像素（编辑后）' },
    version: Number((cur.json.data && cur.json.data.version) || 0),
    subTableRows: {},
  });
  step('4 parent PUT', { code: put.json.code, msg: put.json.msg });

  const cnf = await api(zs, 'GET', '/workflow/tasks/' + childTaskId + '/node-form');
  const childRec = await api(zs, 'GET', '/form/data/p64p2b_child_form/' + childRecordId);
  const tfield = ((cnf.json.data && cnf.json.data.definition && cnf.json.data.definition.fields) || [])
    .find((f) => f.type === 'TABLE');
  const srcRows = (childRec.json.data && childRec.json.data[tfield.name]) || [];
  const childFormData = { summary: 'R06P-FB' };
  childFormData[tfield.name] = srcRows.map((r) => ({ src_row_id: r.src_row_id, feedback: 'R06P-FB-OK' }));
  const cdone = await api(zs, 'POST', '/workflow/commands/tasks/' + childTaskId + '/complete',
    { action: 'APPROVE', nodeFormData: childFormData });
  step('5 child complete', { code: cdone.json.code, msg: cdone.json.msg });
  await sleep(12000);

  const td = await api(admin, 'GET', '/workflow/tasks/' + opsTaskId);
  const parentInstId = td.json.data && (td.json.data.processInstanceId || (td.json.data.instance && td.json.data.instance.processInstanceId));
  const batch = await api(admin, 'GET', '/workflow/instances/' + parentInstId + '/child-batches');
  const b = (batch.json.data || [])[0];
  step('6 pending conflict', { batchStatus: b && b.status, blockReason: b && b.blockReason,
    items: b && b.items.map((i) => ({ id: i.id, status: i.status })) });
  if (!b || b.status !== 'BLOCKED') throw new Error('expected BLOCKED batch with CONFLICT item');

  fs.writeFileSync(OUT + '/06-conflict-pending-identity.json', JSON.stringify({
    at: new Date().toISOString(), parentRecordId: recordId, opsTaskId, childTaskId,
    childRecordId, parentInstanceId: parentInstId, pendingBatch: batch.json.data, steps,
  }, null, 2));
  console.log('SAVED pending identity (leave unrecovered for pixel)');
})().catch((e) => { console.error('FATAL', e.message); process.exit(1); });
