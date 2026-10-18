const fs = require('fs');
const BASE = 'http://127.0.0.1:8080/api';
const OUT = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const actors = JSON.parse(fs.readFileSync(OUT + '/p64p2-actors.json', 'utf8'));
const tokenOf = (u) => fs.readFileSync(OUT + '/p64p2-token-' + u + '.txt', 'utf8').trim();
const admin = tokenOf('admin');
const zhangsan = tokenOf('zhangsan');
const lisi = tokenOf('lisi');

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

async function ensureForm() {
  const page = await api(admin, 'GET', '/form/def/page?pageNum=1&pageSize=50');
  const rows = (page.json.data && (page.json.data.records || page.json.data.list)) || [];
  const found = rows.find((x) => x.formKey === FORM);
  if (found && found.status === 'PUBLISHED') return String(found.id);
  let id;
  if (found) id = String(found.id);
  else {
    const c = must('create form', await api(admin, 'POST', '/form/def', { formKey: FORM, name: 'P64B委托验证表单', logicalTableName: FORM, description: 'P64阶段Ⅱ委托验证' }));
    id = String(c && typeof c === 'object' ? (c.id ?? c.defId) : c);
  }
  must('saveConfig', await api(admin, 'POST', '/form/def/' + id + '/config', {
    definition: JSON.stringify({ title: 'P64B委托验证表单', fields: [{ name: 'subject', type: 'TEXT', label: '事项', required: false }] }),
  }));
  must('publish form', await api(admin, 'POST', '/form/def/' + id + '/publish'));
  return id;
}

async function ensureDef() {
  const page = await api(admin, 'POST', '/workflow/defs/page?pageNum=1&pageSize=50', {});
  const rows = (page.json.data && (page.json.data.records || page.json.data.list)) || [];
  let defId; let key;
  const found = rows.find((d) => d.name === 'P64B委托验证流程');
  if (found) { defId = String(found.id); key = found.processKey; }
  else {
    const c = must('create def', await api(admin, 'POST', '/workflow/defs', { name: 'P64B委托验证流程', formKey: FORM }));
    defId = String(c.defId); key = c.processKey;
  }
  const graph = {
    processKey: key, name: 'P64B委托验证流程', formKey: FORM,
    elements: [
      { id: 'start', kind: 'node', type: 'START', config: { name: '开始' } },
      { id: 'node_ops', kind: 'node', type: 'APPROVAL', config: { name: '运维岗办理', participant: { strategy: 'POST', value: { postCodes: ['ops_post'] } }, nodeForm: { formKey: FORM } } },
      { id: 'end', kind: 'node', type: 'END', config: { name: '结束' } },
      { id: 'e1', kind: 'edge', source: 'start', target: 'node_ops' },
      { id: 'e2', kind: 'edge', source: 'node_ops', target: 'end' },
    ],
    variables: [], triggers: [],
  };
  must('save graph', await api(admin, 'PUT', '/workflow/defs/' + defId + '/graph', graph));
  const pub = must('publish def', await api(admin, 'POST', '/workflow/defs/' + defId + '/publish'));
  return { defId, key: pub.processKey || key };
}

async function submitAndAssignee(defKey, label) {
  const rec = await api(admin, 'POST', '/form/data/' + FORM, { subject: label });
  if (rec.json.code !== 0) throw new Error('submit: ' + JSON.stringify(rec.json).slice(0, 200));
  await sleep(3000);
  const zs = await api(zhangsan, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=30');
  const ls = await api(lisi, 'GET', '/workflow/tasks/todo?pageNum=1&pageSize=30');
  const zsRows = (zs.json.data && (zs.json.data.records || zs.json.data.list)) || [];
  const lsRows = (ls.json.data && (ls.json.data.records || ls.json.data.list)) || [];
  const zsHit = zsRows.find((t) => (t.processName || '').includes('P64B委托验证') || (t.name || '').includes('运维岗办理'));
  const lsHit = lsRows.find((t) => (t.processName || '').includes('P64B委托验证') || (t.name || '').includes('运维岗办理'));
  return { zhangsanHasTask: !!zsHit, lisiHasTask: !!lsHit, zsTask: zsHit && String(zsHit.taskId || zsHit.id), lsTask: lsHit && String(lsHit.taskId || lsHit.id) };
}

async function main() {
  const inst0 = await api(admin, 'GET', '/workflow/instances?pageNum=1&pageSize=50');
  const list0 = (inst0.json.data && (inst0.json.data.records || inst0.json.data.list)) || [];
  for (const row of list0.filter((x) => x.status === 'RUNNING')) {
    await api(admin, 'POST', '/workflow/instances/' + String(row.processInstanceId) + '/discard', {});
  }
  await sleep(2000);
  await ensureForm();
  const def = await ensureDef();
  console.log('def=' + JSON.stringify(def));

  // 1) 未配置委托：源岗位（运维岗）持有者张三获得待办
  const before = await submitAndAssignee(def.key, '未配置委托');
  console.log('BEFORE delegate: ' + JSON.stringify(before));

  // 2) 配置委托：运维岗 → 主管岗（ORG 范围，启用）
  const page = await api(admin, 'POST', '/system/post-delegate/page?pageNum=1&pageSize=20', {});
  const rows = (page.json.data && (page.json.data.records || page.json.data.list)) || [];
  let delegateId = rows.find((d) => String(d.sourcePostId) === actors.opsPostId && String(d.targetPostId) === actors.leadPostId) ? String(rows.find((d) => String(d.sourcePostId) === actors.opsPostId).id) : null;
  if (!delegateId) {
    delegateId = String(must('create delegate', await api(admin, 'POST', '/system/post-delegate', {
      sourcePostId: actors.opsPostId, targetPostId: actors.leadPostId, scopeType: 'ORG', status: 'ENABLED', remark: 'P64阶段Ⅱ验证：运维岗→主管岗',
    })));
  }
  console.log('delegateId=' + delegateId);
  const back = await api(admin, 'POST', '/system/post-delegate/page?pageNum=1&pageSize=20', {});
  console.log('delegate readback=' + JSON.stringify((back.json.data && (back.json.data.records || back.json.data.list) || []).map((d) => ({ id: String(d.id), src: String(d.sourcePostId), tgt: String(d.targetPostId), scope: d.scopeType, status: d.status }))));

  // 3) 委托生效：新实例待办转由受托岗位（主管岗→李四）承接
  const after = await submitAndAssignee(def.key, '委托生效');
  console.log('AFTER delegate: ' + JSON.stringify(after));

  // 4) 停用委托：合法新轮次/新实例重新解析回源岗位（张三）
  const off = await api(admin, 'PUT', '/system/post-delegate/' + delegateId + '/status?status=DISABLED');
  console.log('disable -> code=' + off.json.code + ' ' + (off.json.msg || ''));
  const afterOff = await submitAndAssignee(def.key, '委托停用');
  console.log('AFTER disable: ' + JSON.stringify(afterOff));

  fs.writeFileSync(OUT + '/p64p2b-delegate.json', JSON.stringify({ def, before, after, afterOff, delegateId }, null, 2));
  console.log('DELEGATE CHECK DONE');
}
main().catch((e) => { console.error(String(e)); process.exit(1); });
