const fs = require('fs');
const BASE = 'http://127.0.0.1:8080/api';
const OUT = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';

async function main() {
  const chRes = await fetch(BASE + '/auth/challenge');
  const ch = (await chRes.json()).data;
  fs.writeFileSync(OUT + '/p64p2-api-captcha.png',
    Buffer.from(ch.captchaImage.replace(/^data:image\/png;base64,/, ''), 'base64'));
  fs.writeFileSync(OUT + '/p64p2-api-challenge.json', JSON.stringify({
    captchaId: ch.captchaId, publicKey: ch.publicKey, keyVersion: ch.keyVersion,
  }));
  console.log('challenge saved captchaId=' + ch.captchaId);
}
main().catch((e) => { console.error(e); process.exit(1); });
