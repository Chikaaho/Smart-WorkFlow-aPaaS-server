// P64 回执04：02b 可见可交互主链（真实 Chrome + CDP 取证，headless=false）
// A. 领取入口（委托定义新实例）：张三 UI 待办见候选任务「待领取」→ 领取(确认)→ 详情办理
// B. 父子多角色（父定义链）：admin 运维填报 → 张三/李四 各自子任务办理 → admin 复核 →
//    实例回查「子流程批次与回写」面板；桌面 1440x900 与窄屏 768x1024 像素
// 取证：每步 PNG 像素 + DOM 文本快照 + 步骤日志；后端 AccessLoggingFilter 行为网络索引
const fs = require('fs');
const { execSync, spawn } = require('child_process');
const http = require('http');
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const EV = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-04/raw';
const UI = EV + '/ui';
const APP = 'http://localhost:5173';
const PORT = 9333;
const actors = JSON.parse(fs.readFileSync(LOGS + '/p64p2-actors.json', 'utf8'));

function get(url) {
  return new Promise((resolve, reject) => {
    http.get(url, (res) => {
      let d = '';
      res.on('data', (c) => (d += c));
      res.on('end', () => resolve(d));
    }).on('error', reject);
  });
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

class Cdp {
  constructor(ws) { this.ws = ws; this.id = 0; this.pending = new Map(); }
  static async connect() {
    let list = '';
    for (let i = 0; i < 40; i++) {
      try { list = await get('http://127.0.0.1:' + PORT + '/json/list'); break; }
      catch { await sleep(500); }
    }
    if (!list) throw new Error('chrome devtools not reachable');
    const targets = JSON.parse(list);
    const page = targets.find((t) => t.type === 'page');
    if (!page) throw new Error('no page target');
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
    if (r.exceptionDetails) throw new Error('eval: ' + JSON.stringify(r.exceptionDetails).slice(0, 300));
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
    await sleep(800);
  }
  async waitForText(text, timeoutMs = 15000) {
    const ok = await this.evaluate('new Promise(r => { const t0 = Date.now(); const c = () => document.body && document.body.innerText.includes(' + JSON.stringify(text) + ') ? r(true) : (Date.now() - t0 > ' + timeoutMs + ' ? r(false) : setTimeout(c, 300)); c(); })');
    if (!ok) throw new Error('waitForText timeout: ' + text);
  }
  async domSnapshot(name) {
    const text = await this.evaluate('document.body ? document.body.innerText.slice(0, 6000) : ""');
    const url = await this.evaluate('location.href');
    fs.writeFileSync(UI + '/' + name + '.txt', 'URL: ' + url + '\n----\n' + text + '\n', 'utf8');
    return { url, text };
  }
}

function ocrDisplayedCaptcha(cdp) {
  return (async () => {
    const src = await cdp.evaluate('document.querySelector(".login-page__form img") ? document.querySelector(".login-page__form img").src : ""');
    if (!src || !src.startsWith('data:image')) throw new Error('captcha img not found');
    fs.writeFileSync(LOGS + '/p64p2h-r04-ui-captcha.png', Buffer.from(src.replace(/^data:image\/png;base64,/, ''), 'base64'));
    const ocr = execSync('java -Dstdout.encoding=UTF-8 --source 21 ' + LOGS + '/p64p2-ocr3.java ' +
      LOGS + '/p64p2h-r04-ui-captcha.png', { encoding: 'utf8' });
    const m = ocr.match(/ANSWER: (\S+)/);
    if (!m) throw new Error('OCR failed');
    return m[1];
  })();
}

async function browserLogin(cdp, username) {
  await cdp.nav(APP + '/login');
  await cdp.waitForText('登录', 20000);
  for (let attempt = 1; attempt <= 6; attempt++) {
    if (attempt > 1) {
      // 登录失败后挑战可能未自动刷新：点击验证码图触发 reloadChallenge，再读新图
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
    const url = await cdp.evaluate('location.href');
    if (!url.includes('/login')) {
      console.log('LOGGED-IN ' + username + ' (captcha=' + code + ', attempt=' + attempt + ') at ' + url);
      return;
    }
    console.log('login attempt ' + attempt + ' failed (captcha=' + code + '), retrying');
    await sleep(1200);
  }
  throw new Error('login failed after 4 attempts for ' + username);
}

function jsClickByText(text) {
  return `(() => {
    const btn = Array.from(document.querySelectorAll("button")).find(b => b.textContent.trim().includes(${JSON.stringify(text)}) && !b.disabled);
    if (!btn) return "NO-BUTTON";
    btn.click();
    return "CLICKED";
  })()`;
}

function jsFillTableInputs(prefix) {
  return `(() => {
    const set = (el, v) => {
      const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    };
    const scoped = Array.from(document.querySelectorAll(".dynamic-field__table input.el-input__inner, .dynamic-field__table textarea"))
      .filter(el => !el.disabled && el.offsetParent !== null);
    const inputs = scoped.length > 0 ? scoped : Array.from(document.querySelectorAll(".el-table__row input, .el-table__row textarea"))
      .filter(el => !el.disabled && el.offsetParent !== null);
    const filled = [];
    inputs.forEach((el, i) => { const v = ${JSON.stringify(prefix)} + '-' + (i + 1); set(el, v); filled.push(v); });
    return JSON.stringify({ count: filled.length, values: filled });
  })()`;
}

async function confirmMessageBox(cdp) {
  await sleep(600);
  return cdp.evaluate(`(() => {
    const btn = document.querySelector(".el-message-box__btns .el-button--primary");
    if (!btn) return "NO-DIALOG";
    btn.click();
    return "CONFIRMED";
  })()`);
}

async function route(cdp, path) {
  await cdp.evaluate(`document.querySelector("#app").__vue_app__.config.globalProperties.$router.push(${JSON.stringify(path)})`);
  await sleep(1500);
}

/** 点击待办列表中匹配文本的行（行点击→详情）。 */
function jsClickRowByText(text) {
  return `(() => {
    const rows = Array.from(document.querySelectorAll(".el-table__row"));
    const target = rows.find(r => r.innerText.includes(${JSON.stringify(text)}));
    if (!target) return "NO-ROW";
    target.querySelector("td").dispatchEvent(new MouseEvent("click", { bubbles: true }));
    return "ROW-CLICKED";
  })()`;
}

/** 待办列表中点击包含文本的最新行并回读其 businessKey（子任务定位）。 */
function jsClickNewestRowByText(text) {
  return `(() => {
    const rows = Array.from(document.querySelectorAll(".el-table__row"));
    const target = rows.find(r => r.innerText.includes(${JSON.stringify(text)}));
    if (!target) return "NO-ROW";
    const cells = Array.from(target.querySelectorAll("td")).map(c => c.innerText.trim());
    const bk = cells.find(c => c.length >= 20) || "";
    target.querySelector("td").dispatchEvent(new MouseEvent("click", { bubbles: true }));
    return bk;
  })()`;
}

/** 待办列表中点击指定行内的按钮（如「领取」）。 */
function jsClickRowButtonByText(rowText, btnText) {
  return `(() => {
    const rows = Array.from(document.querySelectorAll(".el-table__row"));
    const target = rows.find(r => r.innerText.includes(${JSON.stringify(rowText)}));
    if (!target) return "NO-ROW";
    const btn = Array.from(target.querySelectorAll("button")).find(b => b.textContent.trim().includes(${JSON.stringify(btnText)}) && !b.disabled);
    if (!btn) return "NO-BUTTON";
    btn.click();
    return "CLICKED";
  })()`;
}

async function main() {
  const chainLog = { startedAt: new Date().toISOString(), steps: [] };
  const step = (label, value) => { chainLog.steps.push({ label, value: value === undefined ? null : value }); console.log('== ' + label + ' ==', value === undefined ? '' : JSON.stringify(value).slice(0, 240)); };

  // ── API 前置：登录 + 创建 领取用委托实例 + 父子链父实例 ──
  for (const u of ['admin', 'zhangsan', 'lisi']) {
    execSync('node ' + LOGS + '/p64p2h-r04-login.cjs ' + u, { encoding: 'utf8' });
  }
  const tokenOf = (u) => fs.readFileSync(LOGS + '/p64p2h-r04-token-' + u + '.txt', 'utf8').trim();
  const BASE = 'http://127.0.0.1:8080/api';
  async function api(token, method, path, body) {
    const r = await fetch(BASE + path, {
      method, headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await r.text();
    let json = {}; try { json = JSON.parse(text.replace(/([:\[,]\s*)(-?\d{16,})(?=\s*[,}\]])/g, '$1"$2"')); } catch { json = { raw: text.slice(0, 200) }; }
    return { status: r.status, json };
  }
  const recIdOf = (resp) => String((resp.json.data && (resp.json.data.id || resp.json.data.recordId)) || resp.json.data);

  // A0 领取用委托实例（候选任务=张三等 5 人）
  const recClaim = await api(tokenOf('admin'), 'POST', '/form/data/p64p2b_delegate_form', { subject: 'R04可见领取-候选任务' });
  if (recClaim.json.code !== 0) throw new Error('submit claim rec: ' + JSON.stringify(recClaim.json).slice(0, 200));
  const claimRecId = recIdOf(recClaim);
  await sleep(4500);
  const zsTodo0 = await api(tokenOf('zhangsan'), 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
  const zrows0 = (zsTodo0.json.data && (zsTodo0.json.data.records || zsTodo0.json.data.list)) || [];
  const claimTaskRow = zrows0.find((t) => t.businessKey === claimRecId && (t.processName || '').includes('委托'));
  if (!claimTaskRow) throw new Error('claim candidate task not found; bk=' + claimRecId);
  step('A0 claimTask(candidate)', { taskId: String(claimTaskRow.taskId || claimTaskRow.id), candidate: claimTaskRow.candidate, recordId: claimRecId });

  // B0 父子链父实例（hosts 3 行：张三2/李四1）
  const rows = [
    { host_name: 'host-01', owner: actors.zhangsanId, feedback: '' },
    { host_name: 'host-02', owner: actors.zhangsanId, feedback: '' },
    { host_name: 'host-03', owner: actors.lisiId, feedback: '' },
  ];
  const scen = JSON.parse(fs.readFileSync(LOGS + '/p64p2b-scenario.json', 'utf8'));
  const rec = await api(tokenOf('admin'), 'POST', '/form/data/' + scen.parentForm, { title: 'R04可见主链', hosts: rows });
  if (rec.json.code !== 0) throw new Error('submit parent: ' + JSON.stringify(rec.json).slice(0, 200));
  const recordId = recIdOf(rec);
  await sleep(4500);
  step('B0 parent record', { recordId });

  // ── 浏览器：真实 Chrome ──
  fs.rmSync(LOGS + '/p64p2h-r04-chrome-profile', { recursive: true, force: true });
  const chrome = spawn('C:/Program Files/Google/Chrome/Application/chrome.exe', [
    '--remote-debugging-port=' + PORT,
    '--user-data-dir=' + LOGS + '/p64p2h-r04-chrome-profile',
    '--window-size=1440,900', '--no-first-run', '--no-default-browser-check', 'about:blank',
  ], { stdio: 'ignore' });
  const chromePid = chrome.pid;
  step('chromePid', chromePid);
  try {
    const cdp = await Cdp.connect();
    await cdp.send('Page.enable');
    await cdp.send('Runtime.enable');

    // ── A. 张三：候选领取 → 办理 ──
    await browserLogin(cdp, 'zhangsan');
    await route(cdp, '/workspace/todo');
    await cdp.waitForText('待领取', 15000);
    const s1 = await cdp.domSnapshot('r04-desktop-01-todo-candidate');
    step('A1 desktop-01', { url: s1.url, hasClaimTag: s1.text.includes('待领取') });
    await cdp.shot('r04-desktop-01-todo-candidate');

    const clickClaim = await cdp.evaluate(jsClickRowButtonByText(claimRecId, '领取'));
    step('A2 click 领取', clickClaim);
    await cdp.shot('r04-desktop-02-claim-dialog');
    const confirmed = await confirmMessageBox(cdp);
    step('A3 confirm claim', confirmed);
    await sleep(2500);
    await cdp.domSnapshot('r04-desktop-03-todo-claimed');
    await cdp.shot('r04-desktop-03-todo-claimed');

    await cdp.evaluate(jsClickRowByText(claimRecId));
    await sleep(2500);
    const d4 = await cdp.domSnapshot('r04-desktop-04-claim-task-detail');
    step('A4 desktop-04', { url: d4.url });
    await cdp.shot('r04-desktop-04-claim-task-detail');
    const approveA = await cdp.evaluate(jsClickByText('通过'));
    step('A5 approve claim-task', approveA);
    const cA = await confirmMessageBox(cdp);
    step('A6 confirm approve claim-task', cA);
    await sleep(3000);
    await cdp.shot('r04-desktop-05-claim-task-after-approve');

    // ── B. admin：运维填报 ──
    await browserLogin(cdp, 'admin');
    await route(cdp, '/workspace/todo');
    await cdp.waitForText(recordId, 15000);
    await cdp.evaluate(jsClickRowByText(recordId));
    await sleep(2500);
    const d6 = await cdp.domSnapshot('r04-desktop-06-ops-task-detail');
    step('B1 desktop-06', { url: d6.url });
    const fillOps = await cdp.evaluate(jsFillTableInputs('R04-OPS'));
    step('B2 fill ops inputs', fillOps);
    await cdp.shot('r04-desktop-06-ops-task-detail');
    const approveOps = await cdp.evaluate(jsClickByText('通过'));
    step('B3 approve ops', approveOps);
    const c6 = await confirmMessageBox(cdp);
    step('B4 confirm ops', c6);
    await sleep(3000);
    await cdp.shot('r04-desktop-07-ops-after-approve');

    // B5 子任务按子流程名定位（子任务 businessKey=子记录ID，由派发链生成；
    // 批次项 target_record_id 于子完成时回填，故运行期以流程名+最新行定位）
    await sleep(5000);
    const CHILD_DEF_NAME = 'P64B子反馈流程';
    step('B5 child locate by name', CHILD_DEF_NAME);

    // ── C. 张三：子任务办理 ──
    await sleep(5000);
    await browserLogin(cdp, 'zhangsan');
    await route(cdp, '/workspace/todo');
    await cdp.waitForText(CHILD_DEF_NAME, 15000);
    const zsChildRec = await cdp.evaluate(jsClickNewestRowByText(CHILD_DEF_NAME));
    step('C0 zhangsan child row bk', zsChildRec);
    await sleep(2500);
    const d8 = await cdp.domSnapshot('r04-desktop-08-child-task-detail');
    step('C1 desktop-08', { url: d8.url });
    const tableText = await cdp.evaluate('Array.from(document.querySelectorAll(".el-table")).map(t => t.innerText.slice(0, 500)).join("\\n===\\n")');
    fs.writeFileSync(UI + '/r04-desktop-08-nodeform-tables.txt', 'URL: ' + d8.url + '\n----\n' + tableText + '\n', 'utf8');
    const fillZs = await cdp.evaluate(jsFillTableInputs('R04-ZS'));
    step('C2 fill zhangsan child inputs', fillZs);
    await cdp.shot('r04-desktop-08-child-task-detail');
    const approveZs = await cdp.evaluate(jsClickByText('通过'));
    step('C3 approve zhangsan child', approveZs);
    const c8 = await confirmMessageBox(cdp);
    step('C4 confirm zhangsan child', c8);
    await sleep(3000);

    // ── D. 李四：子任务办理 ──
    await browserLogin(cdp, 'lisi');
    await route(cdp, '/workspace/todo');
    await cdp.waitForText(CHILD_DEF_NAME, 15000);
    const lsChildRec = await cdp.evaluate(jsClickNewestRowByText(CHILD_DEF_NAME));
    step('D0 lisi child row bk', lsChildRec);
    await sleep(2500);
    const d9 = await cdp.domSnapshot('r04-desktop-09-lisi-child-detail');
    step('D1 desktop-09', { url: d9.url });
    const tableTextLs = await cdp.evaluate('Array.from(document.querySelectorAll(".el-table")).map(t => t.innerText.slice(0, 500)).join("\\n===\\n")');
    fs.writeFileSync(UI + '/r04-desktop-09-lisi-nodeform-tables.txt', 'URL: ' + d9.url + '\n----\n' + tableTextLs + '\n', 'utf8');
    const fillLs = await cdp.evaluate(jsFillTableInputs('R04-LS'));
    step('D2 fill lisi child inputs', fillLs);
    await cdp.shot('r04-desktop-09-lisi-child-detail');
    const approveLs = await cdp.evaluate(jsClickByText('通过'));
    step('D3 approve lisi child', approveLs);
    const c9 = await confirmMessageBox(cdp);
    step('D4 confirm lisi child', c9);
    await sleep(4000);

    // ── D5 冲突受控恢复（有权用户按当前权威版本重放 → 批次重结算 → 父推进）──
    await sleep(4000);
    const opsDetail2 = await api(tokenOf('admin'), 'GET', '/workflow/tasks/' + String(d6.url.split('/task/')[1] || ''));
    const parentPi2 = opsDetail2.json.data && opsDetail2.json.data.processInstanceId;
    let conflictItem = null;
    let batchNow = null;
    for (let poll = 1; poll <= 8; poll++) {
      const batches2 = await api(tokenOf('admin'), 'GET', '/workflow/instances/' + parentPi2 + '/child-batches');
      const bList = batches2.json.data || [];
      conflictItem = bList.flatMap((b) => (b.items || [])).find((it) => it.status === 'CONFLICT');
      batchNow = bList.find((b) => b.status === 'BLOCKED' || b.status === 'SETTLED') || bList[0];
      if (conflictItem) break;
      await sleep(2000);
    }
    step('D5 conflict detected', { batchStatus: batchNow && batchNow.status, itemId: conflictItem && conflictItem.id });
    if (conflictItem) {
      const rec = await api(tokenOf('admin'), 'POST', '/workflow/child-items/' + conflictItem.id + '/retry-writeback');
      step('D6 authorized recovery', { itemId: conflictItem.id, http: rec.status, code: rec.json.code, result: rec.json.data });
    }
    await sleep(4000);

    // ── E. admin：复核 → 实例回查 ──
    await browserLogin(cdp, 'admin');
    await route(cdp, '/workspace/todo');
    await cdp.waitForText(recordId, 15000);
    await cdp.evaluate(jsClickRowByText(recordId));
    await sleep(2500);
    await cdp.domSnapshot('r04-desktop-10-review-detail');
    await cdp.shot('r04-desktop-10-review-detail');
    const approveRev = await cdp.evaluate(jsClickByText('通过'));
    step('E1 approve review', approveRev);
    const c10 = await confirmMessageBox(cdp);
    step('E2 confirm review', c10);
    await sleep(3500);

    await route(cdp, '/workflow/instances');
    await sleep(2000);
    await cdp.evaluate(jsClickRowByText(recordId));
    await sleep(2500);
    const s12 = await cdp.domSnapshot('r04-desktop-11-instance-readback');
    step('E3 desktop-11', { url: s12.url, hasP64Panel: s12.text.includes('子流程批次与回写') });
    await cdp.shot('r04-desktop-11-instance-readback');

    // ── F. 768 窄屏关键页 ──
    await cdp.send('Emulation.setDeviceMetricsOverride', { width: 768, height: 1024, deviceScaleFactor: 1, mobile: false });
    await sleep(1200);
    await route(cdp, '/workspace/todo');
    await sleep(1500);
    await cdp.domSnapshot('r04-narrow-01-todo');
    await cdp.shot('r04-narrow-01-todo');
    await route(cdp, '/workflow/instances');
    await sleep(2000);
    await cdp.evaluate(jsClickRowByText(recordId));
    await sleep(2500);
    await cdp.domSnapshot('r04-narrow-02-instance-readback');
    await cdp.shot('r04-narrow-02-instance-readback');
    await cdp.send('Emulation.clearDeviceMetricsOverride', {});
    chainLog.finishedAt = new Date().toISOString();
  } finally {
    try { execSync('taskkill /PID ' + chromePid + ' /T /F', { encoding: 'utf8' }); } catch (e) { step('chromeKill', String(e.message).slice(0, 120)); }
  }
  fs.writeFileSync(UI + '/r04-chain-log.json', JSON.stringify(chainLog, null, 2), 'utf8');
  fs.writeFileSync(LOGS + '/p64p2h-r04-chain-record.json', JSON.stringify({
    claimRecId, claimTask: String(claimTaskRow.taskId || claimTaskRow.id),
    recordId, startedAt: chainLog.startedAt, finishedAt: chainLog.finishedAt,
  }, null, 2));
  console.log('CHAIN DONE recordId=' + recordId + ' claimTask=' + claimTaskRow.taskId);
}

main().catch((e) => { console.error('FAIL: ' + e.message); process.exit(1); });
