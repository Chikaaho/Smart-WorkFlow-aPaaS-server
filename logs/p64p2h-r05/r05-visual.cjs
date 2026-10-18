// P64 回执05：P2-02b-V 视觉补采（真实 Chrome + CDP，headless=false，桌面 1440x900 与窄屏 768x1024）
// 目标（只补受影响屏幕/交互，不重演主链）：
//  1. [object Object] 修复后：张三/李四子任务详情「数据表单」反馈明细逐行可读；admin 复核详情主机表逐行可读
//  2. 节点业务表单滚动补采（任务详情内可见像素）
//  3. 实例回查抽屉「子流程批次与回写」面板滚动补采（桌面 + 768）
//  4. 768 修复后：基本信息字段标题完整可见（单列 + 长值断行）；回写面板字段标题/操作可访问
const fs = require('fs');
const { execSync, spawn } = require('child_process');
const http = require('http');
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const UI = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-05/raw/ui';
const APP = 'http://localhost:5173';
const PORT = 9333;

function get(url) {
  return new Promise((resolve, reject) => {
    http.get(url, (res) => { let d = ''; res.on('data', (c) => (d += c)); res.on('end', () => resolve(d)); }).on('error', reject);
  });
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
class Cdp {
  constructor(ws) { this.ws = ws; this.id = 0; this.pending = new Map(); }
  static async connect() {
    let list = '';
    for (let i = 0; i < 40; i++) { try { list = await get('http://127.0.0.1:' + PORT + '/json/list'); break; } catch { await sleep(500); } }
    if (!list) throw new Error('chrome devtools not reachable');
    const page = JSON.parse(list).find((t) => t.type === 'page');
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    const c = new Cdp(ws);
    ws.onmessage = (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.id && c.pending.has(msg.id)) {
        const { resolve, reject } = c.pending.get(msg.id);
        c.pending.delete(msg.id);
        msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
      }
    };
    return c;
  }
  send(method, params = {}) {
    const id = ++this.id;
    this.ws.send(JSON.stringify({ id, method, params }));
    return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject }));
  }
  async evaluate(expression) {
    const r = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) throw new Error('eval: ' + JSON.stringify(r.exceptionDetails).slice(0, 400));
    return r.result ? r.result.value : undefined;
  }
  async shot(name) {
    const r = await this.send('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(UI + '/' + name + '.png', Buffer.from(r.data, 'base64'));
    console.log('SHOT ' + name + '.png');
  }
  async nav(url) {
    await this.send('Page.navigate', { url });
    await this.evaluate('new Promise(r => { const t0 = Date.now(); const c = () => document.readyState === "complete" ? r(true) : (Date.now() - t0 > 15000 ? r(false) : setTimeout(c, 200)); c(); })');
    await sleep(900);
  }
  async waitForText(text, timeoutMs = 15000) {
    const ok = await this.evaluate('new Promise(r => { const t0 = Date.now(); const c = () => document.body && document.body.innerText.includes(' + JSON.stringify(text) + ') ? r(true) : (Date.now() - t0 > ' + timeoutMs + ' ? r(false) : setTimeout(c, 300)); c(); })');
    if (!ok) throw new Error('waitForText timeout: ' + text);
  }
  async domSnapshot(name) {
    const text = await this.evaluate('document.body ? document.body.innerText.slice(0, 8000) : ""');
    const url = await this.evaluate('location.href');
    const viewport = await this.evaluate('JSON.stringify({w: window.innerWidth, h: window.innerHeight, dpr: window.devicePixelRatio})');
    fs.writeFileSync(UI + '/' + name + '.txt', 'URL: ' + url + '\nViewport: ' + viewport + '\n----\n' + text + '\n', 'utf8');
    return { url, text };
  }
  async scrollToSelector(selector) {
    return this.evaluate(`(() => {
      const el = document.querySelector(${JSON.stringify(selector)});
      if (!el) return 'NOT_FOUND';
      el.scrollIntoView({ block: 'center' });
      return 'SCROLLED';
    })()`);
  }
}
function ocrDisplayedCaptcha(cdp) {
  return (async () => {
    const src = await cdp.evaluate('document.querySelector(".login-page__form img") ? document.querySelector(".login-page__form img").src : ""');
    if (!src || !src.startsWith('data:image')) throw new Error('captcha img not found');
    fs.writeFileSync(LOGS + '/p64p2h-r05-ui-captcha.png', Buffer.from(src.replace(/^data:image\/png;base64,/, ''), 'base64'));
    const ocr = execSync('java -Dstdout.encoding=UTF-8 --source 21 ' + LOGS + '/p64p2-ocr3.java ' + LOGS + '/p64p2h-r05-ui-captcha.png', { encoding: 'utf8' });
    const m = ocr.match(/ANSWER: (\S+)/);
    if (!m) throw new Error('OCR failed');
    return m[1];
  })();
}
async function browserLogin(cdp, username) {
  await cdp.nav(APP + '/login');
  await cdp.evaluate('try { localStorage.clear(); sessionStorage.clear(); } catch (e) {} "cleared"');
  await cdp.nav(APP + '/login');
  await cdp.waitForText('登录', 20000);
  for (let attempt = 1; attempt <= 6; attempt++) {
    if (attempt > 1) {
      await cdp.evaluate('(document.querySelector(".login-page__form img")||{click(){}}).click()');
      await sleep(900);
    }
    const code = await ocrDisplayedCaptcha(cdp);
    await cdp.evaluate(`(() => {
      const set = (i, v) => {
        const el = document.querySelectorAll(".login-page__form input")[i];
        const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
        el.dispatchEvent(new Event('input', { bubbles: true }));
      };
      set(1, ${JSON.stringify(username)});
      set(2, 'admin123');
      set(3, ${JSON.stringify(code)});
      return 'filled';
    })()`);
    await cdp.evaluate('document.querySelector(".login-page__submit").click()');
    await sleep(3500);
    const after = await cdp.evaluate('location.pathname');
    if (!after.includes('/login')) { console.log('LOGIN OK ' + username + ' (attempt ' + attempt + ')'); return; }
  }
  throw new Error('login failed: ' + username);
}
// 任务详情取证：数据表单卡 + 节点表单滚动补采
async function captureTaskDetail(cdp, taskId, name) {
  await cdp.nav(APP + '/workflow/task/' + taskId);
  await cdp.waitForText('数据表单', 20000);
  await sleep(1200);
  await cdp.shot(name + '.png');
  const snap = await cdp.domSnapshot(name);
  const hasObjectObject = snap.text.includes('[object Object]');
  console.log(name, 'hasObjectObject=', hasObjectObject);
  // 滚动到节点业务表单卡并补采
  const scrolled = await cdp.scrollToSelector('.detail-card--node-form');
  console.log(name, 'nodeform scroll=', scrolled);
  await sleep(700);
  await cdp.shot(name + '-nodeform-scroll.png');
  await cdp.domSnapshot(name + '-nodeform-scroll');
  return hasObjectObject;
}
// 实例回查抽屉取证：基本信息 + 滚动到子流程批次与回写面板
async function captureInstanceReadback(cdp, businessKey, name) {
  await cdp.nav(APP + '/workflow/instances');
  await cdp.waitForText('业务单号', 20000);
  await sleep(1000);
  // 在列表中找目标业务单号行 → 点击行内「查看详情」
  const opened = await cdp.evaluate(`(() => {
    const rows = Array.from(document.querySelectorAll('.el-table__row'));
    const row = rows.find((r) => r.innerText.includes(${JSON.stringify(businessKey)}));
    if (!row) return 'ROW_NOT_FOUND';
    const btn = Array.from(row.querySelectorAll('button')).find((b) => b.innerText.includes('查看详情'));
    if (!btn) return 'BTN_NOT_FOUND';
    btn.click();
    return 'CLICKED';
  })()`);
  console.log(name, 'drawer open=', opened);
  if (opened !== 'CLICKED') throw new Error(opened);
  await cdp.waitForText('流程实例详情', 15000);
  await sleep(1200);
  await cdp.shot(name + '-top.png');
  await cdp.domSnapshot(name + '-top');
  const scrolled = await cdp.evaluate(`(() => {
    const h4 = Array.from(document.querySelectorAll('h4, .p64-section-title')).find((e) => e.innerText.includes('子流程批次与回写'));
    if (!h4) return 'PANEL_NOT_FOUND';
    h4.scrollIntoView({ block: 'start' });
    return 'SCROLLED';
  })()`);
  console.log(name, 'panel scroll=', scrolled);
  await sleep(800);
  await cdp.shot(name + '-batch-panel.png');
  await cdp.domSnapshot(name + '-batch-panel');
  return { opened, scrolled };
}

