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
  return { status: r.status, json: await r.json().catch(() => ({})) };
}
function must(label, r) {
  if (r.json.code !== 0) throw new Error(label + ' -> ' + JSON.stringify(r.json).slice(0, 300));
  return r.json.data;
}

async function main() {
  // 1) 提交父表单发起父流程（表单数据提交与流程发起同链）
  const submit = must('submit parent form', await api('POST', '/form/data/' + scen.parentForm, {
      title: 'P64主机反馈-初始',
      hosts: [
        { host_name: 'host-01', owner: '张三', feedback: '' },
        { host_name: 'host-02', owner: '张三', feedback: '' },
      ],
  }));
  const recordId = submit.recordId || submit.id || submit;
  console.log('parent recordId=' + recordId);

  // 2) 查父实例与 node_ops 任务
  const instances = must('my instances', await api('GET', '/workflow/my/instances?pageNum=1&pageSize=10'));
  const inst = (instances.records || instances.list || []).find((x) => x.formKey === scen.parentForm)
    || (instances.records || instances.list || [])[0];
  console.log('parent instance=' + JSON.stringify({ id: inst && inst.processInstanceId, status: inst && inst.status }));

  const todo = must('todo', await api('GET', '/workflow/tasks/todo?pageNum=1&pageSize=20'));
  const todoRows = todo.records || todo.list || [];
  console.log('todo count=' + todoRows.length);
  console.log(JSON.stringify(todoRows.map((t) => ({ taskId: t.taskId || t.id, node: t.taskDefinitionKey || t.nodeKey, name: t.name }))));
}
main().catch((e) => { console.error(String(e)); process.exit(1); });
