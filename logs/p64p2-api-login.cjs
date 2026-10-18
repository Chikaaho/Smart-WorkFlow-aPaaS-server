const fs = require('fs');
const crypto = require('crypto');
const BASE = 'http://127.0.0.1:8080/api';
const OUT = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const CAPTCHA = process.argv[2];
if (!CAPTCHA) { console.error('usage: node p64p2-api-login.cjs <captcha>'); process.exit(1); }

async function main() {
  const ch = JSON.parse(fs.readFileSync(OUT + '/p64p2-api-challenge.json', 'utf8'));
  const publicKey = crypto.createPublicKey({
    key: Buffer.from(ch.publicKey, 'base64'),
    format: 'der',
    type: 'spki',
  });
  const encrypted = crypto.publicEncrypt(
    { key: publicKey, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' },
    Buffer.from('admin123', 'utf8'),
  ).toString('base64');
  const res = await fetch(BASE + '/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: 'admin',
      password: encrypted,
      captcha: CAPTCHA,
      captchaId: ch.captchaId,
      timestamp: String(Date.now()),
    }),
  });
  const body = await res.json();
  if (body.code !== 0) { console.error('LOGIN FAILED: ' + JSON.stringify(body).slice(0, 200)); process.exit(2); }
  fs.writeFileSync(OUT + '/p64p2-api-token.txt', body.data.accessToken);
  console.log('LOGIN OK, token saved');
}
main().catch((e) => { console.error(e); process.exit(1); });