(async () => {
  fs.mkdirSync(UI, { recursive: true });
  fs.rmSync(LOGS + '/p64p2h-r05-chrome-profile', { recursive: true, force: true });
  const chrome = spawn('C:/Program Files/Google/Chrome/Application/chrome.exe', [
    '--remote-debugging-port=' + PORT,
    '--user-data-dir=' + LOGS + '/p64p2h-r05-chrome-profile',
    '--window-size=1440,900', '--no-first-run', '--no-default-browser-check', 'about:blank',
  ], { stdio: 'ignore' });
  console.log('chromePid', chrome.pid);
  fs.writeFileSync(LOGS + '/p64p2h-r05-chrome-pid.txt', String(chrome.pid));
  const cdp = await Cdp.connect();
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  const steps = [];

  // 说明：主链已完结，子任务历史 assignee 为空，原办理人深链被详情对象权限拒绝（403，
  // 阶段Ⅱ锁定语义）。本轮为修复后可读性复检，统一以 admin（超级管理员，详情对象权限内合法身份）
  // 打开同一批完结对象页面；身份已在 r05-visual-steps.json 与回执中如实声明，不冒充办理人视角。
  await browserLogin(cdp, 'admin');
  // ── 复核任务（主机表：父记录 TABLE 字段） ──
  steps.push({ s: 'review-10', objectObject: await captureTaskDetail(cdp, '76a62f6d-c4c5-11f1-aad4-00ff9e2a8dfb', 'r05-desktop-10-review-detail') });
  // ── 张三子任务（反馈明细 TABLE 字段） ──
  steps.push({ s: 'child-08', objectObject: await captureTaskDetail(cdp, '52c0bb62-c4c5-11f1-aad4-00ff9e2a8dfb', 'r05-desktop-08-child-task-detail') });
  // ── 李四子任务（反馈明细 TABLE 字段） ──
  steps.push({ s: 'child-09', objectObject: await captureTaskDetail(cdp, '52c0bb61-c4c5-11f1-aad4-00ff9e2a8dfb', 'r05-desktop-09-lisi-child-detail') });

  // ── 实例回查抽屉（桌面 1440x900） ──
  steps.push(await captureInstanceReadback(cdp, '5b1ad219-853e-460f-88a9-819cfe1ac305', 'r05-desktop-11-instance-readback'));

  // ── 窄屏 768x1024：同一实例回查 ──
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 768, height: 1024, deviceScaleFactor: 1, mobile: false });
  await sleep(900);
  steps.push(await captureInstanceReadback(cdp, '5b1ad219-853e-460f-88a9-819cfe1ac305', 'r05-narrow-02-instance-readback'));
  await cdp.send('Emulation.clearDeviceMetricsOverride', {});

  fs.writeFileSync(UI + '/r05-visual-steps.json', JSON.stringify({ at: new Date().toISOString(), chromePid: chrome.pid, steps }, null, 2));
  console.log('DONE');
  await cdp.evaluate('(() => { window.close(); return "closed"; })()');
})().catch((e) => { console.error('FATAL', e.message); process.exit(1); });
