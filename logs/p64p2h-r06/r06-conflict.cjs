// P64 回执06 P2-02b-V-C：真实未恢复冲突（反向）→ 受控恢复（正向）
// 1 父记录（1 行 owner=张三）→ 运维通过 → 子派发 → 父主记录 PUT 版本抬升 →
// 张三完成子任务 → 回写主记录 VERSION_CONFLICT（item=CONFLICT/batch=BLOCKED 挂起，像素待处置）→
// admin 受控恢复一次（按当前权威版本）→ item=WRITTEN/batch=SETTLED（诊断清空，正向像素）
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
  let json = {}; try { json = JSON.parse(text); } catch { json = { raw: text.slice(0, 400) }; }
  return { status: res.status, json };
}
(async () => {
  const actors = JSON.parse(fs.readFileSync(LOGS + '/p64p2-actors.json', 'utf8'));
  const admin = fs.readFileSync(LOGS + '/p64p2h-r06-token-admin.txt', 'utf8').trim();
  const zs = fs.readFileSync(LOGS + '/p64p2h-r06-token-zhangsan.txt', 'utf8').trim();

  // 1. 父记录
  const rec = await api(admin, 'POST', '/form/data/p64p2b_parent_form', {
    title: 'R06冲突恢复P2-02b-VC',
    hosts: [{ host_name: 'host-r06-c1', owner: actors.zhangsanId, feedback: '' }],
  });
  step('1 parent submit', { code: rec.json.code, id: rec.json.data });
  const recordId = String(rec.json.data && (rec.json.data.id || rec.json.data));
  await sleep(5000);

  // 2. 运维通过 → 派发
  const todo = await api(admin, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
  const rows = (todo.json.data && (todo.json.data.records || todo.json.data.list)) || [];
  const opsRow = rows.find((t) => t.businessKey === recordId);
  const opsTaskId = String(opsRow.taskId || opsRow.id);
  const nf = await api(admin, 'GET', '/workflow/tasks/' + opsTaskId + '/node-form');
  const nodeFormData = {};
  for (const f of ((nf.json.data && nf.json.data.definition && nf.json.data.definition.fields) || [])) {
    if (f.type === 'TABLE') continue;
    nodeFormData[f.name] = f.type === 'NUMBER' ? 1 : 'R06C-' + (f.label || f.name);
  }
  const done = await api(admin, 'POST', '/workflow/commands/tasks/' + opsTaskId + '/complete',
    { action: 'APPROVE', nodeFormData });
  step('2 ops complete', { code: done.json.code, msg: done.json.msg });
  await sleep(7000);

  // 3. 张三子任务
  const ztodo = await api(zs, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
  const zrows = (ztodo.json.data && (ztodo.json.data.records || ztodo.json.data.list)) || [];
  const childRow = zrows.find((t) => (t.processName || '').includes('子反馈'));
  const childTaskId = String(childRow.taskId || childRow.id);
  const childRecordId = String(childRow.businessKey || '');
  step('3 zhangsan child task', { taskId: childTaskId, recordId: childRecordId });

  // 4. 父主记录 PUT（版本抬升：0→1，制造回写版本冲突）
  const cur = await api(admin, 'GET', '/form/data/p64p2b_parent_form/' + recordId);
  step('4 parent current', { code: cur.json.code, version: cur.json.data && cur.json.data.version });
  const put = await api(admin, 'PUT', '/form/data/p64p2b_parent_form/' + recordId, {
    data: { title: 'R06冲突恢复P2-02b-VC（编辑后）' },
    version: Number((cur.json.data && cur.json.data.version) || 0),
    subTableRows: {},
  });
  step('4 parent PUT', { code: put.json.code, msg: put.json.msg });

  // 5. 张三完成子任务（填报反馈）→ 回写 VERSION_CONFLICT
  const cnf = await api(zs, 'GET', '/workflow/tasks/' + childTaskId + '/node-form');
  const childRec = await api(zs, 'GET', '/form/data/p64p2b_child_form/' + childRecordId);
  if (childRec.json.code !== 0) throw new Error('zhangsan cannot read own child record: ' + childRec.json.msg);
  const tfield = ((cnf.json.data && cnf.json.data.definition && cnf.json.data.definition.fields) || [])
    .find((f) => f.type === 'TABLE');
  const srcRows = (childRec.json.data && childRec.json.data[tfield.name]) || [];
  if (!srcRows.length) throw new Error('no authorized source rows in child record');
  const childFormData = { summary: 'R06C-FB' };
  childFormData[tfield.name] = srcRows.map((r) => ({ src_row_id: r.src_row_id, feedback: 'R06C-FB-OK' }));
  const cdone = await api(zs, 'POST', '/workflow/commands/tasks/' + childTaskId + '/complete',
    { action: 'APPROVE', nodeFormData: childFormData });
  step('5 child complete', { code: cdone.json.code, msg: cdone.json.msg });
  await sleep(7000);

  // 6. 冲突挂起状态捕获（必要反向原件：未恢复冲突准确显示待处置）
  const taskDetail = await api(admin, 'GET', '/workflow/tasks/' + opsTaskId);
  const parentInst = { processInstanceId: taskDetail.json.data && (taskDetail.json.data.processInstanceId || (taskDetail.json.data.instance && taskDetail.json.data.instance.processInstanceId)) };
  if (!parentInst.processInstanceId) throw new Error('parent instance id not found from ops task detail');
  const batch = await api(admin, 'GET', '/workflow/instances/' + parentInst.processInstanceId + '/child-batches');
  step('6 conflict state', { code: batch.json.code, batch: JSON.stringify(batch.json.data).slice(0, 500) });

  // 7. admin 受控恢复一次（仅 CONFLICT 项可恢复）
  const conflictItem = (batch.json.data || [])[0] && (batch.json.data[0].items || []).find((i) => i.status === 'CONFLICT');
  if (!conflictItem) throw new Error('no CONFLICT item found');
  const retry = await api(admin, 'POST', '/workflow/child-items/' + conflictItem.id + '/retry-writeback');
  step('7 retry writeback', { code: retry.json.code, msg: retry.json.msg, data: retry.json.data });
  await sleep(5000);

  // 8. 恢复后状态捕获（正向：WRITTEN/SETTLED，诊断清空）
  const batch2 = await api(admin, 'GET', '/workflow/instances/' + parentInst.processInstanceId + '/child-batches');
  step('8 recovered state', { batch: JSON.stringify(batch2.json.data).slice(0, 500) });

  // 9. 父唤醒推进（SETTLED→等待节点唤醒→父继续；复核任务由后续脚本/清理处置）
  const instAfter = await api(admin, 'GET', '/workflow/tasks/' + childTaskId);
  step('9 child task after', { code: instAfter.json.code, msg: instAfter.json.msg });

  fs.writeFileSync(OUT + '/06-conflict-recovery-identity.json', JSON.stringify({
    at: new Date().toISOString(), parentRecordId: recordId, opsTaskId, childTaskId,
    childRecordId, conflictItemId: conflictItem.id, parentInstanceId: parentInst.processInstanceId,
    conflictBefore: batch.json.data, recoveredAfter: batch2.json.data, steps,
  }, null, 2));
  console.log('SAVED conflict identity');
})().catch((e) => { console.error('FATAL', e.message); process.exit(1); });
