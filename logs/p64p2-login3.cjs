const fs = require('fs');
const crypto = require('crypto');
const BASE = 'http://127.0.0.1:8080/api';
const OUT = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const USERNAME = process.argv[2];
const PASSWORD = process.argv[3] || 'admin123';
const CAPTCHA = process.argv[4];
if (!USERNAME || !CAPTCHA) { console.error('usage: node p64p2-login3.cjs <username> <password> <captcha>'); process.exit(1); }

(async () => {
  const reuse = process.argv.includes('--reuse');
  let ch;
  if (reuse) {
    ch = JSON.parse(fs.readFileSync(OUT + '/proxy-challenge.json', 'utf8'));
  } else {
    ch = (await (await fetch(BASE + '/auth/challenge')).json()).data;
    fs.writeFileSync(OUT + '/proxy-captcha.png', Buffer.from(ch.captchaImage.replace(/^data:image\/\w+;base64,/, ''), 'base64'));
    fs.writeFileSync(OUT + '/proxy-challenge.json', JSON.stringify({ captchaId: ch.captchaId, publicKey: ch.publicKey }, null, 2));
  }
  if (CAPTCHA === '-') { console.log('challenge fetched, captchaId=' + ch.captchaId); return; }
  const publicKey = crypto.createPublicKey({ key: Buffer.from(ch.publicKey, 'base64'), format: 'der', type: 'spki' });
  const encrypted = crypto.publicEncrypt({ key: publicKey, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' }, Buffer.from(PASSWORD, 'utf8')).toString('base64');
  const r = await fetch(BASE + '/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USERNAME, password: encrypted, captcha: CAPTCHA, captchaId: ch.captchaId, timestamp: String(Date.now()) }),
  });
  const b = await r.json();
  console.log(USERNAME + ' login -> code=' + b.code + ' ' + (b.msg || ''));
  if (b.code === 0) {
    fs.writeFileSync(OUT + '/p64p2-token-' + USERNAME + '.txt', b.data.accessToken);
    console.log('token saved for ' + USERNAME);
  }
})();
