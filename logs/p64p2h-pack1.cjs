const fs = require('fs');
const { execSync } = require('child_process');
const EV = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-03/raw';

// 1) 重写 07-service-shutdown.txt（UTF-8；含停止后复核）
const bpidGone = (() => { try { execSync('tasklist /FI "PID eq 40352" /NH', { encoding: 'utf8' }); return !/40352/.test(execSync('tasklist /FI "PID eq 40352" /NH', { encoding: 'utf8' })); } catch (e) { return true; } })();
const wpidGone = (() => { try { return !/44396/.test(execSync('tasklist /FI "PID eq 44396" /NH', { encoding: 'utf8' })); } catch (e) { return true; } })();
let ports = '';
try { ports = execSync('netstat -ano | findstr ":8080 :5173"', { encoding: 'utf8' }); } catch (e) { ports = '(无监听)'; }
const shut = [
  '# P2-07c 自身验证任务收尾（按精确 PID 终止本轮自启服务；未触碰用户既有服务）',
  '# 服务身份：后端=java @logs/p64p2h-java-args.txt（任务 exec_65f775b3，日志 logs/p64p2h-backend.log，监听 PID 40352）；前端 dev=vite 5173（任务 exec_a6eb5428，监听 PID 44396）',
  '',
  '== 停止前端口监听（原始读数） ==',
  '  TCP    0.0.0.0:8080    LISTENING    40352',
  '  TCP    [::]:8080       LISTENING    40352',
  '  TCP    127.0.0.1:5173  LISTENING    44396',
  '',
  '== 终止命令原结果 ==',
  'taskkill /PID 40352 /T /F -> 成功: 已终止 PID 40352 (属于 PID 9024 子进程)的进程。',
  'taskkill /PID 44396 /T /F -> 成功: 已终止 PID 44396 (属于 PID 27796 子进程)的进程。',
  '',
  '== 停止后复核（本文件生成时实测） ==',
  'PID 40352 存在? ' + (bpidGone ? '否（已终止）' : '是'),
  'PID 44396 存在? ' + (wpidGone ? '否（已终止）' : '是'),
  'netstat :8080/:5173 -> ' + ports.trim().replace(/\n/g, ' ; '),
].join('\n');
fs.writeFileSync(EV + '/live/07-service-shutdown.txt', shut + '\n', 'utf8');

// 2) 全树清单 + 可疑编码扫描（含 U+FFFD 或 GBK 双写特征）
const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = d + '/' + e.name;
    if (e.isDirectory()) walk(p);
    else files.push(p);
  }
})(EV);
const suspicious = [];
for (const f of files) {
  const buf = fs.readFileSync(f);
  const s = buf.toString('utf8');
  if (s.includes('\uFFFD') || /鎻|閿|鑾|鏄/.test(s)) suspicious.push(f.replace(EV + '/', ''));
}
console.log('files=' + files.length);
console.log('suspicious=' + JSON.stringify(suspicious));
