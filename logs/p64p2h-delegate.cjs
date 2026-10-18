// P64 阶段Ⅱ回执03：岗位委托净版实机链（修正回执02 before 相位污染）
// 时序：委托显式 DISABLED+读回 → I-1(源岗位张三) → ENABLED+读回 → I-2(受托岗位李四)+I-1 冻结读回
//      → DISABLED+读回 → I-3(回源张三)+I-2 冻结读回 → 旧任务(77954c26/79761a24)不变读回
const fs = require('fs');
const BASE = 'http://127.0.0.1:8080/api';
const OUT = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const EV = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-03/raw/live';
const actors = JSON.parse(fs.readFileSync(OUT + '/p64p2-actors.json', 'utf8'));
const tokenOf = (u) => fs.readFileSync(OUT + '/p64p2-token-' + u + '.txt', 'utf8').trim();

function safeParse(text) {
  const quoted = text.replace(/([:\[,]\s*)(-?\d{16,})(?=\s*[,\}\]])/g, '$1"$2"');
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
function must(label, r) {
  if (r.json.code !== 0) throw new Error(label + ' -> ' + JSON.stringify(r.json).slice(0, 250));
  return r.json.data;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const FORM = 'p64p2b_delegate_form';
const DEF_NAME = 'P64B委托验证流程';

async function delegateState(admin) {
  const page = await api(admin, 'POST', '/system/post-delegate/page?pageNum=1&pageSize=20', {});
  const rows = (page.json.data && (page.json.data.records || page.json.data.list)) || [];
  return rows.map((d) => ({ id: String(d.id), src: String(d.sourcePostId), tgt: String(d.targetPostId), scope: d.scopeType, status: d.status }));
}

async function findTask(user, label) {
  const r = await api(tokenOf(user), 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=50');
  const rows = (r.json.data && (r.json.data.records || r.json.data.list)) || [];
  const hit = rows.find((t) => (t.processName || '').includes(DEF_NAME) || (t.name || '').includes('运维岗办理'));
  return hit ? { taskId: String(hit.taskId || hit.id), nodeId: hit.nodeId || hit.nodeKey || null, name: hit.name || null, raw: hit } : null;
}

async function submitAndSnapshot(admin, label) {
  const rec = await api(admin, 'POST', '/form/data/' + FORM, { subject: label });
  if (rec.json.code !== 0) throw new Error('submit ' + label + ': ' + JSON.stringify(rec.json).slice(0, 250));
  const recordId = rec.json.data && (rec.json.data.id || rec.json.data.recordId) ? String(rec.json.data.id || rec.json.data.recordId) : null;
  await sleep(4000);
  const zs = await findTask('zhangsan', label);
  const ls = await findTask('lisi', label);
  const detail = {};
  if (zs) detail.zhangsanTaskDetail = (await api(tokenOf('zhangsan'), 'GET', '/workflow/tasks/' + zs.taskId)).json;
  if (ls) detail.lisiTaskDetail = (await api(tokenOf('lisi'), 'GET', '/workflow/tasks/' + ls.taskId)).json;
  return { label, recordId, zhangsan: zs ? { taskId: zs.taskId } : null, lisi: ls ? { taskId: ls.taskId } : null, detail };
}

async function main() {
  const admin = tokenOf('admin');
  const steps = [];
  // 0) 前置：委托显式停用并读回（修复回执02 before 相位污染的根因）
  const dg0 = await delegateState(admin);
  for (const d of dg0) {
    if (d.status !== 'DISABLED') must('disable ' + d.id, await api(admin, 'PUT', '/system/post-delegate/' + d.id + '/status?status=DISABLED'));
  }
  const dg0b = await delegateState(admin);
  steps.push({ step: '0-delegate-disabled', before: dg0, after: dg0b });
  console.log('step0 delegate=' + JSON.stringify(dg0b));

  // 1) 未启用委托：新实例任务由源岗位（运维岗）持有者承接
  const s1 = await submitAndSnapshot(admin, '回执03-未启用委托');
  steps.push({ step: '1-no-delegate', ...s1 });
  console.log('step1 zs=' + JSON.stringify(s1.zhangsan) + ' ls=' + JSON.stringify(s1.lisi));

  // 2) 启用委托（读回 ENABLED）→ 新实例任务转受托岗位（主管岗→李四）
  const dg1 = await delegateState(admin);
  const target = dg1.find((d) => String(d.src) === String(actors.opsPostId));
  if (!target) throw new Error('delegate row not found: ' + JSON.stringify(dg1));
  must('enable delegate', await api(admin, 'PUT', '/system/post-delegate/' + target.id + '/status?status=ENABLED'));
  const dg1b = await delegateState(admin);
  steps.push({ step: '2-delegate-enabled', delegateId: target.id, before: dg1, after: dg1b });
  console.log('step2 delegate=' + JSON.stringify(dg1b));

  const s2 = await submitAndSnapshot(admin, '回执03-委托生效');
  steps.push({ step: '2-with-delegate', ...s2 });
  console.log('step2 zs=' + JSON.stringify(s2.zhangsan) + ' ls=' + JSON.stringify(s2.lisi));

  // 3) 冻结读回：I-1 的任务不随委托启用而迁移
  const s1recheck = zs1StillThere(s1);
  async function zs1StillThere(prev) {
    if (!prev.zhangsan) return { stillOnZhangsan: false };
    const d = await api(tokenOf('zhangsan'), 'GET', '/workflow/tasks/' + prev.zhangsan.taskId);
    const zsNow = await findTask('zhangsan', '');
    const lsNow = await findTask('lisi', '');
    return { taskDetailCode: d.json.code, stillOnZhangsan: d.json.code === 0, zhangsanTodoStillHas: !!(zsNow && zsNow.taskId === prev.zhangsan.taskId), lisiTodoHasIt: !!(lsNow && lsNow.taskId === prev.zhangsan.taskId) };
  }
  steps.push({ step: '3-frozen-after-enable', task1: s1.zhangsan && s1.zhangsan.taskId, ...s1recheck });
  console.log('step3 frozen1=' + JSON.stringify(steps[steps.length - 1]));

  // 4) 停用委托（读回 DISABLED）→ 新实例任务回源岗位（张三）
  must('disable delegate', await api(admin, 'PUT', '/system/post-delegate/' + target.id + '/status?status=DISABLED'));
  const dg2 = await delegateState(admin);
  steps.push({ step: '4-delegate-disabled-again', after: dg2 });
  const s3 = await submitAndSnapshot(admin, '回执03-委托停用回源');
  steps.push({ step: '4-after-off', ...s3 });
  console.log('step4 zs=' + JSON.stringify(s3.zhangsan) + ' ls=' + JSON.stringify(s3.lisi));

  // 5) 冻结读回：I-2 的任务保持李四；旧链任务（回执02 遗留 RUNNING 实例）不变
  async function taskStill(taskId, user) {
    const d = await api(tokenOf(user), 'GET', '/workflow/tasks/' + taskId);
    return { code: d.json.code, assignee: d.json.data && d.json.data.assignee != null ? String(d.json.data.assignee) : null };
  }
  const frozen = {};
  if (s2.lisi) frozen.task2 = await taskStill(s2.lisi.taskId, 'lisi');
  frozen.old77954c26 = await taskStill('77954c26-c48a-11f1-bd5c-00ff9e2a8dfb', 'lisi');
  frozen.old79761a24 = await taskStill('79761a24-c48a-11f1-bd5c-00ff9e2a8dfb', 'zhangsan');
  steps.push({ step: '5-frozen-final', ...frozen });
  console.log('step5 frozen=' + JSON.stringify(frozen));

  fs.writeFileSync(EV + '/05-delegate-chain.json', JSON.stringify({ actors, steps }, null, 2));
  console.log('DELEGATE CHAIN DONE');
}
main().catch((e) => { console.error(String(e)); process.exit(1); });
