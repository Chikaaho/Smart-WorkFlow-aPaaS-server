const fs = require('fs');
const crypto = require('crypto');
const BASE = process.argv[2] || 'http://127.0.0.1:5173/api';
const OUT = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const CAPTCHA = process.argv[3];

async function main() {
  const chRes = await fetch(BASE + '/auth/challenge');
  const ch = (await chRes.json()).data;
  fs.writeFileSync(OUT + '/proxy-captcha.png', Buffer.from(ch.captchaImage.replace(/^data:image\/\w+;base64,/, ''), 'base64'));
  fs.writeFileSync(OUT + '/proxy-challenge.json', JSON.stringify({ captchaId: ch.captchaId, publicKey: ch.publicKey, base: BASE }, null, 2));
  console.log('challenge via ' + BASE + ' captchaId=' + ch.captchaId + ' imageBytes=' + Buffer.from(ch.captchaImage.replace(/^data:image\/\w+;base64,/, ''), 'base64').length);
  if (!CAPTCHA) return;
  const publicKey = crypto.createPublicKey({ key: Buffer.from(ch.publicKey, 'base64'), format: 'der', type: 'spki' });
  const encrypted = crypto.publicEncrypt({ key: publicKey, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' }, Buffer.from('admin123', 'utf8')).toString('base64');
  const res = await fetch(BASE + '/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: encrypted, captcha: CAPTCHA, captchaId: ch.captchaId, timestamp: String(Date.now()) }),
  });
  const body = await res.json();
  console.log('login via ' + BASE + ' -> code=' + body.code + ' ' + (body.msg || ''));
  if (body.code === 0) fs.writeFileSync(OUT + '/p64p2-api-token.txt', body.data.accessToken);
}
main().catch((e) => { console.error(String(e)); process.exit(1); });
