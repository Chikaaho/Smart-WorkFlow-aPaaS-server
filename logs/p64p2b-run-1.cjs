const fs = require('fs');
const BASE = 'http://127.0.0.1:8080/api';
const OUT = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const token = fs.readFileSync(OUT + '/p64p2-api-token.txt', 'utf8').trim();
const actors = JSON.parse(fs.readFileSync(OUT + '/p64p2-actors.json', 'utf8'));
const scen = JSON.parse(fs.readFileSync(OUT + '/p64p2b-scenario.json', 'utf8'));

function safeParse(text) {
  const quoted = text.replace(/([:\[,]\s*)(-?\d{16,})(?=\s*[,\}\]])/g, '$1"$2"');
  return JSON.parse(quoted);
}
async function api(method, path, body) {
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
  // 0) 清理旧链：废弃所有 RUNNING 实例（验证环境自建数据）
  const inst0 = await api('GET', '/workflow/instances?pageNum=1&pageSize=30');
  const list0 = (inst0.json.data && (inst0.json.data.records || inst0.json.data.list)) || [];
  for (const row of list0.filter((x) => x.status === 'RUNNING')) {
    const d = await api('POST', '/workflow/instances/' + String(row.processInstanceId) + '/discard', {});
    console.log('discarded ' + row.processInstanceId + ' code=' + d.json.code);
  }
  await sleep(1500);

  // 1) 提交父表单（张三 2 行 + 李四 1 行）
  const rows = [
    { host_name: 'host-01', owner: actors.zhangsanId, feedback: '' },
    { host_name: 'host-02', owner: actors.zhangsanId, feedback: '' },
    { host_name: 'host-03', owner: actors.lisiId, feedback: '' },
  ];
  const submit = await api('POST', '/form/data/' + scen.parentForm, { title: 'P64B主机反馈-初始', hosts: rows });
  if (submit.json.code !== 0) throw new Error('submit failed: ' + JSON.stringify(submit.json).slice(0, 300));
  const parentRecord = String(submit.json.data.recordId || submit.json.data.id || submit.json.data);
  console.log('parentRecord=' + parentRecord);
  await sleep(2500);

  const inst = await api('GET', '/workflow/instances?pageNum=1&pageSize=10');
  const list = (inst.json.data && (inst.json.data.records || inst.json.data.list)) || [];
  const parent = list.find((x) => x.formKey === scen.parentForm && x.status === 'RUNNING');
  const parentInstance = String(parent.processInstanceId);
  console.log('parentInstance=' + parentInstance);

  const todo = await api('GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
  const todos = (todo.json.data && (todo.json.data.records || todo.json.data.list)) || [];
  const ops = todos.find((t) => String(t.processInstanceId) === parentInstance);
  const opsTask = String(ops.taskId || ops.id);
  const opsDone = await api('POST', '/workflow/commands/tasks/' + opsTask + '/complete', {
    action: 'APPROVE', opinion: '运维填报完成',
    nodeFormData: { title: 'P64B主机反馈-初始', hosts: rows },
  });
  console.log('ops complete code=' + opsDone.json.code + ' ' + (opsDone.json.msg || ''));

  // 2) 轮询批次 + 子实例/任务
  let batches = [];
  let childTodos = [];
  for (let i = 0; i < 30; i++) {
    await sleep(2000);
    const b = await api('GET', '/workflow/instances/' + parentInstance + '/child-batches');
    batches = b.json.data || [];
    const t = await api('GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
    const all = (t.json.data && (t.json.data.records || t.json.data.list)) || [];
    childTodos = all.filter((x) => String(x.processInstanceId) !== parentInstance);
    if (batches.length > 0 && childTodos.length >= 2) break;
  }
  console.log('batches=' + JSON.stringify(batches).slice(0, 1100));
  console.log('child todos=' + JSON.stringify(childTodos.map((t) => ({ task: String(t.taskId || t.id), inst: String(t.processInstanceId), name: t.name }))));

  fs.writeFileSync(OUT + '/p64p2b-run.json', JSON.stringify({
    parentRecord, parentInstance, opsTask, batches,
    childTodos: childTodos.map((t) => ({ task: String(t.taskId || t.id), inst: String(t.processInstanceId) })),
    rows, actors,
  }, null, 2));
  console.log('CHAIN-1 DONE');
}
main().catch((e) => { console.error(String(e)); process.exit(1); });
