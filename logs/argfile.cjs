const fs = require('fs');
const full = fs.readFileSync('logs/p64p2-full-cp.txt', 'utf8').trim();
const worker = fs.readFileSync('logs/p64p2-worker-cp.txt', 'utf8').trim();
const args = [
  '-Xmx2g',
  '-Dfile.encoding=UTF-8',
  '-Dsw.bpm.script.worker-classpath=' + worker,
  '-cp',
  full,
  'com.sw.ck.bootstrap.StarterApplication',
].join('\n');
fs.writeFileSync('logs/p64p2-java-args.txt', args);
console.log('argfile bytes=' + args.length);
