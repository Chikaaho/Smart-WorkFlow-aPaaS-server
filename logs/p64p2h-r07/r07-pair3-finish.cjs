// P64 回执07 P2-04a：pair3 收敛补齐（CONFLICT 恢复→SETTLED→复核→APPROVED）
const fs = require('fs');
const { execSync } = require('child_process');
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const OUT = LOGS + '/p64p2h-r07';
const BASE = 'http://127.0.0.1:8080/api';
const PGJAR = 'E:/Maven/repository/org/postgresql/postgresql/42.5.0/postgresql-42.5.0.jar';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function dbq(sql) {
  const tmp = OUT + '/_tmp_dbq_out.txt';
  execSync(`java --class-path "${PGJAR}" --source 21 logs/p64p2h-r07/r07_dbqfull.java "${tmp}" "${sql}"`, { cwd: LOGS + '/..', stdio: 'pipe' });
  return fs.readFileSync(tmp, 'utf8');
}
function firstUuid(txt) { const m = txt.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/); return m && m[0]; }
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
  const p3 = JSON.parse(fs.readFileSync(OUT + '/07-pair3-reverse-final.json', 'utf8'));
  const admin = fs.readFileSync(LOGS + '/p64p2h-r07-token-admin.txt', 'utf8').trim();
  const recordId = p3.pair3.parentRecordId;
  const parentInstId = firstUuid(dbq(`SELECT process_instance_id FROM sw_bpm_instance WHERE business_key='${recordId}' AND status IN ('RUNNING','WAITING') LIMIT 1`))
    || firstUuid(dbq(`SELECT process_instance_id FROM sw_bpm_instance WHERE business_key='${recordId}' LIMIT 1`));
  const b = await api(admin, 'GET', '/workflow/instances/' + parentInstId + '/child-batches');
  const batch = (b.json.data || [])[0];
  const conflictItem = batch && (batch.items || []).find((i) => i.status === 'CONFLICT');
  let retry = null;
  if (conflictItem) {
    retry = await api(admin, 'POST', '/workflow/child-items/' + conflictItem.id + '/retry-writeback');
    console.log('retry', conflictItem.id, retry.json.code, retry.json.msg);
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
  let reviewDone = null;
  for (let i = 0; i < 4; i++) {
    const todo = await api(admin, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
    const rows = (todo.json.data && (todo.json.data.records || todo.json.data.list)) || [];
    const rv = rows.find((t) => t.businessKey === recordId);
    if (rv) {
      const taskId = String(rv.taskId || rv.id);
      const nf = await api(admin, 'GET', '/workflow/tasks/' + taskId + '/node-form');
      const fd = {};
      for (const f of ((nf.json.data && nf.json.data.definition && nf.json.data.definition.fields) || [])) {
        if (f.type === 'TABLE') continue;
        fd[f.name] = f.type === 'NUMBER' ? 1 : 'R07P3R-' + (f.label || f.name);
      }
      const rdone = await api(admin, 'POST', '/workflow/commands/tasks/' + taskId + '/complete', { action: 'APPROVE', nodeFormData: fd });
      reviewDone = { taskId, code: rdone.json.code, msg: rdone.json.msg };
      break;
    }
    await sleep(3000);
  }
  await sleep(5000);
  const result = { at: new Date().toISOString(), parentRecordId: recordId, parentInstanceId: parentInstId, retry: retry && { item: conflictItem.id, code: retry.json.code }, settled, reviewDone };
  fs.writeFileSync(OUT + '/07-pair3-convergence.json', JSON.stringify(result, null, 2));
  console.log('DONE', JSON.stringify(result).slice(0, 300));
})().catch((e) => { console.error('FATAL', e.message); process.exit(1); });
