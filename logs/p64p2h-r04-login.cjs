// P64 回执04：一次性登录工具（challenge→OCR→RSA登录→token落盘；最多5次新挑战重试）
// 用法: node p64p2h-r04-login.cjs <username> [password]
const fs = require('fs');
const crypto = require('crypto');
const { execSync } = require('child_process');
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const BASE = 'http://127.0.0.1:8080/api';

(async () => {
  const user = process.argv[2];
  const password = process.argv[3] || 'admin123';
  if (!user) { console.error('usage: node p64p2h-r04-login.cjs <username> [password]'); process.exit(1); }
  for (let attempt = 1; attempt <= 5; attempt++) {
    const ch = (await (await fetch(BASE + '/auth/challenge')).json()).data;
    if (attempt === 1) {
      // dev profile + ch.dev.test-mock：固定验证码 1234（配置项而非猜测）
      const encrypted1 = (() => { const pk = crypto.createPublicKey({ key: Buffer.from(ch.publicKey, 'base64'), format: 'der', type: 'spki' }); return crypto.publicEncrypt({ key: pk, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' }, Buffer.from(password, 'utf8')).toString('base64'); })();
      const r1 = await fetch(BASE + '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: user, password: encrypted1, captcha: '1234', captchaId: ch.captchaId, timestamp: String(Date.now()) }) });
      const b1 = await r1.json();
      if (b1.code === 0) { fs.writeFileSync(LOGS + '/p64p2h-r04-token-' + user + '.txt', b1.data.accessToken); console.log('OK ' + user + ' token saved (fixed captcha 1234)'); return; }
      console.error(user + ' fixed-captcha attempt failed: ' + JSON.stringify(b1).slice(0, 120) + ' -> fallback OCR');
    }
    fs.writeFileSync(LOGS + '/p64p2h-r04-captcha.png',
      Buffer.from(ch.captchaImage.replace(/^data:image\/png;base64,/, ''), 'base64'));
    const ocr = execSync('java -Dstdout.encoding=UTF-8 --source 21 ' + LOGS + '/p64p2-ocr3.java ' +
      LOGS + '/p64p2h-r04-captcha.png', { encoding: 'utf8' });
    const m = ocr.match(/ANSWER: (\S+)/);
    if (!m) { console.error('OCR failed (attempt ' + attempt + ')'); continue; }
    const code = m[1];
    const publicKey = crypto.createPublicKey({ key: Buffer.from(ch.publicKey, 'base64'), format: 'der', type: 'spki' });
    const encrypted = crypto.publicEncrypt({ key: publicKey, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' },
      Buffer.from(password, 'utf8')).toString('base64');
    const r = await fetch(BASE + '/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: user, password: encrypted, captcha: code, captchaId: ch.captchaId, timestamp: String(Date.now()) }),
    });
    const b = await r.json();
    if (b.code === 0) {
      fs.writeFileSync(LOGS + '/p64p2h-r04-token-' + user + '.txt', b.data.accessToken);
      console.log('OK ' + user + ' token saved (captcha=' + code + ', attempt=' + attempt + ')');
      return;
    }
    console.error(user + ' login attempt ' + attempt + ' failed: ' + JSON.stringify(b).slice(0, 160));
  }
  console.error('FAILED after 5 attempts: ' + user);
  process.exit(1);
})().catch((e) => { console.error(e.message); process.exit(1); });
