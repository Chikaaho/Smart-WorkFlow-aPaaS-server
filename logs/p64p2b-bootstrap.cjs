const fs = require('fs');
const BASE = 'http://127.0.0.1:8080/api';
const OUT = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const token = fs.readFileSync(OUT + '/p64p2-api-token.txt', 'utf8').trim();
const actors = JSON.parse(fs.readFileSync(OUT + '/p64p2-actors.json', 'utf8'));

function safeParse(text) {
  const quoted = text.replace(/([:\[,]\s*)(-?\d{16,})(?=\s*[,\}\]])/g, '$1"$2"');
  return JSON.parse(quoted);
}
async function api(method, path, body) {
  const r = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await r.text();
  if (r.status >= 400 && text.startsWith('<')) {
    console.error('HTTP-ERROR ' + method + ' ' + path + ' status=' + r.status + ' bodyLen=' + (body ? JSON.stringify(body).length : 0) + ' respHead=' + text.slice(0, 120).replace(/\n/g, ' '));
  }
  let json = {};
  try { json = safeParse(text); } catch { json = { raw: text.slice(0, 300) }; }
  return { status: r.status, json };
}
function must(label, r) {
  if (r.json.code !== 0) throw new Error(label + ' FAILED status=' + r.status + ' body=' + JSON.stringify(r.json).slice(0, 300));
  return r.json.data;
}

const PARENT_FORM = 'p64p2b_parent_form';
const CHILD_FORM = 'p64p2b_child_form';

async function ensureForm(formKey, name, definition) {
  const existing = await api('GET', '/form/def/page?pageNum=1&pageSize=50');
  const existingRows = (existing.json.data && (existing.json.data.records || existing.json.data.list)) || [];
  const found = existingRows.find((x) => x.formKey === formKey);
  let id;
  if (found) id = String(found.id);
  else {
    const created = must('create form ' + formKey, await api('POST', '/form/def', { formKey, name, logicalTableName: formKey, description: 'P64阶段Ⅱ审查01补证' }));
    id = String(created && typeof created === 'object' ? (created.id ?? created.defId ?? JSON.stringify(created)) : created);
  }
  const needPublish = !found || found.status !== 'PUBLISHED';
  if (needPublish) {
    must('saveConfig ' + formKey, await api('POST', '/form/def/' + id + '/config', { definition: JSON.stringify(definition) }));
    must('publish ' + formKey, await api('POST', '/form/def/' + id + '/publish'));
  }
  console.log('form ready: ' + formKey + ' id=' + id + (needPublish ? ' (published now)' : ' (already published)'));
  return id;
}

function field(name, type, label, extra) {
  return Object.assign({ name, type, label, required: false }, extra || {});
}

