const fs = require('fs');
const crypto = require('crypto');
const OUT = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const CAPTCHA = process.argv[2];
const BASE = process.argv[3] || 'http://127.0.0.1:8080/api';
if (!CAPTCHA) { console.error('usage: node p64p2-login2.cjs <captcha> [base]'); process.exit(1); }
const ch = JSON.parse(fs.readFileSync(OUT + '/proxy-challenge.json', 'utf8'));

(async () => {
  const publicKey = crypto.createPublicKey({ key: Buffer.from(ch.publicKey, 'base64'), format: 'der', type: 'spki' });
  const encrypted = crypto.publicEncrypt({ key: publicKey, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' }, Buffer.from('admin123', 'utf8')).toString('base64');
  const r = await fetch(BASE + '/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: encrypted, captcha: CAPTCHA, captchaId: ch.captchaId, timestamp: String(Date.now()) }),
  });
  const b = await r.json();
  console.log(CAPTCHA + ' -> code=' + b.code + ' ' + (b.msg || ''));
  if (b.code === 0) {
    fs.writeFileSync(OUT + '/p64p2-api-token.txt', b.data.accessToken);
    console.log('LOGIN OK (token saved)');
  }
})();
