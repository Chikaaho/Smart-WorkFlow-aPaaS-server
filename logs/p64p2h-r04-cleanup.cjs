// P64 回执04：自身收尾（对象清理 + 进程/端口精确回读 + 最终对象状态）
// 清理顺序：证据先采集（05a链在清理前已快照）→ 本轮新建实例逐个 discard → 回读
const fs = require('fs');
const { execSync } = require('child_process');
const BASE = 'http://127.0.0.1:8080/api';
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const EV = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-04/raw';
const tokenOf = (u) => fs.readFileSync(LOGS + '/p64p2h-r04-token-' + u + '.txt', 'utf8').trim();

async function api(token, method, path, body) {
  const r = await fetch(BASE + path, {
    method, headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await r.text();
  let json = {}; try { json = JSON.parse(text.replace(/([:\[,]\s*)(-?\d{16,})(?=\s*[,}\]])/g, '$1"$2"')); } catch { json = { raw: text.slice(0, 200) }; }
  return { status: r.status, json };
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const admin = tokenOf('admin');
  const out = { startedAt: new Date().toISOString(), discards: [], remaining: null };

  // 本轮自建实例：委托冻结链 A/B/C（RUNNING）+ 可见主链父实例（APPROVED 后无需 discard）
  const page = await api(admin, 'GET', '/workflow/instances?pageNum=1&pageSize=50');
  const rows = (page.json.data && (page.json.data.records || page.json.data.list)) || [];
  const running = rows.filter((x) => x.status === 'RUNNING');
  out.runningBefore = running.map((x) => ({ id: String(x.processInstanceId), name: x.processName || x.name || null, theme: x.theme || null }));
  for (const row of running) {
    const r = await api(admin, 'POST', '/workflow/instances/' + String(row.processInstanceId) + '/discard', {});
    out.discards.push({ id: String(row.processInstanceId), http: r.status, code: r.json.code, msg: r.json.msg });
  }
  await sleep(1500);
  const page2 = await api(admin, 'GET', '/workflow/instances?pageNum=1&pageSize=50');
  const rows2 = (page2.json.data && (page2.json.data.records || page2.json.data.list)) || [];
  out.remaining = {
    total: rows2.length,
    byStatus: rows2.reduce((acc, x) => { acc[x.status] = (acc[x.status] || 0) + 1; return acc; }, {}),
    running: rows2.filter((x) => x.status === 'RUNNING').map((x) => String(x.processInstanceId)),
  };
  fs.writeFileSync(EV + '/live/04-self-cleanup.json', JSON.stringify(out, null, 2), 'utf8');
  console.log('cleanup done: discarded=' + out.discards.length + ' runningLeft=' + out.remaining.running.length);
})().catch((e) => { console.error('FAIL: ' + e.message); process.exit(1); });
