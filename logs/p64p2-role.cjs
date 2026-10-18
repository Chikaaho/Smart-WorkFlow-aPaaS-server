const fs = require('fs');
const BASE = 'http://127.0.0.1:8080/api';
const OUT = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const token = fs.readFileSync(OUT + '/p64p2-api-token.txt', 'utf8').trim();
const actors = JSON.parse(fs.readFileSync(OUT + '/p64p2-actors.json', 'utf8'));

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
function ok(label, r) {
  if (r.json.code !== 0) throw new Error(label + ' -> ' + JSON.stringify(r.json).slice(0, 300));
  return r.json.data;
}

(async () => {
  // 1) 角色：业务办理（仅授予表单数据读取/提交按钮权限）
  const page = await api('POST', '/system/role/page?pageNum=1&pageSize=50', {});
  const rows = (page.json.data && (page.json.data.records || page.json.data.list)) || [];
  let roleId = rows.find((r) => r.code === 'biz_handler') ? String(rows.find((r) => r.code === 'biz_handler').id) : null;
  if (!roleId) {
    roleId = String(ok('create role', await api('POST', '/system/role', { name: '业务办理', code: 'biz_handler', sort: 50, status: 1, remark: 'P64阶段Ⅱ验证：表单数据读取/提交按钮权限' })));
  }
  console.log('roleId=' + roleId);

  // 2) 角色菜单：353=form:data:query、350=form:data:submit
  const menus = ok('assign menus', await api('PUT', '/system/role/' + roleId + '/menus', [353, 350]));
  console.log('menus assigned');
  const back = await api('GET', '/system/role/' + roleId + '/menus');
  console.log('role menus readback=' + JSON.stringify(back.json.data));

  // 3) 用户角色：张三/李四
  for (const [name, uid] of [['zhangsan', actors.zhangsanId], ['lisi', actors.lisiId]]) {
    const cur = await api('GET', '/system/user/' + uid + '/roles');
    const curRoles = (cur.json.data || []).map(String);
    const next = Array.from(new Set([...curRoles, roleId]));
    const upd = await api('PUT', '/system/user/' + uid + '/roles', next);
    console.log(name + ' roles ' + JSON.stringify(curRoles) + ' -> ' + JSON.stringify(next) + ' code=' + upd.json.code);
  }
  fs.writeFileSync(OUT + '/p64p2-role.json', JSON.stringify({ roleId }, null, 2));
})();
