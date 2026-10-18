const fs = require('fs');
const { execSync } = require('child_process');
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const EV = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-03/raw';
function q(name, sql) {
  fs.writeFileSync(LOGS + '/tmp-' + name + '.sql', sql, 'utf8');
  return execSync('java -Dstdout.encoding=UTF-8 -cp "E:/Maven/repository/org/postgresql/postgresql/42.7.5/postgresql-42.7.5.jar" --source 21 "' + LOGS + '/p64p2-dbqc.java" "' + LOGS + '/tmp-' + name + '.sql"', { encoding: 'utf8' });
}
// 逐文件重跑只读查询（与原查询逐字一致）
const jobs = {
  'db/03-batch-all.txt': "SELECT id, batch_key, parent_instance_id, root_instance_id, parent_depth, round_no, wait_policy, wait_count, expected_count, settled_count, status, block_reason, source_record_id, source_record_version, settled_at, create_time FROM sw_bpm_child_batch ORDER BY id;",
  'db/03-items-all.txt': "SELECT id, batch_id, item_key, status, source_row_id, source_row_version, source_rows_json, target_record_id, target_instance_id, writeback_time, writeback_source, error_text FROM sw_bpm_child_item ORDER BY batch_id, id;",
  'db/03-parent-def-versions.txt': "SELECT pv.version, pv.graph_version, pv.status, pv.name, pv.form_key, pv.published_at, char_length(pv.graph_json) AS graph_len FROM sw_bpm_process_def_version pv WHERE pv.def_id=2108817690357526529 AND pv.deleted=0 ORDER BY pv.version;",
  'db/03-frozen-v2-nodes.txt': "SELECT n->>'id' AS node_id, n->>'type' AS type, n->'config' AS config FROM sw_bpm_process_def_version pv, LATERAL jsonb_array_elements(pv.graph_json::jsonb->'elements') n WHERE pv.def_id=2108817690357526529 AND pv.graph_version=2 AND n->>'kind'='node' ORDER BY n->>'id';",
  'db/03-settled-chain-history.txt': null, // 多段，单独处理
  'db/04-parent-hosts-final.txt': "SELECT h.id, h.parent_record_id, h.host_name, h.feedback, h.version, h.deleted, h.tenant_id FROM sw_form_table_pucs20ea5b h WHERE h.parent_record_id='bb8652b2-5f4e-4485-a58c-a3cafb069a70' ORDER BY h.create_time;",
  'db/04-parent-hosts-all-records.txt': "SELECT h.parent_record_id, count(*) AS rows, string_agg(h.id || '=' || coalesce(h.feedback,'NULL'), ' ; ') AS rows_brief FROM sw_form_table_pucs20ea5b h WHERE h.deleted=0 GROUP BY h.parent_record_id ORDER BY h.parent_record_id;",
  'db/01-frozen-v3-graph-db.txt': "SELECT version, graph_version, status, published_at, substring(graph_json from 1 for 900) AS graph_head FROM sw_bpm_process_def_version WHERE def_id=2108817690357526529 AND graph_version=3;",
  'db/03-frozen-v2-triggers.txt': "SELECT jsonb_pretty(graph_json::jsonb->'triggers') AS triggers FROM sw_bpm_process_def_version WHERE def_id=2108817690357526529 AND graph_version=2;",
};
for (const [file, sql] of Object.entries(jobs)) {
  if (!sql) continue;
  fs.writeFileSync(EV + '/' + file, q(file.replace(/[\/.]/g, '_'), sql), 'utf8');
  console.log('regen', file);
}
// settled-chain-history：四段查询重跑（头两段含中文任务名）
const p1 = q('sc1', "SELECT act_type_, act_id_, act_name_, assignee_, start_time_, end_time_ FROM act_hi_actinst WHERE proc_inst_id_='02434573-c48a-11f1-bd5c-00ff9e2a8dfb' ORDER BY start_time_;");
const p2 = q('sc2', "SELECT id_, name_, task_def_key_, assignee_, start_time_, end_time_, delete_reason_ FROM act_hi_taskinst WHERE proc_inst_id_='02434573-c48a-11f1-bd5c-00ff9e2a8dfb' ORDER BY start_time_;");
const p3 = q('sc3', "SELECT proc_inst_id_, id_, name_, assignee_, start_time_, end_time_ FROM act_hi_taskinst WHERE proc_inst_id_ IN ('049dd757-c48a-11f1-bd5c-00ff9e2a8dfb','049dd758-c48a-11f1-bd5c-00ff9e2a8dfb') ORDER BY start_time_;");
const p4 = q('sc4', "SELECT act_id_, act_type_, count(*) AS cnt FROM act_hi_actinst WHERE proc_inst_id_='02434573-c48a-11f1-bd5c-00ff9e2a8dfb' AND act_id_ IN ('wait_children','node_review','node_ops') GROUP BY act_id_, act_type_;");
fs.writeFileSync(EV + '/db/03-settled-chain-history.txt', [
  '# P2-03：SETTLED 链（父实例 02434573-c48a-11f1-bd5c-00ff9e2a8dfb / 批次 2108846864182882306）对象原流',
  '# 子实例：049dd757（张三 2 行）049dd758（李四 1 行）；等待节点 wait_children；复核任务 095aea5d',
  '',
  '## 父实例活动历史（ACT_HI_ACTINST：node_ops→wait_children→node_review 各恰 1 次=单次推进）', p1,
  '## 父实例任务历史（ACT_HI_TASKINST：运维填报 1 次 + 运维复核 1 次，含办理人/起止）', p2,
  '## 子实例任务历史（张三/李四子流程各 1 任务，创建→完成）', p3,
  '## 等待节点单次推进判定（wait_children 计数恰 1 起 1 终）', p4,
].join('\n'), 'utf8');
console.log('regen db/03-settled-chain-history.txt');

