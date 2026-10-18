// P64 回执06：登录工具——ch.dev.test-mock 固定验证码 1234（Owner 指示：不识别验证码）
// 用法: node r06-login.cjs <username> [password]   （仅 local profile 会话有效）
const fs = require('fs');
const crypto = require('crypto');
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const BASE = 'http://127.0.0.1:8080/api';

(async () => {
  const user = process.argv[2];
  const password = process.argv[3] || 'admin123';
  if (!user) { console.error('usage: node r06-login.cjs <username>'); process.exit(1); }
  for (let attempt = 1; attempt <= 5; attempt++) {
    const ch = (await (await fetch(BASE + '/auth/challenge')).json()).data;
    const encrypted = (() => { const pk = crypto.createPublicKey({ key: Buffer.from(ch.publicKey, 'base64'), format: 'der', type: 'spki' }); return crypto.publicEncrypt({ key: pk, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' }, Buffer.from(password, 'utf8')).toString('base64'); })();
    const r = await fetch(BASE + '/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: user, password: encrypted, captcha: '1234', captchaId: ch.captchaId, timestamp: String(Date.now()) }) });
    const b = await r.json();
    if (b.code === 0) { fs.writeFileSync(LOGS + '/p64p2h-r06-token-' + user + '.txt', b.data.accessToken); console.log('OK ' + user + ' token saved (fixed captcha 1234, attempt=' + attempt + ')'); return; }
    console.error(user + ' attempt ' + attempt + ' failed: ' + JSON.stringify(b).slice(0, 160));
  }
  console.error('FAILED after 5 attempts: ' + user);
  process.exit(1);
})().catch((e) => { console.error(e.message); process.exit(1); });
