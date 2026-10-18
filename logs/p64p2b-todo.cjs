const fs = require('fs');
const BASE = 'http://127.0.0.1:8080/api';
const OUT = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const user = process.argv[2] || 'zhangsan';
const token = fs.readFileSync(OUT + '/p64p2-token-' + user + '.txt', 'utf8').trim();
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

(async () => {
  const todo = await api('GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
  const rows = (todo.json.data && (todo.json.data.records || todo.json.data.list)) || [];
  console.log('[' + user + '] todo count=' + rows.length);
  for (const t of rows) {
    console.log('  task=' + String(t.taskId || t.id) + ' inst=' + String(t.processInstanceId)
      + ' name=' + t.name + ' node=' + (t.taskDefinitionKey || t.nodeKey));
  }
  const first = rows[0];
  if (first) {
    const detail = await api('GET', '/workflow/tasks/' + String(first.taskId || first.id));
    const d = detail.json.data || {};
    console.log('  detail formKey=' + d.formKey + ' businessKey=' + d.businessKey);
    if (d.formKey && d.businessKey) {
      const rec = await api('GET', '/form/data/' + d.formKey + '/' + d.businessKey);
      const data = rec.json.data || {};
      console.log('  child record data=' + JSON.stringify(data).slice(0, 700));
    }
  }
  fs.writeFileSync(OUT + '/p64p2b-todo-' + user + '.json', JSON.stringify({ rows, detail: null }, null, 2));
})();
