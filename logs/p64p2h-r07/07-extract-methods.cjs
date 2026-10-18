// P64 回执07 P2-05b-A/P2-06a-C：Java 方法原文提取工具（花括号配平，带行号）
// 用法: node 07-extract-methods.cjs <javaFile> <outFile> <Method1,Method2,...> [MethodN...]
// 输出: 文件SHA256+提取命令+时间戳头 + 每方法 <path>:<startLine>-<endLine> 带行号原文
const fs = require('fs');
const crypto = require('crypto');
const src = process.argv[2];
const out = process.argv[3];
const names = process.argv.slice(4).join(',').split(',').filter(Boolean);
const raw = fs.readFileSync(src, 'utf8');
const lines = raw.split(/\r?\n/);
const found = [];
for (const name of names) {
  const sigRe = new RegExp('^\\s*(public|private|protected|static|final|@.*|\\s)*[\\w<>\\[\\], .]*\\b' + name + '\\s*\\(');
  let start = -1;
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].includes(name + '(') && !lines[i].trim().startsWith('//') && !lines[i].trim().startsWith('*')) {
      // 方法签名/调用判别：行内含 name( 且以 { 结尾或在后续数行内出现 {，且缩进为类成员级（≤8空格）
      if (lines[i].trim().endsWith('{') || lines[i].trim().endsWith('(') || /(\)\s*(\{|$))/.test(lines[i]) || /,\s*$/.test(lines[i].trim())) {
        const indent = lines[i].match(/^\s*/)[0].length;
        if (indent <= 8) { start = i; break; }
      }
    }
  }
  if (start < 0) { console.error('NOT FOUND: ' + name); continue; }
  let depth = 0, end = -1, opened = false;
  for (let i = start; i < lines.length; i++) {
    for (const ch of lines[i]) {
      if (ch === '{') { depth++; opened = true; }
      else if (ch === '}') depth--;
    }
    if (opened && depth === 0) { end = i; break; }
  }
  found.push({ name, start: start + 1, end: end + 1 });
}
const sha = crypto.createHash('sha256').update(raw).digest('hex');
let body = `# 原文提取（工具导出，未改动）\nSRC=${src}\nSRC_SHA256=${sha}\nEXTRACTED_AT=${new Date().toISOString()}\nEXTRACT_CMD=node logs/p64p2h-r07/07-extract-methods.cjs ${src} <out> ${names.join(',')}\nMETHODS=${found.map((f) => f.name + ':' + f.start + '-' + f.end).join('; ')}\n\n`;
for (const f of found) {
  body += `===== ${src}:${f.start}-${f.end} ${f.name} =====\n`;
  for (let i = f.start; i <= f.end; i++) body += String(i).padStart(5) + '\t' + lines[i - 1] + '\n';
  body += '\n';
}
fs.appendFileSync(out, body);
console.log('OK ' + found.map((f) => f.name + ':' + f.start + '-' + f.end).join('; '));
