const fs = require('fs');
const cp = fs.readFileSync('logs/p64p2-full-cp.txt', 'utf8').trim();
const entries = cp.split(';').filter(Boolean);
// JAR 规范：Class-Path 项为 URL；Windows 绝对路径用 file:/E:/... 形式
const urls = entries.map((e) => 'file:/' + e.split('\\').join('/').replace(/ /g, '%20'));
const prefix = 'Class-Path: ';
const lines = [];
let cur = prefix;
for (const u of urls) {
  const piece = (cur === prefix ? '' : ' ') + u;
  if (cur.length + piece.length > 71) {
    lines.push(cur);
    cur = ' ' + u;
  } else {
    cur += piece;
  }
}
lines.push(cur);
fs.writeFileSync('logs/MANIFEST.MF', 'Manifest-Version: 1.0\r\n' + lines.join('\r\n') + '\r\n\r\n');
console.log('manifest v3 lines=' + lines.length);
console.log(lines[0]);
console.log(lines[1].slice(0, 110));
