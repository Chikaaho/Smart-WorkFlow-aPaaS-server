// P64 回执04：证据树清单生成（工具生成 + 自校验回读）
// 用法: node p64p2h-r04-manifest.cjs
const fs = require('fs');
const crypto = require('crypto');
const path = require('path');
const EV = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-04';

function walk(dir, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, acc);
    else acc.push(p);
  }
  return acc;
}
const files = walk(EV).filter((f) => !f.endsWith('MANIFEST.sha256') && !f.endsWith('INDEX.md')).sort();
const lines = [];
for (const f of files) {
  const h = crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
  lines.push(h + '  ' + path.relative(EV, f).replace(/\\/g, '/'));
}
fs.writeFileSync(path.join(EV, 'MANIFEST.sha256'), lines.join('\n') + '\n', 'utf8');
console.log('manifest entries=' + lines.length);
// 回读校验
const check = fs.readFileSync(path.join(EV, 'MANIFEST.sha256'), 'utf8').trim().split('\n');
let ok = 0; let bad = [];
for (const line of check) {
  const [h, rel] = line.split(/\s{2}/);
  const h2 = crypto.createHash('sha256').update(fs.readFileSync(path.join(EV, rel))).digest('hex');
  if (h === h2) ok++; else bad.push(rel);
}
console.log('verify ok=' + ok + '/' + check.length + (bad.length ? ' BAD=' + JSON.stringify(bad) : ''));
