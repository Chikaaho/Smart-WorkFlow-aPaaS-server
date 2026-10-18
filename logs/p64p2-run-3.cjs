const fs = require('fs');
const BASE = 'http://127.0.0.1:8080/api';
const OUT = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const token = fs.readFileSync(OUT + '/p64p2-api-token.txt', 'utf8').trim();

async function api(method, path, body) {
  const r = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return await r.json().catch(() => ({}));
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function todo() {
  const t = await api('GET', '/workflow/tasks/todo');
  return (t.data && t.data.records) || [];
}

async function main() {
  // 清理：废弃所有 RUNNING 父实例（验证环境自建数据）
  const inst = await api('GET', '/workflow/instances?pageNum=1&pageSize=20');
  for (const row of ((inst.data && inst.data.records) || []).filter((x) => x.status === 'RUNNING')) {
    await api('POST', '/workflow/instances/' + row.processInstanceId + '/discard', {});
    console.log('discarded ' + row.processInstanceId);
  }
  // 重新发起
  const record = await api('POST', '/form/data/p64p2_parent_form', {
    title: 'P64主机反馈-初始',
    hosts: [
      { host_name: 'host-01', owner: '张三', feedback: '' },
      { host_name: 'host-02', owner: '张三', feedback: '' },
    ],
  });
  const parentRecord = record.data;
  await sleep(2500);
  const list = await api('GET', '/workflow/instances?pageNum=1&pageSize=5');
  const run = ((list.data && list.data.records) || []).find((x) => x.status === 'RUNNING');
  const parentInstance = run.processInstanceId;
  console.log('parent instance=' + parentInstance + ' record=' + parentRecord);

  // 完成 node_ops
  let tasks = await todo();
  const ops = tasks.find((t) => t.processInstanceId === parentInstance);
  const opsApprove = await api('POST', '/workflow/commands/tasks/' + ops.taskId + '/complete', {
    action: 'APPROVE', opinion: '运维填报完成',
    nodeFormData: {
      title: 'P64主机反馈-初始',
      hosts: [
        { host_name: 'host-01', owner: '张三', feedback: '' },
        { host_name: 'host-02', owner: '张三', feedback: '' },
      ],
    },
  });
  console.log('ops approve code=' + opsApprove.code + ' command=' + (opsApprove.data && opsApprove.data.commandId));

  // 轮询：批次 + 子任务
  let batchList = [];
  let childTask = null;
  for (let i = 0; i < 20; i++) {
    await sleep(2000);
    const b = await api('GET', '/workflow/instances/' + parentInstance + '/child-batches');
    batchList = b.data || [];
    tasks = await todo();
    childTask = tasks.find((t) => t.processInstanceId !== parentInstance);
    if (batchList.length > 0 && childTask) break;
  }
  console.log('batches=' + JSON.stringify(batchList).slice(0, 500));
  console.log('childTask=' + (childTask && childTask.taskId));

  // 子办理：写回 summary
  const childApprove = await api('POST', '/workflow/commands/tasks/' + childTask.taskId + '/complete', {
    action: 'APPROVE', opinion: '子反馈完成',
    nodeFormData: { summary: '反馈完成-张三', result_table: [] },
  });
  console.log('child approve code=' + childApprove.code + ' command=' + (childApprove.data && childApprove.data.commandId));

  // 轮询：父推进到 node_review
  let review = null;
  for (let i = 0; i < 15; i++) {
    await sleep(2000);
    tasks = await todo();
    review = tasks.find((t) => t.processInstanceId === parentInstance);
    if (review) break;
  }
  const b2 = await api('GET', '/workflow/instances/' + parentInstance + '/child-batches');
  console.log('after child: batches=' + JSON.stringify(b2.data).slice(0, 700));
  console.log('reviewTask=' + (review && review.taskId));

  // 复核通过
  if (review) {
    const rev = await api('POST', '/workflow/commands/tasks/' + review.taskId + '/complete', {
      action: 'APPROVE', opinion: '复核通过',
      nodeFormData: { title: 'P64主机反馈-复核', hosts: [] },
    });
    console.log('review approve code=' + rev.code);
  }
  await sleep(3000);
  const rec = await api('GET', '/form/data/p64p2_parent_form/' + parentRecord);
  const inst2 = await api('GET', '/workflow/instances?pageNum=1&pageSize=10');
  const row2 = ((inst2.data && inst2.data.records) || []).find((x) => x.processInstanceId === parentInstance);
  const b3 = await api('GET', '/workflow/instances/' + parentInstance + '/child-batches');
  const execs = await api('GET', '/workflow/instances/' + parentInstance + '/trigger-execs');
  const refs = await api('GET', '/workflow/instances/' + parentInstance + '/action-refs');
  const result = {
    parentInstance, parentRecord,
    parentStatus: row2 && row2.status,
    parentRecordData: rec && rec.data,
    childBatches: b3.data, triggerExecs: execs.data, actionRefs: refs.data,
  };
  fs.writeFileSync(OUT + '/p64p2-run-result.json', JSON.stringify(result, null, 2));
  console.log('parent status=' + result.parentStatus);
  console.log('parent title=' + (result.parentRecordData && result.parentRecordData.title));
  console.log('CHAIN DONE');
}
main().catch((e) => { console.error(String(e)); process.exit(1); });
