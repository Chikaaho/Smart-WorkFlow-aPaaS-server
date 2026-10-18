// P64 回执04：04a 同业务角色跨负责人隔离真实HTTP探针
// 张三/李四各读本人子记录=200；交叉读取对方子记录=403（同业务权限仍隔离）；
// 甲（无业务角色）交叉读取=403（既有对照保留）；管理员对照=200
const fs = require('fs');
const BASE = 'http://127.0.0.1:8080/api';
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const EV = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-04/raw';
const tokenOf = (u) => fs.readFileSync(LOGS + '/p64p2h-r04-token-' + u + '.txt', 'utf8').trim();
// SETTLED 链既有子记录（复审03 §1 锁定对象的在役存续，非本轮新建）
const REC_ZS = 'c899a2c8-2b9d-411c-8bd7-6927931a9559';
const REC_LS = 'f135af8f-e8d5-4e2f-a8ae-bed935927808';

async function probe(token, recordId) {
  const r = await fetch(BASE + '/form/data/p64p2b_child_form/' + recordId, {
    method: 'GET', headers: { Authorization: 'Bearer ' + token } });
  const text = await r.text();
  let head = '';
  try {
    const j = JSON.parse(text);
    head = JSON.stringify(j).slice(0, 260);
  } catch { head = text.slice(0, 260); }
  return { http: r.status, bodyHead: head };
}

(async () => {
  const out = { capturedAt: new Date().toISOString(), endpoint: 'GET /form/data/p64p2b_child_form/{recordId}', probes: [] };
  const push = (label, v) => { out.probes.push({ label, ...v }); console.log(label, JSON.stringify(v).slice(0, 160)); };

  push('张三读本人子记录 ' + REC_ZS, await probe(tokenOf('zhangsan'), REC_ZS));
  push('李四读本人子记录 ' + REC_LS, await probe(tokenOf('lisi'), REC_LS));
  push('张三交叉读取李四子记录 ' + REC_LS, await probe(tokenOf('zhangsan'), REC_LS));
  push('李四交叉读取张三子记录 ' + REC_ZS, await probe(tokenOf('lisi'), REC_ZS));
  push('甲（无业务角色）读张三子记录（对照）', await probe(tokenOf('jia'), REC_ZS));
  push('管理员读张三子记录（对照）', await probe(tokenOf('admin'), REC_ZS));

  fs.writeFileSync(EV + '/live/04a-cross-owner-probes.json', JSON.stringify(out, null, 2), 'utf8');
  console.log('DONE');
})().catch((e) => { console.error('FAIL: ' + e.message); process.exit(1); });
