const fs = require('fs');
const { execSync } = require('child_process');
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const EV = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-03/raw';
const log = fs.readFileSync(LOGS + '/p64p2h-backend.log', 'utf8');
// UI 会话窗口：19:38:00（UI challenge）起至当前；提取全部 ACCESS 行（服务端逐请求原件）
const lines = log.split('\n').filter((l) => l.includes('AccessLoggingFilter') && l.includes('ACCESS'));
const uiWindow = lines.filter((l) => {
  const m = l.match(/^2026-10-10T(\d{2}:\d{2}):\d{2}/);
  if (!m) return false;
  const hhmm = m[1];
  return hhmm >= '19:38';
});
const header = [
  '# P2-02/P2-01 请求索引（服务端 AccessLoggingFilter 逐请求原行，UI 会话窗口 ≥19:38）',
  '# 身份：admin(userId=1)/zhangsan(2108817586024214529)/lisi(2108817586460422146) 经可见会话登录；web-* clientRequestId=浏览器 SPA 发起',
  '# 关键原件：PUT /workflow/defs/2108817690357526529/graph（草稿保存）、POST …/publish（发布）、/auth/login（可见会话登录）、/workflow/tasks/*（办理/待办）',
  '# 共 ' + uiWindow.length + ' 行',
  '',
].join('\n');
fs.writeFileSync(EV + '/ui/02-request-index-ui-session.txt', header + uiWindow.join('\n') + '\n', 'utf8');

// 追加本轮截图/新标签能力错误归档
const archive = EV + '/ui/capability-error-archive.txt';
let prev = '';
try { prev = fs.readFileSync(archive, 'utf8'); } catch (e) { prev = ''; }
const add = [
  '',
  '## 2026-10-10 回执03 轮补充归档（同会话复测）',
  '- tab.screenshot() 冷启动重载登录页后：BrowserCommandError: browser screenshot activity capture failed for guest（新错误变体；此前轮为 surface preparation timed out after 3000ms）',
  '- browser.tabs.new()：BrowserCommandError: browser guest not attached (webview not ready)（IAB 新建 guest 失败；既有 tab 的 domSnapshot/locator/evaluate 全程可用）',
  '- 合成点击（dom_cua.click/cua.click）在该登录页不触发 Vue 表单提交（无 POST 到达服务端）；tab.playwright.evaluate 派发 btn.click() 可正常提交（19:38:43 POST /api/auth/login 200 为证）',
  '- 结论：登录后页面像素原件（正式视觉）本轮仍无法取得；替代证据=domSnapshot 结构化页面状态（8 件，桌面+768）+ 服务端逐请求 ACCESS 原行（见 02-request-index-ui-session.txt）+ API/DB 回读。已证失败路径未重复重试（提示01§0）。',
  '',
].join('\n');
fs.writeFileSync(archive, prev + add, 'utf8');
console.log('request index lines=' + uiWindow.length);
console.log('first=' + (uiWindow[0] || '').slice(0, 80));
console.log('last=' + (uiWindow[uiWindow.length - 1] || '').slice(0, 80));
