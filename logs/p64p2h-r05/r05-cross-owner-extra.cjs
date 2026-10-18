// P64 回执05：P2-04a 正向自建记录对——同业务权限普通办理者(SELF)各自读合法、互读拒绝
// jia 自建 p64p2b_child_form 记录（form:data:submit 合法提交）→ jia 读 200；yi 读同记录 → 1507 无数据
const fs = require('fs');
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const OUT = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-05/raw/live';
const BASE = 'http://127.0.0.1:8080/api';
const probes = [];
function record(label, http, body, note) {
  probes.push({ label, http, body, note: note || '' });
  console.log(label, '->', http, (note || '').slice(0, 90));
}
async function api(token, method, path, bodyObj) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: bodyObj ? JSON.stringify(bodyObj) : undefined,
  });
  const text = await res.text();
  let body; try { body = JSON.parse(text); } catch { body = text.slice(0, 400); }
  return { http: res.status, body };
}
(async () => {
  const jia = fs.readFileSync(LOGS + '/p64p2h-r05-token-jia.txt', 'utf8').trim();
  const yi = fs.readFileSync(LOGS + '/p64p2h-r05-token-yi.txt', 'utf8').trim();
  // jia 合法提交（自身业务权限内）：子反馈表单结构（summary + owner_id + result_table 行）
  const sub = await api(jia, 'POST', '/form/data/p64p2b_child_form', {
    summary: 'P2-04a positive self-record probe (jia)',
    owner_id: '2108817586892435457',
    result_table: [{ id: 'row-jia-1', feedback: 'JIA-OWN-ROW' }],
  });
  record('正向:jia(SELF)自建记录', sub.http, sub.body, 'code=' + sub.body.code);
  const recId = sub.body && sub.body.data;
  if (sub.body.code !== 0 || !recId) throw new Error('jia submit failed');
  const own = await api(jia, 'GET', '/form/data/p64p2b_child_form/' + recId);
  record('正向:jia读本人自建记录', own.http, own.body,
    'code=' + own.body.code + ' create_by=' + (own.body.data && own.body.data.create_by));
  const cross = await api(yi, 'GET', '/form/data/p64p2b_child_form/' + recId);
  record('隔离:yi(SELF)读jia自建记录→须拒绝或无数据', cross.http, cross.body,
    'code=' + cross.body.code + ' data=' + JSON.stringify(cross.body.data));
  // 列表正反：jia 列表恰见本人 1 条；yi 列表 0 条
  const listJ = await api(jia, 'POST', '/form/data/p64p2b_child_form/query', { pageNum: 1, pageSize: 50, filters: [] });
  record('正向:jia列表恰见本人记录', listJ.http, null, 'total=' + (listJ.body.data && listJ.body.data.total));
  const listY = await api(yi, 'POST', '/form/data/p64p2b_child_form/query', { pageNum: 1, pageSize: 50, filters: [] });
  record('隔离:yi列表0条（含jia记录不可见）', listY.http, null, 'total=' + (listY.body.data && listY.body.data.total));
  probes.push({ jiaCreatedRecordId: recId });
  fs.writeFileSync(OUT + '/05a-self-pair-positive-probes.json',
    JSON.stringify({ capturedAt: new Date().toISOString(), probes }, null, 2));
  console.log('SAVED 05a-self-pair-positive-probes.json');
})().catch((e) => { console.error('FATAL', e.message); process.exit(1); });
