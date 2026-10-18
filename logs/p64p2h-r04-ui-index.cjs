// P64 回执04：可见会话网络索引提取（后端 AccessLoggingFilter ACCESS 原行，窗口=主链起止）
// 用法: node p64p2h-r04-ui-index.cjs <backendLog> <startISO> <endISO>
const fs = require('fs');
const EV = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-04/raw';

const [logPath, startIso, endIso] = process.argv.slice(2);
const start = new Date(startIso).getTime() - 2000;
const end = new Date(endIso).getTime() + 2000;
const lines = fs.readFileSync(logPath, 'utf8').split(/\r?\n/);
const out = [];
for (const line of lines) {
  const m = line.match(/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3})[^\s]*\s+\w+\s+\d+ --- \[[^\]]*\]\s+c\.s\.c\.s\.filter\.AccessLoggingFilter\s+:\s+ACCESS (.*)$/);
  if (!m) continue;
  const t = new Date(m[1] + '+08:00').getTime();
  if (t < start || t > end) continue;
  out.push(m[1] + ' ' + m[2]);
}
fs.writeFileSync(EV + '/ui/04-request-index-ui-session.txt',
  '# P64 回执04 可见主链网络索引（后端 AccessLoggingFilter ACCESS 原行）\n'
  + '# 窗口: ' + startIso + ' — ' + endIso + '（含前后各 2s 容差）\n'
  + '# 行数: ' + out.length + '\n\n' + out.join('\n') + '\n', 'utf8');
console.log('index lines=' + out.length);
