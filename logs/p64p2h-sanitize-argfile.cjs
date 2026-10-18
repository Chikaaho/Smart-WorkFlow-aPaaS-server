const fs = require('fs');
const SRC = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs/p64p2-java-args.txt';
const DST = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs/p64p2h-java-args.txt';
let s = fs.readFileSync(SRC, 'utf8');
const marker = 'sw-bpm-process-0.1.3-SNAPSHOT.jar';
// 按分号切出 -cp 大行内的条目，剔除指向该 m2 jar 的条目（target/classes 为唯一资源来源）
const parts = s.split(';');
const kept = parts.filter((p) => !p.includes(marker));
fs.writeFileSync(DST, kept.join(';'), 'utf8');
console.log('entries before=' + parts.length + ' after=' + kept.length);
console.log('still contains jar? ' + kept.some((p) => p.includes(marker)));
