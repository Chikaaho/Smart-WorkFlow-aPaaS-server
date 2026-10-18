const fs = require('fs');
const BASE = 'http://127.0.0.1:8080/api';
const OUT = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const scen = JSON.parse(fs.readFileSync(OUT + '/p64p2b-scenario.json', 'utf8'));
const actors = JSON.parse(fs.readFileSync(OUT + '/p64p2-actors.json', 'utf8'));
const tokenOf = (u) => fs.readFileSync(OUT + '/p64p2-token-' + u + '.txt', 'utf8').trim();

function safeParse(text) {
  const quoted = text.replace(/([:\[,]\s*)(-?\d{16,})(?=\s*[,\}\]])/g, '$1"$2"');
  return JSON.parse(quoted);
}
async function api(token, method, path, body) {
  const r = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await r.text();
  let json = {};
  try { json = safeParse(text); } catch { json = { raw: text.slice(0, 300) }; }
  return { status: r.status, json };
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const admin = tokenOf('admin');
  const zhangsan = tokenOf('zhangsan');
  const lisi = tokenOf('lisi');

  // 0) 清理旧 RUNNING 实例
  const inst0 = await api(admin, 'GET', '/workflow/instances?pageNum=1&pageSize=30');
  const list0 = (inst0.json.data && (inst0.json.data.records || inst0.json.data.list)) || [];
  for (const row of list0.filter((x) => x.status === 'RUNNING')) {
    await api(admin, 'POST', '/workflow/instances/' + String(row.processInstanceId) + '/discard', {});
  }
  await sleep(1500);

  // 1) 新父链：提交 + node_ops
  const rows = [
    { host_name: 'host-01', owner: actors.zhangsanId, feedback: '' },
    { host_name: 'host-02', owner: actors.zhangsanId, feedback: '' },
    { host_name: 'host-03', owner: actors.lisiId, feedback: '' },
  ];
  const submit = await api(admin, 'POST', '/form/data/' + scen.parentForm, { title: 'P64B冲突恢复链', hosts: rows });
  if (submit.json.code !== 0) throw new Error('submit: ' + JSON.stringify(submit.json).slice(0, 200));
  const parentRecord = String(submit.json.data.recordId || submit.json.data.id || submit.json.data);
  await sleep(2500);
  const inst = await api(admin, 'GET', '/workflow/instances?pageNum=1&pageSize=10');
  const list = (inst.json.data && (inst.json.data.records || inst.json.data.list)) || [];
  const parent = list.find((x) => x.formKey === scen.parentForm && x.status === 'RUNNING');
  const parentInstance = String(parent.processInstanceId);
  const todo = await api(admin, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
  const todos = (todo.json.data && (todo.json.data.records || todo.json.data.list)) || [];
  const ops = todos.find((t) => String(t.processInstanceId) === parentInstance);
  await api(admin, 'POST', '/workflow/commands/tasks/' + String(ops.taskId || ops.id) + '/complete', {
    action: 'APPROVE', opinion: '运维填报完成', nodeFormData: { title: 'P64B冲突恢复链', hosts: rows },
  });
  console.log('parent=' + parentInstance + ' record=' + parentRecord);

  // 2) 等待批次 + 两个子任务
  let batch = null; let zsTask = null; let lsTask = null;
  for (let i = 0; i < 30; i++) {
    await sleep(2000);
    const b = await api(admin, 'GET', '/workflow/instances/' + parentInstance + '/child-batches');
    batch = (b.json.data || [])[0] || null;
    const t1 = await api(zhangsan, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=20');
    const t2 = await api(lisi, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=20');
    const r1 = (t1.json.data && (t1.json.data.records || t1.json.data.list)) || [];
    const r2 = (t2.json.data && (t2.json.data.records || t2.json.data.list)) || [];
    zsTask = r1.find((t) => String(t.processInstanceId) !== parentInstance) || null;
    lsTask = r2.find((t) => String(t.processInstanceId) !== parentInstance) || null;
    if (batch && zsTask && lsTask) break;
  }
  console.log('batch=' + (batch && batch.id) + ' status=' + (batch && batch.status) + ' expected=' + (batch && batch.expectedCount));
  console.log('items=' + JSON.stringify((batch.items || []).map((i) => ({ key: i.itemKey, row: i.sourceRowId, st: i.status }))));
  console.log('zsTask=' + (zsTask && String(zsTask.taskId || zsTask.id)) + ' lsTask=' + (lsTask && String(lsTask.taskId || lsTask.id)));
  const zsInst = zsTask && String(zsTask.processInstanceId);
  const lsInst = lsTask && String(lsTask.processInstanceId);

  // 3) 张三办理（2 行回写 + 主字段 summary→title）
  const zsRows = [{ src_row_id: batch.items.find((i) => i.itemKey === actors.zhangsanId).sourceRowId, feedback: 'host-01正常' }];
  const zsB = batch.items.find((i) => i.itemKey === actors.zhangsanId);
  // 张三的两行：第一行 sourceRowId 已知，第二行从记录读
  const zsDetail = await api(zhangsan, 'GET', '/workflow/tasks/' + String(zsTask.taskId || zsTask.id));
  const zsKey = (zsDetail.json.data || {}).businessKey;
  const zsRec = await api(zhangsan, 'GET', '/form/data/' + scen.childForm + '/' + zsKey);
  const zsTable = (zsRec.json.data && zsRec.json.data.result_table) || [];
  const zsFormRows = zsTable.map((r, idx) => ({ src_row_id: r.src_row_id, feedback: idx === 0 ? 'host-01正常' : 'host-02需复检' }));
  const zsDone = await api(zhangsan, 'POST', '/workflow/commands/tasks/' + String(zsTask.taskId || zsTask.id) + '/complete', {
    action: 'APPROVE', opinion: '张三反馈完成',
    nodeFormData: { summary: '张三汇总-全部已检', result_table: zsFormRows },
  });
  console.log('zhangsan complete code=' + zsDone.json.code + ' ' + (zsDone.json.msg || ''));
  await sleep(3000);

  // 4) 李四办理（1 行回写；主字段同字段 → 版本冲突挂起）
  const lsB = batch.items.find((i) => i.itemKey === actors.lisiId);
  const lsDetail = await api(lisi, 'GET', '/workflow/tasks/' + String(lsTask.taskId || lsTask.id));
  const lsKey = (lsDetail.json.data || {}).businessKey;
  const lsRec = await api(lisi, 'GET', '/form/data/' + scen.childForm + '/' + lsKey);
  const lsTable = (lsRec.json.data && lsRec.json.data.result_table) || [];
  const lsFormRows = lsTable.map((r) => ({ src_row_id: r.src_row_id, feedback: 'host-03正常' }));
  const lsDone = await api(lisi, 'POST', '/workflow/commands/tasks/' + String(lsTask.taskId || lsTask.id) + '/complete', {
    action: 'APPROVE', opinion: '李四反馈完成',
    nodeFormData: { summary: '李四汇总-主机03正常', result_table: lsFormRows },
  });
  console.log('lisi complete code=' + lsDone.json.code + ' ' + (lsDone.json.msg || ''));
  await sleep(4000);

  // 5) 冲突态读回
  const b2 = await api(admin, 'GET', '/workflow/instances/' + parentInstance + '/child-batches');
  const batch2 = (b2.json.data || [])[0] || {};
  console.log('after both: batch status=' + batch2.status + ' block=' + batch2.blockReason);
  console.log('items2=' + JSON.stringify((batch2.items || []).map((i) => ({ key: i.itemKey, st: i.status, err: i.errorText }))));

  // 6) 有权恢复（retry-writeback）
  const conflictItem = (batch2.items || []).find((i) => i.status === 'CONFLICT');
  if (conflictItem) {
    const retry = await api(admin, 'POST', '/workflow/child-items/' + String(conflictItem.id) + '/retry-writeback', {});
    console.log('retry -> code=' + retry.json.code + ' data=' + JSON.stringify(retry.json.data));
    await sleep(4000);
    const b3 = await api(admin, 'GET', '/workflow/instances/' + parentInstance + '/child-batches');
    const batch3 = (b3.json.data || [])[0] || {};
    console.log('after retry: batch status=' + batch3.status + ' settled=' + batch3.settledCount + '/' + batch3.expectedCount + ' settledAt=' + batch3.settledAt);
    console.log('items3=' + JSON.stringify((batch3.items || []).map((i) => ({ key: i.itemKey, st: i.status, wb: (i.writebackJson || '').slice(0, 120) }))));
  }

  // 7) 复核任务（等待节点推进后）
  let review = null;
  for (let i = 0; i < 15; i++) {
    await sleep(2000);
    const t = await api(admin, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
    const all = (t.json.data && (t.json.data.records || t.json.data.list)) || [];
    review = all.find((x) => String(x.processInstanceId) === parentInstance) || null;
    if (review) break;
  }
  console.log('reviewTask=' + (review && String(review.taskId || review.id)));
  if (review) {
    const rev = await api(admin, 'POST', '/workflow/commands/tasks/' + String(review.taskId || review.id) + '/complete', {
      action: 'APPROVE', opinion: '复核通过', nodeFormData: { title: 'P64B冲突恢复链-复核', hosts: [] },
    });
    console.log('review code=' + rev.json.code);
  }
  await sleep(3500);
  const rec = await api(admin, 'GET', '/form/data/' + scen.parentForm + '/' + parentRecord);
  const data = rec.json.data || {};
  console.log('parent title=' + data.title);
  console.log('parent hosts=' + JSON.stringify((data.hosts || []).map((h) => ({ host: h.host_name, owner: h.owner, fb: h.feedback }))));
  const inst2 = await api(admin, 'GET', '/workflow/instances?pageNum=1&pageSize=10');
  const list2 = (inst2.json.data && (inst2.json.data.records || inst2.json.data.list)) || [];
  const p2 = list2.find((x) => String(x.processInstanceId) === parentInstance);
  console.log('parent status=' + (p2 && p2.status));

  fs.writeFileSync(OUT + '/p64p2b-run2.json', JSON.stringify({ parentInstance, parentRecord, batchId: batch && batch.id, reviewTask: review && String(review.taskId || review.id) }, null, 2));
  console.log('CHAIN-2 DONE');
}
main().catch((e) => { console.error(String(e)); process.exit(1); });