// 委托链 DB 文件（原混合编码）：头注释 node 重写 + 数据用 UTF-8 JVM 重跑
const d1 = q('dt1', "SELECT i.id, i.process_instance_id, i.business_key, i.status, i.def_version, i.initiator_id, i.create_time FROM sw_bpm_instance i WHERE i.process_instance_id IN ('90ab175d-c49c-11f1-8088-00ff9e2a8dfb','9345bf85-c49c-11f1-8088-00ff9e2a8dfb','95cd7be3-c49c-11f1-8088-00ff9e2a8dfb','75b73d3b-c48a-11f1-bd5c-00ff9e2a8dfb','77952509-c48a-11f1-bd5c-00ff9e2a8dfb','79761a17-c48a-11f1-bd5c-00ff9e2a8dfb') ORDER BY i.create_time;");
const d2 = q('dt2', "SELECT il.task_id_, il.type_, il.group_id_, il.user_id_ FROM act_ru_identitylink il WHERE il.task_id_ IN ('90b5298a-c49c-11f1-8088-00ff9e2a8dfb','9345bf92-c49c-11f1-8088-00ff9e2a8dfb','95cda300-c49c-11f1-8088-00ff9e2a8dfb');");
const d3 = q('dt3', "SELECT t.id_, t.name_, t.task_def_key_, t.assignee_, t.proc_inst_id_, t.create_time_ FROM act_ru_task t WHERE t.id_ IN ('90b5298a-c49c-11f1-8088-00ff9e2a8dfb','9345bf92-c49c-11f1-8088-00ff9e2a8dfb','95cda300-c49c-11f1-8088-00ff9e2a8dfb','77954c26-c48a-11f1-bd5c-00ff9e2a8dfb','79761a24-c48a-11f1-bd5c-00ff9e2a8dfb','75b73d48-c48a-11f1-bd5c-00ff9e2a8dfb') ORDER BY t.create_time_;");
fs.writeFileSync(EV + '/db/05-delegate-tasks-db.txt', [
  '# 委托链实例回读（实例↔记录↔状态：回执02 旧 3 实例 + 回执03 新 3 实例；清理后均为 DISCARDED，读数时点=清理前）',
  d1,
  '# 委托链任务候选人链接回读（未启用委托=岗位任职 5 人候选；启用=直派受托岗位持有者、零候选）',
  d2,
  '# 委托链 Flowable 运行任务回读（assignee/实例/创建时间；旧 3 任务在启停全程未被改动=冻结）',
  d3,
].join('\n'), 'utf8');
console.log('regen db/05-delegate-tasks-db.txt');

