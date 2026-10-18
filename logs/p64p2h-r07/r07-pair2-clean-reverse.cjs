// P64 回执07 P2-04a：第二最小对——干净时序反向（乙对甲活任务 complete → 命令终态轮询 → 甲随后成功）
// + 两对收敛：CONFLICT 恢复 → SETTLED → 父复核 → APPROVED（全部终态）
const fs = require('fs');
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const OUT = LOGS + '/p64p2h-r07';
const BASE = 'http://127.0.0.1:8080/api';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const steps = [];
function step(name, data) { steps.push({ name, data }); console.log(name, JSON.stringify(data).slice(0, 320)); }
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
const tokenOf = (u) => fs.readFileSync(LOGS + '/p64p2h-r07-token-' + u + '.txt', 'utf8').trim();
async function todoRows(token) {
  const td = await api(token, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
  return (td.json.data && (td.json.data.records || td.json.data.list)) || [];
}
async function findTask(token, recordId) {
  return (await todoRows(token)).find((t) => t.businessKey === recordId);
}
async function fillAndComplete(token, task, prefix) {
  const taskId = String(task.taskId || task.id);
  const nf = await api(token, 'GET', '/workflow/tasks/' + taskId + '/node-form');
  const fd = {};
  for (const f of ((nf.json.data && nf.json.data.definition && nf.json.data.definition.fields) || [])) {
    if (f.type === 'TABLE') continue;
    fd[f.name] = f.type === 'NUMBER' ? 1 : prefix + '-' + (f.label || f.name);
  }
  return api(token, 'POST', '/workflow/commands/tasks/' + taskId + '/complete', { action: 'APPROVE', nodeFormData: fd });
}
async function childPayload(token, taskId, recordId, label) {
  const cnf = await api(token, 'GET', '/workflow/tasks/' + taskId + '/node-form');
  const childRec = await api(token, 'GET', '/form/data/p64p2b_child_form/' + recordId);
  const tfield = ((cnf.json.data && cnf.json.data.definition && cnf.json.data.definition.fields) || []).find((f) => f.type === 'TABLE');
  const srcRows = (childRec.json.data && childRec.json.data[tfield.name]) || [];
  if (childRec.json.code !== 0 || !srcRows.length) throw new Error(label + ' payload fail ' + JSON.stringify({ code: childRec.json.code, n: srcRows.length }));
  const nodeFormData = { summary: label + '-FB' };
  nodeFormData[tfield.name] = srcRows.map((r) => ({ src_row_id: r.src_row_id, feedback: label + '-OK' }));
  return { payload: { action: 'APPROVE', nodeFormData }, srcRowIds: srcRows.map((r) => r.src_row_id) };
}
async function recoverAndFinish(admin, parentInstId, parentRecordId, tag) {
  const b = await api(admin, 'GET', '/workflow/instances/' + parentInstId + '/child-batches');
  const batch = (b.json.data || [])[0];
  const conflictItem = batch && (batch.items || []).find((i) => i.status === 'CONFLICT');
  let retry = null;
  if (conflictItem) {
    retry = await api(admin, 'POST', '/workflow/child-items/' + conflictItem.id + '/retry-writeback');
    step(tag + ' retry writeback', { item: conflictItem.id, code: retry.json.code, msg: retry.json.msg });
    await sleep(6000);
  }
  let settled = null;
  for (let i = 0; i < 5; i++) {
    const b2 = await api(admin, 'GET', '/workflow/instances/' + parentInstId + '/child-batches');
    const bt = (b2.json.data || [])[0];
    settled = bt && { status: bt.status, settled: bt.settledCount, expected: bt.expectedCount, blockReason: bt.blockReason };
    if (settled && settled.status === 'SETTLED') break;
    await sleep(3000);
  }
  step(tag + ' batch settled', settled);
  let reviewDone = null;
  for (let i = 0; i < 4; i++) {
    const rv = await findTask(admin, parentRecordId);
    if (rv) {
      const rdone = await fillAndComplete(admin, rv, tag + 'R');
      reviewDone = { taskId: String(rv.taskId || rv.id), code: rdone.json.code, msg: rdone.json.msg };
      break;
    }
    await sleep(3000);
  }
  step(tag + ' review complete', reviewDone);
  await sleep(5000);
  return { retry: retry && { code: retry.json.code }, settled, reviewDone };
}
(async () => {
  const actors = JSON.parse(fs.readFileSync(LOGS + '/p64p2-actors.json', 'utf8'));
  const admin = tokenOf('admin'), jiaT = tokenOf('jia'), yiT = tokenOf('yi');
  const out = { at: new Date().toISOString() };

  // ===== 0. 第一对收敛（CONFLICT 恢复→SETTLED→复核→APPROVED） =====
  const pair1 = JSON.parse(fs.readFileSync(OUT + '/07-pair-final.json', 'utf8'));
  out.pair1 = await recoverAndFinish(admin, pair1.parentInstanceId, pair1.parentRecordId, 'P1');
  fs.writeFileSync(OUT + '/07-pair-convergence.json', JSON.stringify({ pair1ParentInstanceId: pair1.parentInstanceId, ...out.pair1 }, null, 2));

  // ===== 1. 第二最小对建立 =====
  const rec = await api(admin, 'POST', '/form/data/p64p2b_parent_form', {
    title: 'R07反向对P2-04a',
    hosts: [
      { host_name: 'jia', owner: actors.jiaId, feedback: '' },
      { host_name: 'yi', owner: actors.yiId, feedback: '' },
    ],
  });
  const recordId = String(rec.json.data && (rec.json.data.id || rec.json.data));
  await sleep(5000);
  const opsRow = await findTask(admin, recordId);
  if (!opsRow) throw new Error('ops task not found');
  const opsDone = await fillAndComplete(admin, opsRow, 'R07P2O');
  step('1 ops complete', { code: opsDone.json.code, msg: opsDone.json.msg });
  await sleep(8000);
  const child = {};
  for (const [user, uid] of [['jia', actors.jiaId], ['yi', actors.yiId]]) {
    const row = ((await todoRows(tokenOf(user))).find((t) => (t.processName || '').includes('子反馈')));
    if (!row) throw new Error(user + ' child task not found');
    child[user] = { taskId: String(row.taskId || row.id), recordId: String(row.businessKey || '') };
  }
  step('2 pair2 children', child);
  const td = await api(admin, 'GET', '/workflow/tasks/' + String(opsRow.taskId || opsRow.id));
  const parentInstId = td.json.data && (td.json.data.processInstanceId || (td.json.data.instance && td.json.data.instance.processInstanceId));
  out.pair2 = { parentRecordId: recordId, parentInstanceId: parentInstId, children: child };

  // ===== 3. 干净反向：乙对甲活任务 complete → 等命令终态（甲任务保持活跃=未生效） =====
  const rev = await api(yiT, 'POST', '/workflow/commands/tasks/' + child.jia.taskId + '/complete',
    { action: 'APPROVE', nodeFormData: { summary: 'R07P2-YI-REV-FB' } });
  step('3 yi reverse on jia ACTIVE task', { http: rev.status, code: rev.json.code, msg: rev.json.msg });
  let jiaTaskStillActive = false;
  for (let i = 0; i < 5; i++) {
    await sleep(3000);
    const rows = await todoRows(jiaT);
    jiaTaskStillActive = rows.some((t) => String(t.taskId || t.id) === child.jia.taskId);
    if (jiaTaskStillActive) break;
  }
  step('3b jia task still active after yi reverse', { jiaTaskStillActive });
  out.pair2.reverse = {
    at: new Date().toISOString(), direction: 'yi→jia task complete (task ACTIVE, no race)',
    taskId: child.jia.taskId, recordId: child.jia.recordId,
    http: rev.status, code: rev.json.code, msg: rev.json.msg,
    jiaTaskStillActiveAfterCommandSettled: jiaTaskStillActive,
  };
  fs.writeFileSync(OUT + '/07-pair2-reverse-clean.json', JSON.stringify(out.pair2.reverse, null, 2));
  if (!jiaTaskStillActive) throw new Error('DEFECT SIGNAL: yi command completed jia task — see receipt');

  // ===== 4. 甲完成 → 乙完成 =====
  const jp = await childPayload(jiaT, child.jia.taskId, child.jia.recordId, 'R07P2-JIA');
  const jdone = await api(jiaT, 'POST', '/workflow/commands/tasks/' + child.jia.taskId + '/complete', jp.payload);
  step('4a jia complete', { code: jdone.json.code, srcRowIds: jp.srcRowIds });
  await sleep(4000);
  const yp = await childPayload(yiT, child.yi.taskId, child.yi.recordId, 'R07P2-YI');
  const ydone = await api(yiT, 'POST', '/workflow/commands/tasks/' + child.yi.taskId + '/complete', yp.payload);
  step('4b yi complete', { code: ydone.json.code, srcRowIds: yp.srcRowIds });
  out.pair2.completions = { jia: { code: jdone.json.code, srcRowIds: jp.srcRowIds }, yi: { code: ydone.json.code, srcRowIds: yp.srcRowIds } };
  if (ydone.json.code !== 0) throw new Error('yi complete failed');
  await sleep(6000);

  // ===== 5. 第二对收敛 =====
  out.pair2.convergence = await recoverAndFinish(admin, parentInstId, recordId, 'P2');
  fs.writeFileSync(OUT + '/07-pair2-final.json', JSON.stringify(out, null, 2));
  console.log('SAVED pair2 final');
})().catch((e) => { console.error('FATAL', e.message); process.exit(1); });
