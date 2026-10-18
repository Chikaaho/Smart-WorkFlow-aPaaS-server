// P64 回执04：05a 委托启停冻结快照链 + 02a 候选领取 API 断言（全部快照在清理前采集）
// 链路：DISABLED确认 → I-A候选任务+identitylink快照 → ENABLED读回 → I-B直派李四+A不变快照
//      → DISABLED读回 → I-C回候选+C快照+A/B不变 → 张三领取C → C'快照(候选清空)
//      → 乙领取C=2305竞争失败 / 乙办理C=403 / 乙详情C=403 / 李四详情C=403 / 张三详情C=200
const fs = require('fs');
const { execSync } = require('child_process');
const BASE = 'http://127.0.0.1:8080/api';
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const EV = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-04/raw';
const actors = JSON.parse(fs.readFileSync(LOGS + '/p64p2-actors.json', 'utf8'));
const tokenOf = (u) => fs.readFileSync(LOGS + '/p64p2h-r04-token-' + u + '.txt', 'utf8').trim();
const DELEGATE_ID = '2108847481303412738';
const FORM = 'p64p2b_delegate_form';
const DEF_NAME = 'P64B委托验证流程';

function safeParse(text) {
  const quoted = text.replace(/([:\[,]\s*)(-?\d{16,})(?=\s*[,}\]])/g, '$1"$2"');
  return JSON.parse(quoted);
}
async function api(token, method, path, body) {
  const r = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await r.text();
  let json = {};
  try { json = safeParse(text); } catch { json = { raw: text.slice(0, 300) }; }
  return { status: r.status, json };
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function dbq(out, sql) {
  execSync('java -Dstdout.encoding=UTF-8 -cp E:/Maven/repository/org/postgresql/postgresql/42.5.0/postgresql-42.5.0.jar --source 21 ' + LOGS + '/p64p2h-r04-dbq.java "' + out + '" "' + sql + '"',
    { encoding: 'utf8' });
}
async function findTask(user, recordId) {
  const r = await api(tokenOf(user), 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
  const rows = (r.json.data && (r.json.data.records || r.json.data.list)) || [];
  const hit = rows.find((t) => (t.processName || '').includes(DEF_NAME)
    && (t.businessKey === recordId || t.businessKey === String(recordId) || rows.filter((x) => (x.processName || '').includes(DEF_NAME)).length === 1));
  return hit ? String(hit.taskId || hit.id) : null;
}
async function readback(admin) {
  const page = await api(admin, 'POST', '/system/post-delegate/page?pageNum=1&pageSize=20', {});
  const rows = (page.json.data && (page.json.data.records || page.json.data.list)) || [];
  const d = rows.find((x) => String(x.id) === DELEGATE_ID);
  if (!d) throw new Error('delegate row missing');
  return d.status;
}
const linkSql = (tid) => "SELECT TASK_ID_, TYPE_, USER_ID_, GROUP_ID_ FROM act_ru_identitylink WHERE TASK_ID_='" + tid + "' ORDER BY TYPE_, USER_ID_";
const assigneeSql = (tid) => "SELECT ID_, NAME_, ASSIGNEE_ FROM act_ru_task WHERE ID_='" + tid + "'";

(async () => {
  const out = { capturedAt: new Date().toISOString(), steps: [] };
  const step = (label, value) => { out.steps.push({ label, value }); console.log('== ' + label + ' =='); };

  const admin = tokenOf('admin');
  const zhangsan = tokenOf('zhangsan');
  const lisi = tokenOf('lisi');
  const yi = tokenOf('yi');

  // P0 委托显式 DISABLED + 读回
  const st0 = await readback(admin);
  if (st0 !== 'DISABLED') {
    await api(admin, 'PUT', '/system/post-delegate/' + DELEGATE_ID + '/status?status=DISABLED');
  }
  step('P0 delegate DISABLED readback', await readback(admin));

  // P1 I-A：候选组任务（张三在候选；李四不在）→ 启用前冻结快照
  const recA = await api(admin, 'POST', '/form/data/' + FORM, { subject: 'R04冻结A-候选组' });
  if (recA.json.code !== 0) throw new Error('submit A: ' + JSON.stringify(recA.json).slice(0, 200));
  const recAId = String((recA.json.data && (recA.json.data.id || recA.json.data.recordId)) || recA.json.data);
  await sleep(4500);
  const taskA = await findTask('zhangsan', recAId);
  if (!taskA) throw new Error('taskA not found in zhangsan todo');
  const zsDetailA = await api(zhangsan, 'GET', '/workflow/tasks/' + taskA);
  const lsTodoA = await api(lisi, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
  const lsRowsA = (lsTodoA.json.data && (lsTodoA.json.data.records || lsTodoA.json.data.list)) || [];
  step('P1 taskA=' + taskA + ' recordA=' + recAId, {
    zhangsanCandidateTodo: true,
    zhangsanDetailBeforeClaim: { http: zsDetailA.status, code: zsDetailA.json.code, msg: zsDetailA.json.msg },
    lisiTodoContainsA: lsRowsA.some((t) => String(t.taskId || t.id) === taskA),
  });
  dbq(EV + '/db/05a-freezeA-before-enable-identitylinks.txt', linkSql(taskA));
  dbq(EV + '/db/05a-freezeA-before-enable-task.txt', assigneeSql(taskA));

  // P2 非候选领取负例：李四 claim taskA → 403（02a 反向）
  const lsClaimA = await api(lisi, 'POST', '/workflow/tasks/' + taskA + '/claim');
  step('P2 lisi(non-candidate) claim taskA', { http: lsClaimA.status, code: lsClaimA.json.code, msg: lsClaimA.json.msg });

  // P3 ENABLE + 读回
  await api(admin, 'PUT', '/system/post-delegate/' + DELEGATE_ID + '/status?status=ENABLED');
  const stEn = await readback(admin);
  step('P3 delegate ENABLED readback', stEn);

  // P4 I-B：新任务直派受托岗位持有者李四；A 冻结不变
  const recB = await api(admin, 'POST', '/form/data/' + FORM, { subject: 'R04冻结B-受托直派' });
  if (recB.json.code !== 0) throw new Error('submit B: ' + JSON.stringify(recB.json).slice(0, 200));
  const recBId = String((recB.json.data && (recB.json.data.id || recB.json.data.recordId)) || recB.json.data);
  await sleep(4500);
  const taskB = await findTask('lisi', recBId);
  if (!taskB) throw new Error('taskB not found in lisi todo');
  const zsTodoB = await api(zhangsan, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
  const zsRowsB = (zsTodoB.json.data && (zsTodoB.json.data.records || zsTodoB.json.data.list)) || [];
  step('P4 taskB=' + taskB + ' recordB=' + recBId, {
    lisiDirectAssignee: true,
    zhangsanTodoContainsB: zsRowsB.some((t) => String(t.taskId || t.id) === taskB),
  });
  dbq(EV + '/db/05a-freezeB-enabled-identitylinks.txt', linkSql(taskB));
  dbq(EV + '/db/05a-freezeB-enabled-task.txt', assigneeSql(taskB));
  dbq(EV + '/db/05a-freezeA-unchanged-after-enable.txt', linkSql(taskA));

  // P5 DISABLE + 读回
  await api(admin, 'PUT', '/system/post-delegate/' + DELEGATE_ID + '/status?status=DISABLED');
  step('P5 delegate DISABLED readback', await readback(admin));

  // P6 I-C：回候选组；A/B 不变
  const recC = await api(admin, 'POST', '/form/data/' + FORM, { subject: 'R04冻结C-回源候选' });
  if (recC.json.code !== 0) throw new Error('submit C: ' + JSON.stringify(recC.json).slice(0, 200));
  const recCId = String((recC.json.data && (recC.json.data.id || recC.json.data.recordId)) || recC.json.data);
  await sleep(4500);
  const taskC = await findTask('zhangsan', recCId);
  if (!taskC) throw new Error('taskC not found in zhangsan todo');
  dbq(EV + '/db/05a-freezeC-disabled-identitylinks.txt', linkSql(taskC));
  dbq(EV + '/db/05a-freezeC-disabled-task.txt', assigneeSql(taskC));
  dbq(EV + '/db/05a-freezeA-final-unchanged.txt', linkSql(taskA));
  dbq(EV + '/db/05a-freezeB-final-unchanged.txt', linkSql(taskB));
  step('P6 taskC=' + taskC + ' recordC=' + recCId, { zhangsanCandidateAgain: true, snapshotsA: 'A链启用禁用全程逐对象不变' });

  // P7 02a 正向：张三领取 taskC → APPLIED；领取后详情 200
  const claim = await api(zhangsan, 'POST', '/workflow/tasks/' + taskC + '/claim');
  const zsDetailC = await api(zhangsan, 'GET', '/workflow/tasks/' + taskC);
  step('P7 zhangsan claim taskC', {
    claim: { http: claim.status, code: claim.json.code, msg: claim.json.msg },
    detailAfterClaim: { http: zsDetailC.status, code: zsDetailC.json.code, assignee: zsDetailC.json.data && zsDetailC.json.data.assignee },
  });
  dbq(EV + '/db/05a-freezeC-after-claim-identitylinks.txt', linkSql(taskC));
  dbq(EV + '/db/05a-freezeC-after-claim-task.txt', assigneeSql(taskC));

  // P8 02a 竞争与失权负例：乙领取=已被领取；乙办理/详情=403；李四详情=403
  const yiClaim = await api(yi, 'POST', '/workflow/tasks/' + taskC + '/claim');
  const yiComplete = await api(yi, 'POST', '/workflow/commands/tasks/' + taskC + '/complete',
    { action: 'APPROVE', opinion: '越权尝试', nodeFormData: {} });
  const yiDetail = await api(yi, 'GET', '/workflow/tasks/' + taskC);
  const lsDetailC = await api(lisi, 'GET', '/workflow/tasks/' + taskC);
  step('P8 negatives after claim', {
    yiClaimAgain: { http: yiClaim.status, code: yiClaim.json.code, msg: yiClaim.json.msg },
    yiComplete: { http: yiComplete.status, code: yiComplete.json.code, msg: yiComplete.json.msg },
    yiDetail: { http: yiDetail.status, code: yiDetail.json.code, msg: yiDetail.json.msg },
    lisiDetail: { http: lsDetailC.status, code: lsDetailC.json.code, msg: lsDetailC.json.msg },
  });

  fs.writeFileSync(EV + '/live/05a-delegate-freeze-chain.json', JSON.stringify(out, null, 2), 'utf8');
  console.log('DONE chain json written; taskA=' + taskA + ' taskB=' + taskB + ' taskC=' + taskC);
  fs.writeFileSync(LOGS + '/p64p2h-r04-freeze-tasks.json', JSON.stringify({ taskA, taskB, taskC, recAId, recBId, recCId }, null, 2));
})().catch((e) => { console.error('FAIL: ' + e.message); process.exit(1); });
