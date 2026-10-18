const fs = require('fs');
const { execSync } = require('child_process');
const BASE = 'http://127.0.0.1:8080/api';
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const EV = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-03/raw';
const tokenOf = (u) => fs.readFileSync(LOGS + '/p64p2-token-' + u + '.txt', 'utf8').trim();
function safeParse(text) {
  const quoted = text.replace(/([:\[,]\s*)(-?\d{16,})(?=\s*[,\}\]])/g, '$1"$2"');
  return JSON.parse(quoted);
}
async function api(token, method, path) {
  const r = await fetch(BASE + path, { method, headers: { Authorization: 'Bearer ' + token } });
  const text = await r.text();
  let json = {};
  try { json = safeParse(text); } catch { json = { raw: text.slice(0, 200) }; }
  return { status: r.status, code: json.code, msg: json.msg, data: json.data, rawHead: text.slice(0, 200) };
}
function q(name, sql) {
  fs.writeFileSync(LOGS + '/tmp-' + name + '.sql', sql, 'utf8');
  try {
    return execSync('java -cp "E:/Maven/repository/org/postgresql/postgresql/42.7.5/postgresql-42.7.5.jar" --source 21 "' + LOGS + '/p64p2-dbqc.java" "' + LOGS + '/tmp-' + name + '.sql"', { encoding: 'utf8' });
  } catch (e) { return 'ERROR: ' + String((e.stdout || '') + (e.stderr || '')); }
}

(async () => {
  // ============ P2-03：SETTLED 链对象原流 ============
  const p03 = [];
  p03.push('# P2-03：SETTLED 链（父实例 02434573-c48a-11f1-bd5c-00ff9e2a8dfb / 批次 2108846864182882306）对象原流');
  p03.push('# 子实例：049dd757（张三 2 行）049dd758（李四 1 行）；等待节点 wait_children；复核任务 095aea5d');
  p03.push('');
  p03.push('## 父实例活动历史（ACT_HI_ACTINST：node_ops→wait_children→node_review 各恰 1 次=单次推进）');
  p03.push(q('hist1', "SELECT act_type_, act_id_, act_name_, assignee_, start_time_, end_time_ FROM act_hi_actinst WHERE proc_inst_id_='02434573-c48a-11f1-bd5c-00ff9e2a8dfb' ORDER BY start_time_;"));
  p03.push('');
  p03.push('## 父实例任务历史（ACT_HI_TASKINST：运维填报 1 次 + 运维复核 1 次，含办理人/起止）');
  p03.push(q('hist2', "SELECT id_, name_, task_def_key_, assignee_, start_time_, end_time_, delete_reason_ FROM act_hi_taskinst WHERE proc_inst_id_='02434573-c48a-11f1-bd5c-00ff9e2a8dfb' ORDER BY start_time_;"));
  p03.push('');
  p03.push('## 子实例任务历史（张三/李四子流程各 1 任务，创建→完成）');
  p03.push(q('hist3', "SELECT proc_inst_id_, id_, name_, assignee_, start_time_, end_time_ FROM act_hi_taskinst WHERE proc_inst_id_ IN ('049dd757-c48a-11f1-bd5c-00ff9e2a8dfb','049dd758-c48a-11f1-bd5c-00ff9e2a8dfb') ORDER BY start_time_;"));
  p03.push('');
  p03.push('## 等待节点单次推进判定（wait_children 计数应恰 1 起 1 终）');
  p03.push(q('hist4', "SELECT act_id_, act_type_, count(*) AS cnt FROM act_hi_actinst WHERE proc_inst_id_='02434573-c48a-11f1-bd5c-00ff9e2a8dfb' AND act_id_ IN ('wait_children','node_review','node_ops') GROUP BY act_id_, act_type_;"));
  fs.writeFileSync(EV + '/db/03-settled-chain-history.txt', p03.join('\n') + '\n', 'utf8');
  console.log('OK 03-settled-chain-history.txt');

  // ============ P2-04：授权读取/拒绝原结果 ============
  const CHILD_FORM_KEY = 'p64p2b_child_form';
  const ownRecord = 'c899a2c8-2b9d-411c-8bd7-6927931a9559';  // 张三子记录（其 2 行）
  const otherRecord = 'f135af8f-e8d5-4e2f-a8ae-bed935927808'; // 李四子记录（其 1 行）
  const p04 = [];
  const zhangRead = await api(tokenOf('zhangsan'), 'GET', '/form/data/' + CHILD_FORM_KEY + '/' + ownRecord);
  const jiaReadOther = await api(tokenOf('jia'), 'GET', '/form/data/' + CHILD_FORM_KEY + '/' + ownRecord);
  const jiaReadOwnNone = await api(tokenOf('jia'), 'GET', '/form/data/' + CHILD_FORM_KEY + '/' + otherRecord);
  const adminRead = await api(tokenOf('admin'), 'GET', '/form/data/' + CHILD_FORM_KEY + '/' + ownRecord);
  p04.push('# P2-04：记录级授权读取/拒绝原结果（子表单 ' + CHILD_FORM_KEY + '）');
  p04.push('# 对照：张三读自己子记录（应 200）；甲（无业务角色，data_scope fail-closed）读他人记录（应拒绝）；管理员读（应 200）');
  p04.push('');
  p04.push('## 张三读本人子记录 ' + ownRecord);
  p04.push('HTTP ' + zhangRead.status + ' code=' + zhangRead.code + ' msg=' + zhangRead.msg);
  p04.push('bodyHead=' + JSON.stringify(zhangRead.data).slice(0, 400));
  p04.push('');
  p04.push('## 甲读张三子记录 ' + ownRecord + '（越权读取尝试）');
  p04.push('HTTP ' + jiaReadOther.status + ' code=' + jiaReadOther.code + ' msg=' + jiaReadOther.msg);
  p04.push('');
  p04.push('## 甲读李四子记录 ' + otherRecord + '（越权读取尝试）');
  p04.push('HTTP ' + jiaReadOwnNone.status + ' code=' + jiaReadOwnNone.code + ' msg=' + jiaReadOwnNone.msg);
  p04.push('');
  p04.push('## 管理员读张三子记录（对照 200）');
  p04.push('HTTP ' + adminRead.status + ' code=' + adminRead.code + ' msg=' + adminRead.msg);
  fs.writeFileSync(EV + '/db/04-record-authz-probes.txt', p04.join('\n') + '\n', 'utf8');
  console.log('OK 04-record-authz-probes.txt');
  console.log('zhang=' + zhangRead.status + '/' + zhangRead.code + ' jiaOther=' + jiaReadOther.status + '/' + jiaReadOther.code + ' admin=' + adminRead.status + '/' + adminRead.code);
})().catch((e) => { console.error(String(e)); process.exit(1); });
