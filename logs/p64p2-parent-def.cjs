const fs = require('fs');
const BASE = 'http://127.0.0.1:8080/api';
const OUT = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const token = fs.readFileSync(OUT + '/p64p2-api-token.txt', 'utf8').trim();
const scen = JSON.parse(fs.readFileSync(OUT + '/p64p2-scenario.json', 'utf8'));

async function api(method, path, body) {
  const r = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { status: r.status, json: await r.json().catch(() => ({})) };
}

async function main() {
  const page = await api('GET', '/workflow/defs?pageNum=1&pageSize=50');
  const rows = (page.json.data && page.json.data.records) || [];
  let parent = rows.find((x) => x.name === 'P64主机反馈父流程');
  if (!parent) {
    const created = await api('POST', '/workflow/defs', { name: 'P64主机反馈父流程', formKey: scen.parentForm });
    if (created.json.code !== 0) throw new Error('create parent def: ' + JSON.stringify(created.json).slice(0, 200));
    parent = { id: created.json.data.defId, processKey: created.json.data.graph.processKey };
  }
  const graph = {
    processKey: parent.processKey,
    name: 'P64主机反馈父流程',
    formKey: scen.parentForm,
    elements: [
      { id: 'start', kind: 'node', type: 'START', config: { name: '开始' } },
      { id: 'node_ops', kind: 'node', type: 'APPROVAL', config: { name: '运维填报', participant: { strategy: 'FIXED_USER', value: '1' }, nodeForm: { formKey: scen.parentForm } } },
      { id: 'wait_children', kind: 'node', type: 'SUBFLOW_WAIT', config: { name: '等待子流程', waitActionIds: ['act_child'] } },
      { id: 'node_review', kind: 'node', type: 'APPROVAL', config: { name: '运维复核', participant: { strategy: 'FIXED_USER', value: '1' }, nodeForm: { formKey: scen.parentForm } } },
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
                targetProcessDefKey: scen.childDefKey, targetFormKey: scen.childForm, maxDispatch: 50,
                mapping: [{ targetField: 'summary', itemField: 'id' }],
                orchestration: 'CHILD', waitPolicy: 'ALL',
                writeBack: {
                  resultNodeKey: 'node_result',
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
  const save = await api('PUT', '/workflow/defs/' + parent.id + '/graph', graph);
  if (save.json.code !== 0) throw new Error('save parent graph: ' + JSON.stringify(save.json).slice(0, 260));
  const pub = await api('POST', '/workflow/defs/' + parent.id + '/publish');
  if (pub.json.code !== 0) throw new Error('publish parent: ' + JSON.stringify(pub.json).slice(0, 260));
  console.log('parent def ready: id=' + parent.id + ' key=' + pub.json.data.processKey);
  scen.parentDefId = parent.id;
  scen.parentDefKey = pub.json.data.processKey;
  fs.writeFileSync(OUT + '/p64p2-scenario.json', JSON.stringify(scen, null, 2));
  console.log('SCENARIO READY');
}
main().catch((e) => { console.error(String(e)); process.exit(1); });
