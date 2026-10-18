const fs = require('fs');
const g = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-03/raw/gates/03-server-gate-bootstrap-anchors.log';
const buf = fs.readFileSync(g);
const decU = new TextDecoder('utf-8', { fatal: true });
const decG = new TextDecoder('gbk');
const out = [];
let fixed = 0;
for (const lineBytes of buf.toString('latin1').split('\n')) {
  const raw = Buffer.from(lineBytes, 'latin1');
  let text;
  try { text = decU.decode(raw); }
  catch (e) { text = decG.decode(raw); fixed++; }
  out.push(text);
}
fs.writeFileSync(g, out.join('\n'), 'utf8');
console.log('gbk lines fixed=' + fixed);
const check = fs.readFileSync(g, 'utf8');
console.log('has U+FFFD now? ' + check.includes('\uFFFD'));
