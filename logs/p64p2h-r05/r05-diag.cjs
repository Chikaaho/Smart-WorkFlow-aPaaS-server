const fs = require('fs');
const { execSync, spawn } = require('child_process');
const http = require('http');
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const APP = 'http://localhost:5173';
const PORT = 9333;
function get(url) { return new Promise((resolve, reject) => { http.get(url, (res) => { let d = ''; res.on('data', (c) => (d += c)); res.on('end', () => resolve(d)); }).on('error', reject); }); }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
class Cdp {
  constructor(ws) { this.ws = ws; this.id = 0; this.pending = new Map(); }
  static async connect() {
    let list = '';
    for (let i = 0; i < 40; i++) { try { list = await get('http://127.0.0.1:' + PORT + '/json/list'); break; } catch { await sleep(500); } }
    const page = JSON.parse(list).find((t) => t.type === 'page');
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    const c = new Cdp(ws);
    ws.onmessage = (ev) => { const msg = JSON.parse(ev.data); if (msg.id && c.pending.has(msg.id)) { const { resolve, reject } = c.pending.get(msg.id); c.pending.delete(msg.id); msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result); } };
    return c;
  }
  send(method, params = {}) { const id = ++this.id; this.ws.send(JSON.stringify({ id, method, params })); return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject })); }
  async evaluate(expression) { const r = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true }); if (r.exceptionDetails) throw new Error('eval: ' + JSON.stringify(r.exceptionDetails).slice(0, 300)); return r.result ? r.result.value : undefined; }
  async nav(url) { await this.send('Page.navigate', { url }); await this.evaluate('new Promise(r => { const t0 = Date.now(); const c = () => document.readyState === "complete" ? r(true) : (Date.now() - t0 > 15000 ? r(false) : setTimeout(c, 200)); c(); })'); await sleep(1000); }
}
(async () => {
  const cdp = await Cdp.connect();
  await cdp.send('Page.enable'); await cdp.send('Runtime.enable');
  await cdp.nav(APP + '/workflow/task/76a62f6d-c4c5-11f1-aad4-00ff9e2a8dfb');
  await sleep(2500);
  const text = await cdp.evaluate('document.body ? document.body.innerText.slice(0, 2500) : "NO BODY"');
  console.log('URL:', await cdp.evaluate('location.href'));
  console.log('----BODY----'); console.log(text);
})().catch((e) => { console.error('FATAL', e.message); process.exit(1); });