// 聚合 DB 文件：数据段已 UTF-8？重跑数据查询保持 node 头
const a1 = q('ag1', "SELECT id, process_instance_id, business_key, status, def_version, create_time, update_time FROM sw_bpm_instance WHERE process_instance_id IN ('9c3aba21-c49d-11f1-8088-00ff9e2a8dfb','6e30b060-c49d-11f1-8088-00ff9e2a8dfb','552d083f-c49d-11f1-8088-00ff9e2a8dfb');");
const a2 = q('ag2', "SELECT node_key, task_id, round_no, status, submitted_by, data_text, submit_time FROM sw_bpm_task_form_data WHERE process_instance_id='9c3aba21-c49d-11f1-8088-00ff9e2a8dfb' AND deleted=0 ORDER BY submit_time;");
const a3 = q('ag3', "SELECT node_key, task_id, participant_id, participant_name, participant_status, invalid_reason FROM sw_bpm_participant_snapshot WHERE process_instance_id='9c3aba21-c49d-11f1-8088-00ff9e2a8dfb' ORDER BY create_time;");
const a4 = q('ag4', "SELECT node_key, task_id, actor_id, outcome FROM sw_bpm_consensus_vote WHERE process_instance_id='9c3aba21-c49d-11f1-8088-00ff9e2a8dfb' ORDER BY create_time;");
const a5 = q('ag5', "SELECT t.id_, t.name_, t.task_def_key_, t.assignee_, t.proc_inst_id_ FROM act_ru_task t WHERE t.proc_inst_id_='9c3aba21-c49d-11f1-8088-00ff9e2a8dfb';");
fs.writeFileSync(EV + '/db/05-aggregate-db.txt', [
  '# 聚合会签实机链（实例 9c3aba21-c49d-11f1-8088-00ff9e2a8dfb，def_version=5）对象回读',
  '# 场景：node_a（候选=甲/乙）甲办理填 handlers=[丁,丙,丁]；node_b（候选=丙）丙填 handlers=[甲]（负控）；',
  '#       node_agg=CONSENSUS，participant=NODE_FORM_AGGREGATE{nodeKey:node_a, formField:handlers, round:CURRENT}',
  '# 断言：聚合恰生成 丁+丙 两会签任务（[丁,丙,丁] 去重），甲（node_b 填入）不出现；实例 APPROVED',
  '',
  '## 实例终态（sw_bpm_instance，读数时点含两轮自清理前遗留 RUNNING 实例对照；现均已 DISCARDED）', a1,
  '## 节点表单数据来源回读（sw_bpm_task_form_data，来源/轮次可追溯）', a2,
  '## 参与人快照回读（sw_bpm_participant_snapshot）', a3,
  '## 会签投票回读（sw_bpm_consensus_vote：丁/丙两票 APPROVE，ALL 结算）', a4,
  '## 会签运行任务回读（act_ru_task；读数时点任务未结，现实例已 APPROVED）', a5,
].join('\n'), 'utf8');
console.log('regen db/05-aggregate-db.txt');

// 门禁原件 GBK→UTF-8 行级修复（原件为实跑日志，仅按实际编码解码，不改写内容）
const g = EV + '/gates/03-server-gate-bootstrap-anchors.log';
const buf = fs.readFileSync(g);
const decU = new TextDecoder('utf-8', { fatal: false });
const decG = new TextDecoder('gbk');
const outLines = [];
for (const line of buf.toString('latin1').split('\n')) {
  // latin1 保守读字节，再按行判：能被 UTF-8 解释且含中文且无 mojibake 特征 → utf8；否则 gbk
  const raw = Buffer.from(line, 'latin1');
  const asU = decU.decode(raw);
  if (/[\uE000-\uF8FF]|鎻|閿|鑾|鏄|鐧|娣/.test(asU)) outLines.push(decG.decode(raw));
  else outLines.push(asU);
}
fs.writeFileSync(g, outLines.join('\n'), 'utf8');
console.log('gbk-decoded gates log');
