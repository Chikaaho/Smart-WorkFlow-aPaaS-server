// P64 回执05：最小派发——创建 1 个父实例（1 行来源）→ 运维填报通过 → 子派发 1 子任务（活任务）
// 仅用于 P2-02b-V 节点表单活像素补采（完结任务 node-form 404，卡不渲染）；取证后全部 discard 清理
// 不重演完整主链（无多角色/冲突/恢复/复核段）
const fs = require('fs');
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const OUT = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-05/raw/live';
const BASE = 'http://127.0.0.1:8080/api';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const steps = [];
function step(name, data) { steps.push({ name, data }); console.log(name, JSON.stringify(data).slice(0, 220)); }
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
  const admin = fs.readFileSync(LOGS + '/p64p2h-r05-token-admin.txt', 'utf8').trim();
  const zs = fs.readFileSync(LOGS + '/p64p2h-r05-token-zhangsan.txt', 'utf8').trim();
  const actors = JSON.parse(fs.readFileSync(LOGS + '/p64p2-actors.json', 'utf8'));

  // 1. 父记录（1 行来源，负责人=张三）
  const rec = await api(admin, 'POST', '/form/data/p64p2b_parent_form', {
    title: 'R05节点表单像素补采',
    hosts: [{ host_name: 'host-r05-a', owner: actors.zhangsanId, feedback: '' }],
  });
  step('1 parent submit', { code: rec.json.code, id: rec.json.data });
  if (rec.json.code !== 0) throw new Error('submit parent failed');
  const recordId = String(rec.json.data && (rec.json.data.id || rec.json.data));
  await sleep(5000);

  // 2. admin 待办中找运维填报任务
  const todo = await api(admin, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
  const rows = (todo.json.data && (todo.json.data.records || todo.json.data.list)) || [];
  const opsRow = rows.find((t) => t.businessKey === recordId);
  step('2 ops task', { found: !!opsRow, taskId: opsRow && (opsRow.taskId || opsRow.id), name: opsRow && opsRow.taskName });
  if (!opsRow) throw new Error('ops task not found');
  const opsTaskId = String(opsRow.taskId || opsRow.id);

  // 3. 读运维节点表单定义 → 填充必填字段
  const nf = await api(admin, 'GET', '/workflow/tasks/' + opsTaskId + '/node-form');
  step('3 ops node-form', { code: nf.json.code, name: nf.json.data && nf.json.data.formName });
  const nodeFormData = {};
  if (nf.json.code === 0 && nf.json.data && nf.json.data.definition) {
    for (const f of (nf.json.data.definition.fields || [])) {
      if (f.type === 'TABLE') continue; // 表格字段沿记录现值
      nodeFormData[f.name] = f.type === 'NUMBER' ? 1 : 'R05-' + (f.label || f.name);
    }
  }
  step('3b nodeFormData keys', { keys: Object.keys(nodeFormData) });

  // 4. 通过（最终提交随审批同事务）
  const done = await api(admin, 'POST', '/workflow/commands/tasks/' + opsTaskId + '/complete', {
    action: 'APPROVE', nodeFormData,
  });
  step('4 ops complete', { code: done.json.code, msg: done.json.msg });
  if (done.json.code !== 0) throw new Error('complete failed: ' + done.json.msg);
  await sleep(6000); // 触发→派发→子实例/任务

  // 5. 张三待办找子任务
  const ztodo = await api(zs, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
  const zrows = (ztodo.json.data && (ztodo.json.data.records || ztodo.json.data.list)) || [];
  const childRow = zrows.find((t) => (t.processName || '').includes('子反馈'));
  step('5 child task', { found: !!childRow, taskId: childRow && (childRow.taskId || childRow.id), bk: childRow && childRow.businessKey, candidate: childRow && childRow.candidate });
  if (!childRow) throw new Error('child task not found');
  const childTaskId = String(childRow.taskId || childRow.id);
  const childRecordId = String(childRow.businessKey || '');

  // 6. 记录实例身份（供取证与清理）
  const instQ = await api(admin, 'POST', '/workflow/instances/query',
    { pageNum: 1, pageSize: 20, filters: [] });
  const instRows = (instQ.json.data && (instQ.json.data.records || instQ.json.data.list)) || [];
  const mine = instRows.filter((i) => i.businessKey === recordId
    || String(i.businessKey) === childRecordId).map((i) => ({
    id: i.processInstanceId, bk: i.businessKey, status: i.status }));
  step('6 instances', { mine });

  fs.writeFileSync(OUT + '/05b-minimal-dispatch-identity.json', JSON.stringify({
    at: new Date().toISOString(),
    parentRecordId: recordId, opsTaskId, childTaskId, childRecordId,
    instances: mine, steps,
  }, null, 2));
  console.log('SAVED identity; childTaskId=' + childTaskId);
})().catch((e) => { console.error('FATAL', e.message); process.exit(1); });
