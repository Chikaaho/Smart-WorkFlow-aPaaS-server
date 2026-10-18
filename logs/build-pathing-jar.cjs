const fs = require('fs');
const path = require('path');
const cp = fs.readFileSync('logs/p64p2-full-cp.txt', 'utf8').trim();
const entries = cp.split(';').filter(Boolean);
const urls = entries.map((e) => {
  let u = e.split('\\').join('/');
  if (!u.startsWith('/')) u = '/' + u;
  return 'file://' + u;
});
// Manifest Class-Path 行需 ≤72 字节，续行以空格开头
const prefix = 'Class-Path: ';
const lines = [];
let current = prefix;
for (const u of urls) {
  const piece = (current === prefix ? '' : ' ') + u;
  if (current.length + piece.length > 71) {
    lines.push(current);
    current = ' ' + u;
  } else {
    current += piece;
  }
}
lines.push(current);
const manifest = 'Manifest-Version: 1.0\r\n' + lines.join('\r\n') + '\r\n\r\n';
fs.mkdirSync('logs/empty', { recursive: true });
fs.writeFileSync('logs/MANIFEST.MF', manifest);
console.log('manifest written, entries=' + urls.length + ' lines=' + lines.length);
