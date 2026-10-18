// P64 回执05：P2-04a 跨负责人隔离目标出口全量取证（真实 HTTP，完整 body 落盘）
// 对照组（既有 ALL 角色，保留）：zhangsan/lisi 互读对方子记录=200 合法（角色配置即全局授权）
// 普通办理者组（本轮登记 biz_handler_self，data_scope=SELF，权限串同 biz_handler）：
//   jia/yi 读对方子记录/主链子记录/父单据 → 必须拒绝或不给对方数据；列表/导出/写出口同口径
// 管理员宽域：admin 按显式权限读取合法（对照）
const fs = require('fs');
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const OUT = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-05/raw/live';
const BASE = 'http://127.0.0.1:8080/api';

const ZS_RECORD = 'c899a2c8-2b9d-411c-8bd7-6927931a9559'; // 04a 探针原对象·张三子记录
const LS_RECORD = 'f135af8f-e8d5-4e2f-a8ae-bed935927808'; // 04a 探针原对象·李四子记录
const ZS_MAINCHAIN_RECORD = '5276b840-7cfc-459e-a5f9-996cfd776081'; // r04 主链张三子记录
const LS_MAINCHAIN_RECORD = '61835090-8e82-4a89-b204-edcc3dc7279b'; // r04 主链李四子记录
const PARENT_RECORD = '5b1ad219-853e-460f-88a9-819cfe1ac305'; // r04 主链父单据
const ZS_TASK = '52c0bb62-c4c5-11f1-aad4-00ff9e2a8dfb'; // r04 主链张三子任务（办理出口）

const probes = [];
function record(label, http, body, note) {
  probes.push({ label, http, body, note: note || '' });
  console.log(label, '->', http, (note || '').slice(0, 80));
}
async function api(token, method, path, bodyObj) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: bodyObj ? JSON.stringify(bodyObj) : undefined,
  });
  const text = await res.text();
  let body;
  try { body = JSON.parse(text); } catch { body = text.slice(0, 400); }
  return { http: res.status, body };
}
async function detail(token, user, recordId, expectLabel) {
  const r = await api(token, 'GET', '/form/data/p64p2b_child_form/' + recordId);
  const code = r.body && r.body.code;
  record(expectLabel, r.http, r.body, 'code=' + code + ' id=' + (r.body && r.body.data && r.body.data.id));
  return r;
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const tokens = {};
  for (const u of ['zhangsan', 'lisi', 'admin', 'jia', 'yi']) {
    tokens[u] = fs.readFileSync(LOGS + '/p64p2h-r05-token-' + u + '.txt', 'utf8').trim();
  }

  // ── 对照组（保留）：张三/李四 data_scope=ALL，互读合法 ──
  await detail(tokens.zhangsan, 'zhangsan', ZS_RECORD, '对照:张三读本人子记录');
  await detail(tokens.lisi, 'lisi', LS_RECORD, '对照:李四读本人子记录');
  await detail(tokens.zhangsan, 'zhangsan', LS_RECORD, '对照:张三交叉读李四子记录(ALL配置合法,完整body)');
  await detail(tokens.lisi, 'lisi', ZS_RECORD, '对照:李四交叉读张三子记录(ALL配置合法,完整body)');

  // ── 管理员宽域（显式权限） ──
  await detail(tokens.admin, 'admin', ZS_RECORD, '对照:管理员读张三子记录');
  await detail(tokens.admin, 'admin', LS_RECORD, '对照:管理员读李四子记录');

  // ── 普通办理者组（SELF）：对象子记录不可读 ──
  for (const u of ['jia', 'yi']) {
    await detail(tokens[u], u, ZS_RECORD, '隔离:' + u + '(SELF)读张三子记录→须拒绝或无数据');
    await detail(tokens[u], u, LS_RECORD, '隔离:' + u + '(SELF)读李四子记录→须拒绝或无数据');
    await detail(tokens[u], u, ZS_MAINCHAIN_RECORD, '隔离:' + u + '(SELF)读主链张三子记录→须拒绝或无数据');
    await detail(tokens[u], u, LS_MAINCHAIN_RECORD, '隔离:' + u + '(SELF)读主链李四子记录→须拒绝或无数据');
    await detail(tokens[u], u, PARENT_RECORD, '隔离:' + u + '(SELF)读父单据→须拒绝或无数据');
  }

  // ── 普通办理者组：列表/导出/写/办理出口 ──
  const jia = tokens.jia, yi = tokens.yi;
  const listR = await api(jia, 'POST', '/form/data/p64p2b_child_form/query',
    { pageNum: 1, pageSize: 50, filters: [] });
  record('隔离:jia(SELF)列表出口全量', listR.http, listR.body,
    'total=' + (listR.body && listR.body.data && listR.body.data.total));
  const listAdm = await api(tokens.admin, 'POST', '/form/data/p64p2b_child_form/query',
    { pageNum: 1, pageSize: 50, filters: [] });
  record('对照:admin列表出口全量', listAdm.http, listAdm.body,
    'total=' + (listAdm.body && listAdm.body.data && listAdm.body.data.total));
  const expR = await api(jia, 'POST', '/form/data/p64p2b_child_form/export',
    { pageNum: 1, pageSize: 100, filters: [] });
  record('隔离:jia(SELF)导出出口', expR.http,
    typeof expR.body === 'string' ? expR.body.slice(0, 200) : expR.body, '导出内容头部');
  // 写出口：版本号故意不匹配（999）——若返回版本冲突=越权可写（缺陷）；若拒绝/无记录=隔离成立；
  // 任何情况下不产生真实变更
  const wrR = await api(jia, 'PUT', '/form/data/p64p2b_child_form/' + LS_RECORD,
    { version: 999, data: { summary: 'P2-04a negative write probe' } });
  record('隔离:jia(SELF)越权写李四子记录(版本999)', wrR.http, wrR.body,
    'code=' + (wrR.body && wrR.body.code) + ' msg=' + (wrR.body && wrR.body.msg));
  // 办理出口：BPM 任务详情按 assignee（阶段Ⅱ锁定），jia 读张三子任务
  const taskR = await api(jia, 'GET', '/workflow/tasks/' + ZS_TASK);
  record('隔离:jia(SELF)读张三子任务详情(办理出口)', taskR.http, taskR.body,
    'code=' + (taskR.body && taskR.body.code) + ' msg=' + (taskR.body && taskR.body.msg));

  fs.writeFileSync(OUT + '/05a-cross-owner-full-probes.json',
    JSON.stringify({ capturedAt: new Date().toISOString(), endpointBase: BASE,
      arrangement: { role: 'biz_handler_self', dataScope: 'SELF(ordinal 3)',
        menus: ['form:data:query', 'form:data:submit'], users: ['jia', 'yi'] },
      probes }, null, 2));
  console.log('SAVED ' + OUT + '/05a-cross-owner-full-probes.json');
})().catch((e) => { console.error('FATAL', e.message); process.exit(1); });
// NOTE: 正向自建记录对（普通办理者各自读合法 + 对方不可读）由 r05-cross-owner-extra.cjs 追加，
// 不修改本原件（保持 05a 探针原始时点）。
