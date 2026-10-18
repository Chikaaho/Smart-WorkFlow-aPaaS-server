const fs = require('fs');
const BASE = 'http://127.0.0.1:8080/api';
const OUT = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const token = fs.readFileSync(OUT + '/p64p2-api-token.txt', 'utf8').trim();

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
  try { json = safeParse(text); } catch { json = { raw: text.slice(0, 200) }; }
  return { status: r.status, json };
}
function ok(label, r) {
  if (r.json.code !== 0) throw new Error(label + ' -> ' + JSON.stringify(r.json).slice(0, 300));
  return r.json.data;
}

async function main() {
  // 1) 部门：运维部（挂在根部门 1 下）
  let deptId = null;
  const deptTree = await api('GET', '/system/dept/tree');
  const flat = [];
  const walk = (nodes) => (nodes || []).forEach((n) => { flat.push(n); walk(n.children); });
  walk(deptTree.json.data || []);
  const foundDept = flat.find((d) => d.name === '运维部');
  if (foundDept) deptId = String(foundDept.id);
  else deptId = String(ok('create dept', await api('POST', '/system/dept', { parentId: 1, name: '运维部', code: 'ops_dept', sort: 10, status: 0 })));
  console.log('deptId=' + deptId);

  // 2) 岗位：运维岗 / 主管岗（status=1 启用）
  async function ensurePost(code, name) {
    const page = await api('POST', '/system/post/page?pageNum=1&pageSize=50', {});
    const rows = (page.json.data && (page.json.data.records || page.json.data.list)) || [];
    const found = rows.find((p) => p.code === code);
    if (found) return String(found.id);
    return String(ok('create post ' + code, await api('POST', '/system/post', { code, name, sort: 10, status: 1, description: 'P64阶段Ⅱ验证岗位' })));
  }
  const opsPostId = await ensurePost('ops_post', '运维岗');
  const leadPostId = await ensurePost('lead_post', '主管岗');
  console.log('opsPostId=' + opsPostId + ' leadPostId=' + leadPostId);

  // 3) 用户：张三/李四（岗位任职）+ 甲/乙/丙/丁（聚合会签对象）
  async function ensureUser(username, realName, posts) {
    const page = await api('POST', '/system/user/page?pageNum=1&pageSize=100', {});
    const rows = (page.json.data && (page.json.data.records || page.json.data.list)) || [];
    const found = rows.find((u) => u.username === username);
    if (found) return String(found.id);
    const body = { username, realName, plainPassword: 'admin123', deptId, status: 0 };
    if (posts) body.posts = posts.map((p) => ({ postId: String(p.postId), deptId: String(p.deptId) }));
    return String(ok('create user ' + username, await api('POST', '/system/user', body)));
  }
  const zhangsanId = await ensureUser('zhangsan', '张三', [{ postId: opsPostId, deptId }]);
  const lisiId = await ensureUser('lisi', '李四', [{ postId: leadPostId, deptId }]);
  const jiaId = await ensureUser('jia', '甲', [{ postId: opsPostId, deptId }]);
  const yiId = await ensureUser('yi', '乙', [{ postId: opsPostId, deptId }]);
  const bingId = await ensureUser('bing', '丙', [{ postId: opsPostId, deptId }]);
  const dingId = await ensureUser('ding', '丁', [{ postId: opsPostId, deptId }]);

  const ids = { deptId, opsPostId, leadPostId, zhangsanId, lisiId, jiaId, yiId, bingId, dingId };
  fs.writeFileSync(OUT + '/p64p2-actors.json', JSON.stringify(ids, null, 2));
  console.log('ACTORS: ' + JSON.stringify(ids));

  // 4) 回读
  const posts = await api('GET', '/system/user/' + zhangsanId + '/posts');
  console.log('zhangsan posts=' + JSON.stringify(posts.json.data));
  const posts2 = await api('GET', '/system/user/' + lisiId + '/posts');
  console.log('lisi posts=' + JSON.stringify(posts2.json.data));
  const uopt = await api('GET', '/system/user/options?limit=50');
  console.log('user options=' + JSON.stringify((uopt.json.data || []).map((o) => ({ id: o.id, name: o.realName || o.username }))));
}
main().catch((e) => { console.error(String(e)); process.exit(1); });
