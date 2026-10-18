// P64 回执07 P2-04a：乙本人真实成功办理（必要反向 + 甲完成 + 乙合法载荷成功 + 批次结算 + 父复核收敛）
// 反向：乙对甲任务的 complete 调用（期望拒绝、无业务效果；甲随后成功 = 无效果证明）。
// 正向：jia/yi 各自办理自己被派发的子任务（载荷=节点表单授权来源行，合法完整请求）。
// 输出：07-reverse-yi-on-jia.json / 07-yi-success.json / 07-pair-final.json
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
async function childPayload(token, taskId, recordId, label) {
  const cnf = await api(token, 'GET', '/workflow/tasks/' + taskId + '/node-form');
  const childRec = await api(token, 'GET', '/form/data/p64p2b_child_form/' + recordId);
  const tfield = ((cnf.json.data && cnf.json.data.definition && cnf.json.data.definition.fields) || []).find((f) => f.type === 'TABLE');
  const srcRows = (childRec.json.data && childRec.json.data[tfield.name]) || [];
  if (childRec.json.code !== 0 || !srcRows.length) {
    return { error: { readRecordCode: childRec.json.code, msg: childRec.json.msg, tableField: tfield && tfield.name, srcRows: srcRows.length } };
  }
  const nodeFormData = { summary: label + '-FB' };
  nodeFormData[tfield.name] = srcRows.map((r) => ({ src_row_id: r.src_row_id, feedback: label + '-OK' }));
  return { payload: { action: 'APPROVE', nodeFormData }, tableField: tfield.name, srcRowIds: srcRows.map((r) => r.src_row_id), recordReadCode: childRec.json.code };
}
(async () => {
  const idn = JSON.parse(fs.readFileSync(OUT + '/07-dispatch-pair-identity.json', 'utf8'));
  const jia = idn.children.jia, yi = idn.children.yi;
  const admin = tokenOf('admin'), jiaT = tokenOf('jia'), yiT = tokenOf('yi');

  // ===== 1. 必要反向：乙 complete 甲任务（期望业务拒绝） =====
  const rev = await api(yiT, 'POST', '/workflow/commands/tasks/' + jia.taskId + '/complete',
    { action: 'APPROVE', nodeFormData: { summary: 'R07-YI-REV-FB' } });
  step('1 yi complete jia task (expect reject)', { http: rev.status, code: rev.json.code, msg: rev.json.msg });
  fs.writeFileSync(OUT + '/07-reverse-yi-on-jia.json', JSON.stringify({
    at: new Date().toISOString(), direction: 'yi→jia task complete',
    taskId: jia.taskId, recordId: jia.recordId,
    http: rev.status, code: rev.json.code, msg: rev.json.msg, data: rev.json.data,
  }, null, 2));

  // ===== 2. 甲完成自己的子任务（无效果证明 + 甲成功原件） =====
  const jp = await childPayload(jiaT, jia.taskId, jia.recordId, 'R07-JIA');
  if (jp.error) throw new Error('jia payload fail: ' + JSON.stringify(jp.error));
  const jdone = await api(jiaT, 'POST', '/workflow/commands/tasks/' + jia.taskId + '/complete', jp.payload);
  step('2 jia complete', { code: jdone.json.code, msg: jdone.json.msg, srcRowIds: jp.srcRowIds });
  fs.writeFileSync(OUT + '/07-jia-success.json', JSON.stringify({
    at: new Date().toISOString(), taskId: jia.taskId, recordId: jia.recordId,
    tableField: jp.tableField, srcRowIds: jp.srcRowIds, recordReadCode: jp.recordReadCode,
    submitted: jp.payload, http: jdone.status, code: jdone.json.code, msg: jdone.json.msg,
  }, null, 2));
  await sleep(4000);

  // ===== 3. 乙完成自己的子任务（关键成功原件：合法载荷、授权来源行） =====
  const yp = await childPayload(yiT, yi.taskId, yi.recordId, 'R07-YI');
  if (yp.error) throw new Error('yi payload fail: ' + JSON.stringify(yp.error));
  const ydone = await api(yiT, 'POST', '/workflow/commands/tasks/' + yi.taskId + '/complete', yp.payload);
  step('3 yi complete (KEY)', { code: ydone.json.code, msg: ydone.json.msg, srcRowIds: yp.srcRowIds });
  fs.writeFileSync(OUT + '/07-yi-success.json', JSON.stringify({
    at: new Date().toISOString(), taskId: yi.taskId, recordId: yi.recordId,
    tableField: yp.tableField, srcRowIds: yp.srcRowIds, recordReadCode: yp.recordReadCode,
    submitted: yp.payload, http: ydone.status, code: ydone.json.code, msg: ydone.json.msg,
    note: '乙本人对系统分配对象合法完整载荷成功办理（与回执06 2426 原件区别：本轮载荷无未知字段、来源行取自本人子记录）',
  }, null, 2));
  if (ydone.json.code !== 0) throw new Error('yi complete failed');
  await sleep(6000);

  // ===== 4. 批次结算等待（有界轮询） =====
  const td = await api(admin, 'GET', '/workflow/tasks/' + idn.opsTaskId);
  const parentInstId = td.json.data && (td.json.data.processInstanceId || (td.json.data.instance && td.json.data.instance.processInstanceId));
  let batchStatus = null;
  for (let i = 0; i < 6; i++) {
    const b = await api(admin, 'GET', '/workflow/instances/' + parentInstId + '/child-batches');
    batchStatus = (b.json.data || [])[0] && { status: b.json.data[0].status, settled: b.json.data[0].settledCount, expected: b.json.data[0].expectedCount, blockReason: b.json.data[0].blockReason };
    if (batchStatus && batchStatus.status === 'SETTLED') break;
    await sleep(3000);
  }
  step('4 batch settle', { parentInstId, batchStatus });

  // ===== 5. 父复核（等待唤醒后下一节点）→ APPROVED =====
  let reviewDone = null;
  for (let i = 0; i < 4; i++) {
    const todo = await api(admin, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
    const rows = (todo.json.data && (todo.json.data.records || todo.json.data.list)) || [];
    const rv = rows.find((t) => t.businessKey === idn.parentRecordId);
    if (rv) {
      const taskId = String(rv.taskId || rv.id);
      const nf = await api(admin, 'GET', '/workflow/tasks/' + taskId + '/node-form');
      const fd = {};
      for (const f of ((nf.json.data && nf.json.data.definition && nf.json.data.definition.fields) || [])) {
        if (f.type === 'TABLE') continue;
        fd[f.name] = f.type === 'NUMBER' ? 1 : 'R07R-' + (f.label || f.name);
      }
      const rdone = await api(admin, 'POST', '/workflow/commands/tasks/' + taskId + '/complete', { action: 'APPROVE', nodeFormData: fd });
      reviewDone = { taskId, code: rdone.json.code, msg: rdone.json.msg };
      break;
    }
    await sleep(3000);
  }
  step('5 review complete', reviewDone);
  await sleep(5000);

  // ===== 6. 终态回读（任务详情 API + 批次面板） =====
  const b2 = await api(admin, 'GET', '/workflow/instances/' + parentInstId + '/child-batches');
  fs.writeFileSync(OUT + '/07-pair-final.json', JSON.stringify({
    at: new Date().toISOString(), parentRecordId: idn.parentRecordId, parentInstanceId: parentInstId,
    opsTaskId: idn.opsTaskId, batchAfterSettle: b2.json.data, reviewDone,
    children: { jia, yi },
    reverse: JSON.parse(fs.readFileSync(OUT + '/07-reverse-yi-on-jia.json', 'utf8')),
    steps,
  }, null, 2));
  console.log('SAVED pair final');
})().catch((e) => { console.error('FATAL', e.message); process.exit(1); });
