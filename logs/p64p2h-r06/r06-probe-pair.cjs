// P64 回执06 P2-04a：普通办理者（SELF 域）对系统派发对象的实际出口正反探测
// 正向：jia 读自己的派发子记录（详情200/列表含1/办理任务详情200/办理命令受理）
// 反向：jia 读 yi 的子记录 1507；jia 打开 yi 的任务 403；jia 无 form:data:edit 的写 403（功能权限口径登记）
// 全量原始响应落盘；写探针在只读校验后执行且仅对可写对象判定（零变更要求由 403 语义天然保证）
const fs = require('fs');
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const OUT = LOGS + '/p64p2h-r06';
const BASE = 'http://127.0.0.1:8080/api';
const probes = [];
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
function rec(name, r, note) {
  probes.push({ name, status: r.status, code: r.json.code, msg: r.json.msg, note: note || null,
    dataBrief: JSON.stringify(r.json.data).slice(0, 260) });
  console.log(name, 'HTTP', r.status, 'code=', r.json.code, r.json.msg || '');
}
(async () => {
  const identity = JSON.parse(fs.readFileSync(OUT + '/06-dispatch-pair-identity.json', 'utf8'));
  const admin = fs.readFileSync(LOGS + '/p64p2h-r06-token-admin.txt', 'utf8').trim();
  const jia = fs.readFileSync(LOGS + '/p64p2h-r06-token-jia.txt', 'utf8').trim();
  const own = identity.children.jia.recordId;
  const other = identity.children.yi.recordId;
  const ownTask = identity.children.jia.taskId;
  const otherTask = identity.children.yi.taskId;

  // ---- 正向：jia 对自己被派发的子记录 ----
  rec('A1 jia detail own', await api(jia, 'GET', '/form/data/p64p2b_child_form/' + own));
  rec('A2 jia list own-visible', await api(jia, 'POST', '/form/data/p64p2b_child_form/query',
    { pageNum: 1, pageSize: 20, filters: [] }));
  rec('A3 jia admin-list-contrast', await api(admin, 'POST', '/form/data/p64p2b_child_form/query',
    { pageNum: 1, pageSize: 20, filters: [] }));
  rec('A4 jia task detail own', await api(jia, 'GET', '/workflow/tasks/' + ownTask));
  // 办理出口：节点表单读取（含授权来源行预填）
  rec('A5 jia node-form own', await api(jia, 'GET', '/workflow/tasks/' + ownTask + '/node-form'));

  // ---- 反向：jia 对 yi 的派发对象 ----
  rec('B1 jia detail other', await api(jia, 'GET', '/form/data/p64p2b_child_form/' + other));
  rec('B2 jia task detail other', await api(jia, 'GET', '/workflow/tasks/' + otherTask));
  // 写出口（功能权限口径）：jia 无 form:data:edit → 403 功能门；读先于写、零变更
  rec('B3 jia write other (function perm)', await api(jia, 'PUT',
    '/form/data/p64p2b_child_form/' + other, { summary: 'R06-越权写探针' }));

  // ---- 反向对称：yi 对 jia 的派发对象 ----
  const yi = fs.readFileSync(LOGS + '/p64p2h-r06-token-yi.txt', 'utf8').trim();
  rec('C1 yi detail jia-record', await api(yi, 'GET', '/form/data/p64p2b_child_form/' + own));
  rec('C2 yi task detail jia-task', await api(yi, 'GET', '/workflow/tasks/' + ownTask));

  fs.writeFileSync(OUT + '/06-probe-pair-outlets.json', JSON.stringify({ at: new Date().toISOString(), probes }, null, 2));
  console.log('SAVED probes');
})().catch((e) => { console.error('FATAL', e.message); process.exit(1); });