async function main() {
  // 1) 父表单：title + hosts 表格（host_name/owner(USER单选)/feedback）
  const parentFormId = await ensureForm(PARENT_FORM, 'P64B主机反馈父表', {
    title: 'P64B主机反馈父表',
    fields: [
      field('title', 'TEXT', '标题'),
      field('hosts', 'TABLE', '主机表', {
        subFields: [
          field('host_name', 'TEXT', '主机'),
          field('owner', 'USER', '负责人'),
          field('feedback', 'TEXT', '反馈结果'),
        ],
      }),
    ],
  });
  // 2) 子表单：summary + result_table（src_row_id/feedback）+ owner_id（派发映射写入）
  const childFormId = await ensureForm(CHILD_FORM, 'P64B子流程反馈表', {
    title: 'P64B子流程反馈表',
    fields: [
      field('summary', 'TEXT', '汇总说明'),
      field('owner_id', 'TEXT', '负责人ID'),
      field('result_table', 'TABLE', '反馈明细', {
        subFields: [
          field('src_row_id', 'TEXT', '来源行ID'),
          field('feedback', 'TEXT', '反馈结果'),
        ],
      }),
    ],
  });

  // 3) 子流程定义：START → node_result(APPROVAL, FORM_FIELD owner_id) → END
  let childDefId = null, childDefKey = null;
  const defPage = await api('POST', '/workflow/defs/page?pageNum=1&pageSize=50', {});
  const defRows = (defPage.json.data && (defPage.json.data.records || defPage.json.data.list)) || [];
  const existingChild = defRows.find((d) => d.name === 'P64B子反馈流程');
  if (existingChild) {
    childDefId = String(existingChild.id);
    childDefKey = existingChild.processKey;
  } else {
    const c = must('create child def', await api('POST', '/workflow/defs', { name: 'P64B子反馈流程', formKey: CHILD_FORM }));
    childDefId = String(c.defId); childDefKey = c.processKey;
  }
  const childGraph = {
    processKey: childDefKey,
    name: 'P64B子反馈流程',
    formKey: CHILD_FORM,
    elements: [
      { id: 'start', kind: 'node', type: 'START', config: { name: '开始' } },
      { id: 'node_result', kind: 'node', type: 'APPROVAL', config: { name: '子反馈填写', participant: { strategy: 'FORM_FIELD', value: { objectType: 'USER', scope: 'MAIN', field: 'owner_id' } }, nodeForm: { formKey: CHILD_FORM } } },
      { id: 'end', kind: 'node', type: 'END', config: { name: '结束' } },
      { id: 'e1', kind: 'edge', source: 'start', target: 'node_result' },
      { id: 'e2', kind: 'edge', source: 'node_result', target: 'end' },
    ],
    variables: [],
    triggers: [],
  };
  must('save child graph', await api('PUT', '/workflow/defs/' + childDefId + '/graph', childGraph));
  const childPub = must('publish child def', await api('POST', '/workflow/defs/' + childDefId + '/publish'));
  childDefKey = childPub.processKey || childDefKey;
  console.log('child def ready: id=' + childDefId + ' key=' + childDefKey);

  // 4) 父流程定义 v1：START → node_ops → wait_children(SUBFLOW_WAIT) → node_review → END
  //    CHILD 动作 v1：ALL + 行级回写 fields=[feedback←feedback]，mainFields=[]（由 UI 追加）
  let parentDefId = null, parentDefKey = null;
  const existingParent = defRows.find((d) => d.name === 'P64B主机反馈父流程');
  if (existingParent) {
    parentDefId = String(existingParent.id);
    parentDefKey = existingParent.processKey;
  } else {
    const p = must('create parent def', await api('POST', '/workflow/defs', { name: 'P64B主机反馈父流程', formKey: PARENT_FORM }));
    parentDefId = String(p.defId); parentDefKey = p.processKey;
  }
  const parentGraph = {
    processKey: parentDefKey,
    name: 'P64B主机反馈父流程',
    formKey: PARENT_FORM,
    elements: [
      { id: 'start', kind: 'node', type: 'START', config: { name: '开始' } },
      { id: 'node_ops', kind: 'node', type: 'APPROVAL', config: { name: '运维填报', participant: { strategy: 'FIXED_USER', value: '1' }, nodeForm: { formKey: PARENT_FORM } } },
      { id: 'wait_children', kind: 'node', type: 'SUBFLOW_WAIT', config: { name: '等待子流程', waitActionIds: ['act_child'] } },
      { id: 'node_review', kind: 'node', type: 'APPROVAL', config: { name: '运维复核', participant: { strategy: 'FIXED_USER', value: '1' }, nodeForm: { formKey: PARENT_FORM } } },
      { id: 'end', kind: 'node', type: 'END', config: { name: '结束' } },
      { id: 'e1', kind: 'edge', source: 'start', target: 'node_ops' },
      { id: 'e2', kind: 'edge', source: 'node_ops', target: 'wait_children' },
      { id: 'e3', kind: 'edge', source: 'wait_children', target: 'node_review' },
      { id: 'e4', kind: 'edge', source: 'node_review', target: 'end' },
    ],
    variables: [
      { varId: 'var_hosts', name: '主机表', type: 'ROWS', source: 'MAIN_FORM', sourceField: 'hosts', roundRule: 'CURRENT', aggregation: 'CONCAT', nullable: false },
    ],
    triggers: [
      {
        triggerId: 'trg_child', name: '按负责人派发子流程', event: 'NODE_ROUND_COMPLETED', nodeKey: 'node_ops',
        script: 'return 1;', variables: ['var_hosts'],
        branches: [
          {
            branchId: 'b1', name: '命中', matchType: 'NUMBER', matchValue: '1',
            actions: [
              {
                actionId: 'act_child', name: '按负责人派发子反馈', type: 'START_GROUPED',
                sourceVariable: 'var_hosts', groupBy: 'owner',
                targetProcessDefKey: childDefKey, targetFormKey: CHILD_FORM, maxDispatch: 50,
                mapping: [
                  { targetField: 'owner_id', itemField: 'owner' },
                ],
                orchestration: 'CHILD', waitPolicy: 'ALL',
                writeBack: {
                  resultNodeKey: 'node_result', tableField: 'result_table', rowKeyField: 'src_row_id',
                  parentTableField: 'hosts',
                  fields: [{ fromField: 'feedback', toField: 'feedback' }],
                  mainFields: [],
                },
              },
            ],
          },
        ],
        unmatchedDisposition: 'HALT', errorDisposition: 'HALT',
      },
    ],
  };
  must('save parent graph v1', await api('PUT', '/workflow/defs/' + parentDefId + '/graph', parentGraph));
  const parentPub = must('publish parent def v1', await api('POST', '/workflow/defs/' + parentDefId + '/publish'));
  parentDefKey = parentPub.processKey || parentDefKey;
  console.log('parent def ready: id=' + parentDefId + ' key=' + parentDefKey + ' version=' + (parentPub.defVersion || parentPub.version));

  fs.writeFileSync(OUT + '/p64p2b-scenario.json', JSON.stringify({
    parentForm: PARENT_FORM, childForm: CHILD_FORM, parentFormId, childFormId,
    childDefId, childDefKey, parentDefId, parentDefKey,
  }, null, 2));
  console.log('SCENARIO B READY');
}
main().catch((e) => { console.error(String(e)); process.exit(1); });
