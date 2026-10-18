const fs = require('fs');
const path = require('path');
const deps = fs.readFileSync('logs/p64p2-cp.txt', 'utf8').trim();
const classes = [];
function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) {
      if (e.name === 'node_modules' || e.name === '.git' || e.name === 'logs') continue;
      if (e.name === 'target') {
        const c = path.join(p, 'classes');
        if (fs.existsSync(c)) classes.push(c);
        continue;
      }
      walk(p);
    }
  }
}
walk('.');
const norm = (s) => s.split(path.sep).join('/');
const cp = [...classes.map((c) => norm(path.resolve(c))), deps].join(';');
fs.writeFileSync('logs/p64p2-full-cp.txt', cp);
console.log('module classes=' + classes.length + ' cp bytes=' + cp.length);
