const fs = require('fs');
const [path, out, ...methods] = process.argv.slice(2);
const lines = fs.readFileSync(path, 'utf8').split(/\r?\n/);
let result = [];
for (const m of methods) {
  const esc = m.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const pat = new RegExp('^\\s*(?:public|private|protected)?\\s*[\\w<>,\\[\\] .]+\\s+' + esc + '\\s*\\(');
  const idx = lines.findIndex((l) => pat.test(l));
  if (idx < 0) { result.push('!! method not found: ' + m + '\n'); continue; }
  let start = idx, j = idx - 1;
  while (j >= 0) {
    const t = lines[j].trim();
    if (t === '') {
      let k = j - 1;
      while (k >= 0 && lines[k].trim() === '') k--;
      if (k >= 0 && lines[k].trim().startsWith('@')) { j = k; start = j; continue; }
      break;
    }
    if (t.startsWith('@') || t.startsWith('//') || t.startsWith('*') || t.startsWith('/**') || t.startsWith('*/')) { start = j; j--; continue; }
    break;
  }
  let depth = 0, seen = false, end = idx;
  for (let i = idx; i < lines.length; i++) {
    for (const ch of lines[i]) { if (ch === '{') { depth++; seen = true; } else if (ch === '}') depth--; }
    if (seen && depth === 0) { end = i; break; }
  }
  result.push('// ---- ' + m + '  (lines ' + (start + 1) + '-' + (end + 1) + ') ----\n' + lines.slice(start, end + 1).join('\n') + '\n\n');
}
const header = '// 原件提取：' + path + '\n'
  + '// 提取规则：按方法实际起止边界（花括号配平）原样提取，含 @Test/@DisplayName 注解与行内注释；不改写断言\n'
  + '// 提取时点：2026-10-10；来源文件最后提交=Server 仓 4319bb4\n\n';
fs.writeFileSync(out, header + result.join(''), 'utf8');
console.log('OK', out, result.length, 'methods');
