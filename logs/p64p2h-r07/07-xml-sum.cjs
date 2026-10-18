// P64 回执07 P2-07b-G：surefire XML 逐类清单工具（类名/tests/failures/errors/skipped/SHA256/mtime）
// 用法: node 07-xml-sum.cjs <surefireDir> <outFile> [filterSubstring]
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const dir = process.argv[2];
const out = process.argv[3];
const filter = process.argv[4] || '';
const files = fs.readdirSync(dir).filter((f) => f.startsWith('TEST-') && f.endsWith('.xml')).sort();
const rows = [];
let T = 0, F = 0, E = 0, S = 0;
for (const f of files) {
  const p = path.join(dir, f);
  const raw = fs.readFileSync(p, 'utf8');
  const m = raw.match(/<testsuite[^>]*name="([^"]*)"[^>]*tests="(\d+)"[^>]*>/);
  const attr = (k) => { const mm = raw.match(new RegExp(k + '="(\\d+)"')); return mm ? Number(mm[1]) : null; };
  const name = m ? m[1] : f;
  if (filter && !name.includes(filter) && !f.includes(filter)) continue;
  const tests = attr('tests'), fail = attr('failures'), err = attr('errors'), sk = attr('skipped');
  const sha = crypto.createHash('sha256').update(raw).digest('hex');
  const mt = fs.statSync(p).mtime.toISOString();
  rows.push({ file: f, class: name, tests, failures: fail, errors: err, skipped: sk, sha256: sha.slice(0, 16), mtime: mt });
  T += tests || 0; F += fail || 0; E += err || 0; S += sk || 0;
}
const body = rows.map((r) => `${r.class} | tests=${r.tests} fail=${r.failures} err=${r.errors} skip=${r.skipped} | ${r.sha256} | ${r.mtime} | ${r.file}`).join('\n');
const summary = `\nXML-COUNT=${rows.length} TESTS=${T} FAILURES=${F} ERRORS=${E} SKIPPED=${S}\nDIR=${dir}\nGENERATED_AT=${new Date().toISOString()}\nCMD=node logs/p64p2h-r07/07-xml-sum.cjs ${dir} <out>${filter ? ' ' + filter : ''}\n`;
fs.writeFileSync(out, body + '\n' + summary);
console.log('XML-COUNT=' + rows.length, 'TESTS=' + T, 'FAILURES=' + F, 'ERRORS=' + E, 'SKIPPED=' + S);
