// P64 回执07 P2-04a：第三次（最终）干净反向——乙对甲活任务 complete 后等待命令真终态（SQL 轮询 ≤48s），
// 期间甲不办理；以命令 status/failure_reason 判定：FAILED(权限/无效果)=干净反向，COMPLETED=缺陷信号。
const fs = require('fs');
const { execSync } = require('child_process');
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const OUT = LOGS + '/p64p2h-r07';
const BASE = 'http://127.0.0.1:8080/api';
const PGJAR = 'E:/Maven/repository/org/postgresql/postgresql/42.5.0/postgresql-42.5.0.jar';
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
  if (childRec.json.code !== 0 || !srcRows.length) throw new Error(label + ' payload fail');
  const nodeFormData = { summary: label + '-FB' };
  nodeFormData[tfield.name] = srcRows.map((r) => ({ src_row_id: r.src_row_id, feedback: label + '-OK' }));
  return { payload: { action: 'APPROVE', nodeFormData }, srcRowIds: srcRows.map((r) => r.src_row_id) };
}
function dbq(sql) {
  const tmp = OUT + '/_tmp_dbq_out.txt';
  execSync(`java --class-path "${PGJAR}" --source 21 logs/p64p2h-r07/r07_dbqfull.java "${tmp}" "${sql}"`, { cwd: LOGS + '/..', stdio: 'pipe' });
  return fs.readFileSync(tmp, 'utf8');
}
(async () => {
  const actors = JSON.parse(fs.readFileSync(LOGS + '/p64p2-actors.json', 'utf8'));
  const admin = tokenOf('admin'), jiaT = tokenOf('jia'), yiT = tokenOf('yi');
  const out = { at: new Date().toISOString() };

  // ===== 1. 第三最小对 =====
  const rec = await api(admin, 'POST', '/form/data/p64p2b_parent_form', {
    title: 'R07反向对B-P2-04a',
    hosts: [
      { host_name: 'jia', owner: actors.jiaId, feedback: '' },
      { host_name: 'yi', owner: actors.yiId, feedback: '' },
    ],
  });
  const recordId = String(rec.json.data && (rec.json.data.id || rec.json.data));
  if (rec.json.code !== 0 || recordId === 'null') throw new Error('parent submit failed: ' + JSON.stringify(rec.json).slice(0, 200));
  await sleep(5000);
  const opsRow = await findTask(admin, recordId);
  if (!opsRow) throw new Error('ops task not found for ' + recordId);
  const opsDone = await fillAndComplete(admin, opsRow, 'R07P3O');
  step('1 ops complete', { code: opsDone.json.code });
  await sleep(8000);
  const child = {};
  for (const u of ['jia', 'yi']) {
    const row = (await todoRows(tokenOf(u))).find((t) => (t.processName || '').includes('子反馈'));
    if (!row) throw new Error(u + ' child task not found');
    child[u] = { taskId: String(row.taskId || row.id), recordId: String(row.businessKey || '') };
  }
  out.pair3 = { parentRecordId: recordId, children: child };
  step('2 pair3 children', child);

  // ===== 2. 乙对甲活任务 complete（此后甲不动，等命令真终态） =====
  const rev = await api(yiT, 'POST', '/workflow/commands/tasks/' + child.jia.taskId + '/complete',
    { action: 'APPROVE', nodeFormData: { summary: 'R07P3-YI-REV-FB' } });
  step('3 yi reverse on jia ACTIVE task', { http: rev.status, code: rev.json.code, msg: rev.json.msg });
  out.pair3.reverseHttp = { http: rev.status, code: rev.json.code, msg: rev.json.msg, at: new Date().toISOString() };

  // ===== 3. SQL 轮询命令真终态（≤48s；期间甲不办理） =====
  let cmdFinal = null;
  for (let i = 0; i < 12; i++) {
    await sleep(4000);
    const outTxt = dbq(`SELECT status, left(coalesce(failure_reason,'<none>'),160) AS fail, create_by, finished_at FROM sw_bpm_command WHERE payload LIKE '%${child.jia.taskId}%' AND create_by='2108817587316060161' ORDER BY create_time DESC LIMIT 1`);
    const line = outTxt.split('\n').find((l) => l.includes('|') && !l.includes('--') && !l.includes('status |'));
    if (line && !line.includes('NULL')) {
      const [status, fail, createBy, finishedAt] = line.split('|').map((s) => s.trim());
      cmdFinal = { status, fail, createBy, finishedAt, polledAt: new Date().toISOString(), pollRound: i + 1 };
      if (finishedAt && finishedAt !== 'NULL') break;
    }
  }
  const jiaTaskActive = (await todoRows(jiaT)).some((t) => String(t.taskId || t.id) === child.jia.taskId);
  out.pair3.reverseCommandFinal = cmdFinal;
  out.pair3.jiaTaskStillActive = jiaTaskActive;
  step('4 reverse command final', { cmdFinal, jiaTaskActive });
  fs.writeFileSync(OUT + '/07-pair3-reverse-final.json', JSON.stringify(out, null, 2));
  if (!cmdFinal || cmdFinal.status === 'COMPLETED') throw new Error('DEFECT SIGNAL or unresolved: ' + JSON.stringify(cmdFinal));

  // ===== 5. 甲完成 → 乙完成 → 收敛 =====
  const jp = await childPayload(jiaT, child.jia.taskId, child.jia.recordId, 'R07P3-JIA');
  const jdone = await api(jiaT, 'POST', '/workflow/commands/tasks/' + child.jia.taskId + '/complete', jp.payload);
  step('5a jia complete', { code: jdone.json.code });
  await sleep(4000);
  const yp = await childPayload(yiT, child.yi.taskId, child.yi.recordId, 'R07P3-YI');
  const ydone = await api(yiT, 'POST', '/workflow/commands/tasks/' + child.yi.taskId + '/complete', yp.payload);
  step('5b yi complete', { code: ydone.json.code });
  out.pair3.completions = { jia: jdone.json.code, yi: ydone.json.code };
  if (ydone.json.code !== 0) throw new Error('yi complete failed');
  await sleep(6000);
  const td = await api(admin, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
  const opsTaskRow = ((td.json.data && (td.json.data.records || td.json.data.list)) || []).find(() => false);
  void opsTaskRow;
  // 收敛：恢复 CONFLICT → SETTLED → 复核
  const instQ = dbq(`SELECT process_instance_id FROM sw_bpm_instance WHERE business_key='${recordId}' LIMIT 1`);
  const parentInstId = (instTxt(instQ));
  const b = await api(admin, 'GET', '/workflow/instances/' + parentInstId + '/child-batches');
  const batch = (b.json.data || [])[0];
  const conflictItem = batch && (batch.items || []).find((i) => i.status === 'CONFLICT');
  let retry = null;
  if (conflictItem) {
    retry = await api(admin, 'POST', '/workflow/child-items/' + conflictItem.id + '/retry-writeback');
    await sleep(6000);
  }
  let settled = null;
  for (let i = 0; i < 5; i++) {
    const b2 = await api(admin, 'GET', '/workflow/instances/' + parentInstId + '/child-batches');
    const bt = (b2.json.data || [])[0];
    settled = bt && { status: bt.status, settled: bt.settledCount, expected: bt.expectedCount };
    if (settled && settled.status === 'SETTLED') break;
    await sleep(3000);
  }
  let reviewDone = null;
  for (let i = 0; i < 4; i++) {
    const rv = await findTask(admin, recordId);
    if (rv) {
      const rdone = await fillAndComplete(admin, rv, 'R07P3R');
      reviewDone = { taskId: String(rv.taskId || rv.id), code: rdone.json.code };
      break;
    }
    await sleep(3000);
  }
  out.pair3.convergence = { parentInstanceId: parentInstId, retry: retry && retry.json.code, settled, reviewDone };
  step('6 converged', out.pair3.convergence);
  fs.writeFileSync(OUT + '/07-pair3-reverse-final.json', JSON.stringify(out, null, 2));
  console.log('SAVED pair3 final');
})().catch((e) => { console.error('FATAL', e.message); process.exit(1); });
function instTxt(q) { const l = q.split('\n').find((x) => x.includes('-') === false && /\w{8}-\w{4}/.test(x)); return l ? l.split('|')[0].trim() : ''; }
