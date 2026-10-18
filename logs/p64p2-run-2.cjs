const fs = require('fs');
const BASE = 'http://127.0.0.1:8080/api';
const OUT = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const token = fs.readFileSync(OUT + '/p64p2-api-token.txt', 'utf8').trim();
const scen = JSON.parse(fs.readFileSync(OUT + '/p64p2-scenario.json', 'utf8'));

async function api(method, path, body) {
  const r = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return await r.json().catch(() => ({}));
}
async function todo() {
  const t = await api('GET', '/workflow/tasks/todo');
  return (t.data && t.data.records) || [];
}
async function batches(instanceId) {
  const b = await api('GET', '/workflow/instances/' + instanceId + '/child-batches');
  return (b.data) || [];
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const parentInstance = 'b7857f44-c46b-11f1-ab15-00ff9e2a8dfb';
  const parentRecord = '669476c9-7cf8-44a8-9713-c6cfaeaf4f18';

  // 1) 完成 node_ops（父节点表单数据：title + hosts 两行同负责人）
  let tasks = await todo();
  let ops = tasks.find((t) => t.processInstanceId === parentInstance);
  console.log('ops task=' + ops.taskId);
  const approveOps = await api('POST', '/workflow/commands/tasks/' + ops.taskId + '/complete', {
    action: 'APPROVE',
    opinion: '运维填报完成',
    nodeFormData: {
      title: 'P64主机反馈-初始',
      hosts: [
        { host_name: 'host-01', owner: '张三', feedback: '' },
        { host_name: 'host-02', owner: '张三', feedback: '' },
      ],
    },
  });
  console.log('approve ops: ' + JSON.stringify(approveOps).slice(0, 200));

  // 2) 轮询子流程批次与子任务（异步 FLOW_START 创建，有界 40s）
  let batchList = [];
  let childTask = null;
  for (let i = 0; i < 20; i++) {
    await sleep(2000);
    batchList = await batches(parentInstance);
    tasks = await todo();
    childTask = tasks.find((t) => t.processInstanceId !== parentInstance);
    if (batchList.length > 0 && childTask) break;
  }
  console.log('batches=' + JSON.stringify(batchList).slice(0, 600));
  console.log('child task=' + JSON.stringify(childTask && { id: childTask.taskId, inst: childTask.processInstanceId, name: childTask.processName }));

  // 3) 子办理：填写结果（summary=反馈文本；行级列留空——本链回写为主记录字段）
  const childApprove = await api('POST', '/workflow/commands/tasks/' + childTask.taskId + '/complete', {
    action: 'APPROVE',
    opinion: '子反馈完成',
    nodeFormData: { summary: '反馈完成-张三', result_table: [] },
  });
  console.log('approve child: ' + JSON.stringify(childApprove).slice(0, 200));

  // 4) 轮询父推进（等待节点结算 → node_review 任务出现）
  let review = null;
  for (let i = 0; i < 15; i++) {
    await sleep(2000);
    tasks = await todo();
    review = tasks.find((t) => t.processInstanceId === parentInstance);
    if (review) break;
  }
  batchList = await batches(parentInstance);
  console.log('after child: batches=' + JSON.stringify(batchList).slice(0, 700));
  console.log('review task=' + JSON.stringify(review && { id: review.taskId }));

  // 5) 复核通过 → 父 APPROVED；回读父表单记录（回写值）
  if (review) {
    const rev = await api('POST', '/workflow/commands/tasks/' + review.taskId + '/complete', {
      action: 'APPROVE', opinion: '复核通过',
      nodeFormData: { title: 'P64主机反馈-复核', hosts: [] },
    });
    console.log('approve review: ' + JSON.stringify(rev).slice(0, 160));
  }
  await sleep(2500);
  const rec = await api('GET', '/form/data/' + scen.parentForm + '/' + parentRecord);
  console.log('parent record: ' + JSON.stringify(rec).slice(0, 500));
  const inst = await api('GET', '/workflow/instances?pageNum=1&pageSize=5');
  const row = ((inst.data && inst.data.records) || []).find((x) => x.processInstanceId === parentInstance);
  console.log('parent instance status=' + (row && row.status));
  fs.writeFileSync(OUT + '/p64p2-run-result.json', JSON.stringify({
    parentInstance, parentRecord, batchList, parentStatus: row && row.status,
    parentRecordData: rec && rec.data,
  }, null, 2));
  console.log('RUN CHAIN DONE');
}
main().catch((e) => { console.error(String(e)); process.exit(1); });
