const fs = require('fs');
const { execSync } = require('child_process');
const BASE = 'http://127.0.0.1:8080/api';
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const EV = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-03/raw/live';
const token = fs.readFileSync(LOGS + '/p64p2-token-admin.txt', 'utf8').trim();
function safeParse(text) {
  const quoted = text.replace(/([:\[,]\s*)(-?\d{16,})(?=\s*[,\}\]])/g, '$1"$2"');
  return JSON.parse(quoted);
}
(async () => {
  const out = [];
  const list = await fetch(BASE + '/workflow/instances?pageNum=1&pageSize=100', { headers: { Authorization: 'Bearer ' + token } });
  const data = safeParse(await list.text());
  const rows = (data.data && (data.data.records || data.data.list)) || [];
  const running = rows.filter((x) => x.status === 'RUNNING');
  out.push('# P2-07c 自有验证实例清理（废弃=业务终态 DISCARDED，仅限本轮自建验证对象）');
  out.push('# 清理前 RUNNING 实例 ' + running.length + ' 个：');
  for (const r of running) {
    const id = String(r.processInstanceId || r.id);
    const d = await fetch(BASE + '/workflow/instances/' + id + '/discard', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token }, body: '{}' });
    out.push('DISCARD ' + id + ' defKey=' + r.processDefKey + ' businessKey=' + r.businessKey + ' -> HTTP ' + d.status);
  }
  await new Promise((r) => setTimeout(r, 2500));
  const list2 = await fetch(BASE + '/workflow/instances?pageNum=1&pageSize=100', { headers: { Authorization: 'Bearer ' + token } });
  const data2 = safeParse(await list2.text());
  const rows2 = (data2.data && (data2.data.records || data2.data.list)) || [];
  const stillRunning = rows2.filter((x) => x.status === 'RUNNING');
  const counts = {};
  for (const x of rows2) counts[x.status] = (counts[x.status] || 0) + 1;
  out.push('');
  out.push('# 清理后 API 回读：total=' + rows2.length + ' RUNNING=' + stillRunning.length + ' 状态分布=' + JSON.stringify(counts));
  fs.writeFileSync(EV + '/07-self-cleanup-discard.txt', out.join('\n') + '\n', 'utf8');
  console.log(out.join('\n'));
})().catch((e) => { console.error(String(e)); process.exit(1); });
