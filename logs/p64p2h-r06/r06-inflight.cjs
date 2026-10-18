// P64 回执06 P2-06b：阶段Ⅱ在役对象——关闭新入口（发布去触发新版本）后在役收敛
// 步骤：A 建在役父实例（v3，2 行张三/李四）→ 运维通过 → 批次 WAITING（子任务未办）
//       B 发布 v4（图去 triggers）=关闭新入口；捕获新版本值与产物
//       C 新入口零意图：新父实例走完运维节点 → 无 trigger_exec/无批次/无意图（SQL 复核）
//       D 在役收敛：张三/李四完成子任务 → 批次 SETTLED → 父等待唤醒 → 复核通过 APPROVED
//       E 在役查询路径：父实例回查（列表/详情/批次）200
const fs = require('fs');
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const OUT = LOGS + '/p64p2h-r06';
const BASE = 'http://127.0.0.1:8080/api';
const PARENT_DEF_ID = '2108817690357526529'; // P64B主机反馈父流程（v3 PUBLISHED）
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
async function findOpsTask(admin, recordId) {
  const todo = await api(admin, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
  const rows = (todo.json.data && (todo.json.data.records || todo.json.data.list)) || [];
  return rows.find((t) => t.businessKey === recordId);
}
async function completeOps(admin, recordId, title) {
  const opsRow = await findOpsTask(admin, recordId);
  if (!opsRow) throw new Error('ops task not found for ' + recordId);
  const opsTaskId = String(opsRow.taskId || opsRow.id);
  const nf = await api(admin, 'GET', '/workflow/tasks/' + opsTaskId + '/node-form');
  const nodeFormData = {};
  for (const f of ((nf.json.data && nf.json.data.definition && nf.json.data.definition.fields) || [])) {
    if (f.type === 'TABLE') continue;
    nodeFormData[f.name] = f.type === 'NUMBER' ? 1 : title + '-' + (f.label || f.name);
  }
  const done = await api(admin, 'POST', '/workflow/commands/tasks/' + opsTaskId + '/complete',
    { action: 'APPROVE', nodeFormData });
  step('ops complete ' + recordId, { code: done.json.code, msg: done.json.msg });
  return opsTaskId;
}
(async () => {
  const actors = JSON.parse(fs.readFileSync(LOGS + '/p64p2-actors.json', 'utf8'));
  const admin = fs.readFileSync(LOGS + '/p64p2h-r06-token-admin.txt', 'utf8').trim();
  const zs = fs.readFileSync(LOGS + '/p64p2h-r06-token-zhangsan.txt', 'utf8').trim();
  const ls = fs.readFileSync(LOGS + '/p64p2h-r06-token-lisi.txt', 'utf8').trim();

  // ===== A. 在役父实例（v3；子任务保持未办） =====
  const rec = await api(admin, 'POST', '/form/data/p64p2b_parent_form', {
    title: 'R06在役关闭P2-06b',
    hosts: [
      { host_name: 'zhangsan', owner: actors.zhangsanId, feedback: '' },
      { host_name: 'lisi', owner: actors.lisiId, feedback: '' },
    ],
  });
  step('A1 inflight parent submit', { code: rec.json.code, id: rec.json.data });
  const recordId = String(rec.json.data && (rec.json.data.id || rec.json.data));
  await sleep(5000);
  const inflightOpsTask = await completeOps(admin, recordId, 'R06I');
  await sleep(7000);
  const opsTd = await api(admin, 'GET', '/workflow/tasks/' + inflightOpsTask);
  const parentInst = { processInstanceId: opsTd.json.data && (opsTd.json.data.processInstanceId || (opsTd.json.data.instance && opsTd.json.data.instance.processInstanceId)) };
  if (!parentInst.processInstanceId) throw new Error('parent instance not found from ops task detail');
  step('A2 inflight parent instance', { id: parentInst.processInstanceId });
  const batchA = await api(admin, 'GET', '/workflow/instances/' + parentInst.processInstanceId + '/child-batches');
  const inflightItems = (batchA.json.data || [])[0] && batchA.json.data[0].items.map((i) => ({ id: i.id, status: i.status, targetInstanceId: i.targetInstanceId }));
  step('A3 inflight batch', { status: (batchA.json.data || [])[0] && batchA.json.data[0].status, items: inflightItems });

  // ===== B. 关闭新入口：发布去触发 v4 =====
  const graph = await api(admin, 'GET', '/workflow/defs/' + PARENT_DEF_ID);
  if (graph.json.code !== 0) throw new Error('def graph read failed');
  const g = graph.json.data;
  const removedTriggers = g.triggers || [];
  const removedWaitRefs = [];
  for (const el of (g.elements || [])) {
    if (el.kind === 'node' && String(el.type || '').toUpperCase() === 'SUBFLOW_WAIT') {
      const inEdge = (g.elements || []).find((e) => e.kind === 'edge' && e.target === el.id);
      const outEdge = (g.elements || []).find((e) => e.kind === 'edge' && e.source === el.id);
      removedWaitRefs.push({ nodeId: el.id, waitActionIds: el.config && el.config.waitActionIds,
        reconnected: inEdge && outEdge ? { from: inEdge.source, to: outEdge.target } : null });
      g.elements = (g.elements || []).filter((x) => x !== el && x !== inEdge && x !== outEdge);
      if (inEdge && outEdge && inEdge !== outEdge) {
        g.elements.push({ id: 'edge_close_' + el.id, kind: 'edge', source: inEdge.source, target: outEdge.target });
      }
    }
  }
  const v4Graph = { ...g, triggers: [] };
  const save = await api(admin, 'PUT', '/workflow/defs/' + PARENT_DEF_ID + '/graph', v4Graph);
  step('B1 draft graph saved (triggers+waitActionIds removed)', { code: save.json.code, msg: save.json.msg, removedTriggerIds: removedTriggers.map((t) => t.triggerId), removedWaitRefs });
  const pub = await api(admin, 'POST', '/workflow/defs/' + PARENT_DEF_ID + '/publish');
  step('B2 publish v4', { code: pub.json.code, msg: pub.json.msg, defVersion: pub.json.data && pub.json.data.defVersion, status: pub.json.data && pub.json.data.status });
  if (pub.json.code !== 0) throw new Error('publish failed: ' + pub.json.msg);
  fs.writeFileSync(OUT + '/06-inflight-v4-graph.json', JSON.stringify({ at: new Date().toISOString(), publishedGraph: v4Graph, removedTriggers }, null, 2));

  // ===== C. 新入口零意图：新父实例走完运维节点 =====
  const recN = await api(admin, 'POST', '/form/data/p64p2b_parent_form', {
    title: 'R06新入口零意图',
    hosts: [{ host_name: 'host-r06-n1', owner: actors.zhangsanId, feedback: '' }],
  });
  const recordIdN = String(recN.json.data && (recN.json.data.id || recN.json.data));
  await sleep(5000);
  const opsTaskIdN = await completeOps(admin, recordIdN, 'R06N');
  await sleep(6000);
  const instQN = await api(admin, 'POST', '/workflow/instances/query', { pageNum: 1, pageSize: 30, filters: [] });
  const rowsN = (instQN.json.data && (instQN.json.data.records || instQN.json.data.list)) || [];
  const opsTdN = await api(admin, 'GET', '/workflow/tasks/' + opsTaskIdN);
  const newInst = { processInstanceId: opsTdN.json.data && (opsTdN.json.data.processInstanceId || (opsTdN.json.data.instance && opsTdN.json.data.instance.processInstanceId)) };
  step('C1 new entry instance', { id: newInst.processInstanceId });
  const batchN = await api(admin, 'GET', '/workflow/instances/' + newInst.processInstanceId + '/child-batches');
  step('C2 new entry batches (expect empty)', { code: batchN.json.code, count: (batchN.json.data || []).length });

  // ===== D. 在役收敛：张三/李四完成子任务 =====
  async function completeChild(token, label) {
    const td = await api(token, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
    const trows = (td.json.data && (td.json.data.records || td.json.data.list)) || [];
    const childRow = trows.find((t) => (t.processName || '').includes('子反馈'));
    if (!childRow) { step('D child not found ' + label, {}); return null; }
    const taskId = String(childRow.taskId || childRow.id);
    const childRecordId = String(childRow.businessKey || '');
    const cnf = await api(token, 'GET', '/workflow/tasks/' + taskId + '/node-form');
    // 带授权来源行提交（子记录 create_by=本人，接缝保证本人可读）
    const childRec = await api(token, 'GET', '/form/data/p64p2b_child_form/' + childRecordId);
    if (childRec.json.code !== 0) throw new Error(label + ' cannot read own child record: ' + childRec.json.msg);
    const tfield = ((cnf.json.data && cnf.json.data.definition && cnf.json.data.definition.fields) || [])
      .find((f) => f.type === 'TABLE');
    const srcRows = (childRec.json.data && childRec.json.data[tfield.name]) || [];
    if (!srcRows.length) throw new Error(label + ' no authorized source rows');
    const childFormData = { summary: label + '-FB' };
    childFormData[tfield.name] = srcRows.map((r) => ({ src_row_id: r.src_row_id, feedback: label + '-OK' }));
    const cdone = await api(token, 'POST', '/workflow/commands/tasks/' + taskId + '/complete',
      { action: 'APPROVE', nodeFormData: childFormData });
    step('D child complete ' + label, { taskId, code: cdone.json.code, msg: cdone.json.msg });
    return { taskId, recordId: String(childRow.businessKey || '') };
  }
  const zsDone = await completeChild(zs, 'R06I-ZS');
  await sleep(3000);
  const lsDone = await completeChild(ls, 'R06I-LS');
  await sleep(7000);

  // 批次结算 + 父唤醒
  const batchD = await api(admin, 'GET', '/workflow/instances/' + parentInst.processInstanceId + '/child-batches');
  step('D2 batch settled', { status: (batchD.json.data || [])[0] && batchD.json.data[0].status,
    settled: (batchD.json.data || [])[0] && [batchD.json.data[0].settledCount, batchD.json.data[0].expectedCount] });

  // 复核任务（父唤醒后下一节点）由 admin 完成至 APPROVED
  await sleep(3000);
  let reviewDone = null;
  for (let i = 0; i < 3; i++) {
    const rv = await findOpsTask(admin, recordId);
    if (rv) {
      const rTaskId = String(rv.taskId || rv.id);
      const rnf = await api(admin, 'GET', '/workflow/tasks/' + rTaskId + '/node-form');
      const rfd = {};
      for (const f of ((rnf.json.data && rnf.json.data.definition && rnf.json.data.definition.fields) || [])) {
        if (f.type === 'TABLE') continue;
        rfd[f.name] = f.type === 'NUMBER' ? 1 : 'R06R-' + (f.label || f.name);
      }
      const rdone = await api(admin, 'POST', '/workflow/commands/tasks/' + rTaskId + '/complete',
        { action: 'APPROVE', nodeFormData: rfd });
      reviewDone = { taskId: rTaskId, code: rdone.json.code, msg: rdone.json.msg };
      break;
    }
    await sleep(3000);
  }
  step('D3 review complete', reviewDone);
  await sleep(5000);

  // ===== E. 在役查询路径 + 终态 =====
  const instE = await api(admin, 'POST', '/workflow/instances/query', { pageNum: 1, pageSize: 30, filters: [] });
  const rowsE = (instE.json.data && (instE.json.data.records || instE.json.data.list)) || [];
  const mineE = rowsE.filter((i) => [recordId, recordIdN, zsDone && zsDone.recordId, lsDone && lsDone.recordId].includes(String(i.businessKey)))
    .map((i) => ({ id: i.processInstanceId, bk: i.businessKey, status: i.status, defVersion: i.defVersion }));
  step('E1 instances final', { mineE });
  const batchE = await api(admin, 'GET', '/workflow/instances/' + parentInst.processInstanceId + '/child-batches');
  step('E2 inflight readback after converge', { code: batchE.json.code,
    status: (batchE.json.data || [])[0] && batchE.json.data[0].status });

  fs.writeFileSync(OUT + '/06-inflight-close-identity.json', JSON.stringify({
    at: new Date().toISOString(),
    parentDefId: PARENT_DEF_ID,
    inflight: { parentRecordId: recordId, opsTaskId: inflightOpsTask, parentInstanceId: parentInst.processInstanceId, batchBefore: batchA.json.data, itemsBefore: inflightItems },
    closedEntry: { newVersion: pub.json.data && pub.json.data.defVersion, removedTriggerIds: removedTriggers.map((t) => t.triggerId), removedWaitRefs, newEntry: { parentRecordId: recordIdN, instanceId: newInst && newInst.processInstanceId, defVersion: newInst && newInst.defVersion, batchCount: (batchN.json.data || []).length } },
    children: { zhangsan: zsDone, lisi: lsDone },
    reviewDone,
    finalInstances: mineE,
    batchAfter: batchE.json.data,
    steps,
  }, null, 2));
  console.log('SAVED inflight identity');
})().catch((e) => { console.error('FATAL', e.message); process.exit(1); });
