const fs = require('fs');
const BASE = 'http://127.0.0.1:8080/api';
const OUT = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const token = fs.readFileSync(OUT + '/p64p2-api-token.txt', 'utf8').trim();

async function api(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const json = await res.json().catch(() => ({}));
  return { status: res.status, json };
}
function must(label, r) {
  if (r.status !== 200 || r.json.code !== 0) {
    throw new Error(label + ' FAILED status=' + r.status + ' body=' + JSON.stringify(r.json).slice(0, 300));
  }
  return r.json.data;
}

const PARENT_FORM = 'p64p2_parent_form';
const CHILD_FORM = 'p64p2_child_form';

async function ensureForm(formKey, name, definition) {
  const existing = await api('GET', '/form/def/page?pageNum=1&pageSize=50');
  const existingRows = (existing.json.data && (existing.json.data.records || existing.json.data.list)) || [];
  const found = existingRows.find((x) => x.formKey === formKey);
  const create = found ? { json: { code: 0, data: { id: found.id } } } : await api('POST', '/form/def', { formKey, name, logicalTableName: formKey, description: 'P64阶段Ⅱ验证' });
  let id;
  if (create.json.code === 0) {
    id = create.json.data.id;
  } else {
    const page = await api('GET', '/form/def/page?pageNum=1&pageSize=50');
    const rows = (page.json.data && (page.json.data.records || page.json.data.list)) || [];
    const row = rows.find((x) => x.formKey === formKey);
    if (!row) throw new Error('form not found after create failure: ' + formKey + ' ' + JSON.stringify(create.json).slice(0, 200));
    id = row.id;
  }
  const alreadyPublished = found && found.status === 'PUBLISHED';
  if (!alreadyPublished) {
    must('saveConfig ' + formKey, await api('POST', '/form/def/' + id + '/config', { definition: JSON.stringify(definition) }));
    must('publish ' + formKey, await api('POST', '/form/def/' + id + '/publish'));
  }
  console.log('form ready: ' + formKey + ' id=' + id + (alreadyPublished ? ' (already published)' : ''));
  return id;
}

function field(name, type, label, extra) {
  return Object.assign({ name, type, label, required: false }, extra || {});
}

async function main() {
  // 1) 父表单：title + hosts 表格（host_name/owner/feedback）
  await ensureForm(PARENT_FORM, 'P64主机反馈父表', {
    title: 'P64主机反馈父表',
    fields: [
      field('title', 'TEXT', '标题'),
      field('hosts', 'TABLE', '主机表', {
        subFields: [
          field('host_name', 'TEXT', '主机'),
          field('owner', 'TEXT', '负责人'),
          field('feedback', 'TEXT', '反馈结果'),
        ],
      }),
    ],
  });
  // 2) 子表单：summary + result_table（src_row_id/feedback）
  await ensureForm(CHILD_FORM, 'P64子流程反馈表', {
    title: 'P64子流程反馈表',
    fields: [
      field('summary', 'TEXT', '汇总说明'),
      field('result_table', 'TABLE', '反馈明细', {
        subFields: [
          field('src_row_id', 'TEXT', '来源行ID'),
          field('feedback', 'TEXT', '反馈结果'),
        ],
      }),
    ],
  });

  // 3) 子流程定义：START → node_result(APPROVAL, FIXED_USER admin) → END
  const childCreate = must('create child def', await api('POST', '/workflow/defs', { name: 'P64子反馈流程', formKey: CHILD_FORM }));
  const childDefId = childCreate.defId;
  const childGraph = {
    processKey: childCreate.processKey,
    name: 'P64子反馈流程',
    formKey: CHILD_FORM,
    elements: [
      { id: 'start', kind: 'node', type: 'START', config: { name: '开始' } },
      { id: 'node_result', kind: 'node', type: 'APPROVAL', config: { name: '子反馈填写', participant: { strategy: 'FIXED_USER', value: '1' }, nodeForm: { formKey: CHILD_FORM } } },
      { id: 'end', kind: 'node', type: 'END', config: { name: '结束' } },
      { id: 'e1', kind: 'edge', source: 'start', target: 'node_result' },
      { id: 'e2', kind: 'edge', source: 'node_result', target: 'end' },
    ],
    variables: [],
    triggers: [],
  };
  must('save child graph', await api('PUT', '/workflow/defs/' + childDefId + '/graph', childGraph));
  const childPublished = must('publish child def', await api('POST', '/workflow/defs/' + childDefId + '/publish'));
  const childDefKey = childPublished.processKey;
  console.log('child def ready: id=' + childDefId + ' key=' + childDefKey);

  // 4) 父流程定义：START → node_ops → wait_children(SUBFLOW_WAIT) → node_review → END + CHILD 触发动作
  const parentCreate = must('create parent def', await api('POST', '/workflow/defs', { name: 'P64主机反馈父流程', formKey: PARENT_FORM }));
  const parentDefId = parentCreate.defId;
  const parentGraph = {
    processKey: parentCreate.processKey,
    name: 'P64主机反馈父流程',
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
                mapping: [{ targetField: 'src_row_id', itemField: 'id' }],
                orchestration: 'CHILD', waitPolicy: 'ALL',
                writeBack: {
                  resultNodeKey: 'node_result', tableField: 'result_table', rowKeyField: 'src_row_id',
                  parentTableField: 'hosts',
                  fields: [{ fromField: 'feedback', toField: 'feedback' }],
                  mainFields: [{ fromField: 'summary', toField: 'title' }],
                },
              },
            ],
          },
        ],
        unmatchedDisposition: 'HALT', errorDisposition: 'HALT',
      },
    ],
  };
  must('save parent graph', await api('PUT', '/workflow/defs/' + parentDefId + '/graph', parentGraph));
  const parentPublished = must('publish parent def', await api('POST', '/workflow/defs/' + parentDefId + '/publish'));
  console.log('parent def ready: id=' + parentDefId + ' key=' + parentPublished.processKey);
  fs.writeFileSync(OUT + '/p64p2-scenario.json', JSON.stringify({
    parentForm: PARENT_FORM, childForm: CHILD_FORM,
    childDefId, childDefKey, parentDefId, parentDefKey: parentPublished.processKey,
  }, null, 2));
  console.log('SCENARIO READY');
}
main().catch((e) => { console.error(String(e)); process.exit(1); });
