const fs = require('fs');
const { execSync } = require('child_process');
const ROOT = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-03';
const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = d + '/' + e.name;
    if (e.isDirectory()) walk(p);
    else files.push(p.slice(ROOT.length + 1).replace(/\\/g, '/'));
  }
})(ROOT);
files.sort();
// MANIFEST：排除自身
const manifest = files.filter((f) => f !== 'MANIFEST.sha256').map((f) => {
  const h = execSync('sha256sum "' + ROOT + '/' + f + '"', { encoding: 'utf8' }).trim();
  return h;
}).join('\n') + '\n';
fs.writeFileSync(ROOT + '/MANIFEST.sha256', manifest, 'utf8');
const verify = execSync('cd "' + ROOT + '" && sha256sum -c MANIFEST.sha256 2>&1 | tail -3 && sha256sum -c MANIFEST.sha256 2>/dev/null | grep -c OK', { encoding: 'utf8' });
console.log('manifest entries=' + (manifest.match(/\n/g) || []).length);
console.log(verify);
