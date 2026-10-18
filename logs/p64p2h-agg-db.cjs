const fs = require('fs');
const { execSync } = require('child_process');
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const EV = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-03/raw/db';
const J = (sqlFile) => execSync('java -cp "E:/Maven/repository/org/postgresql/postgresql/42.7.5/postgresql-42.7.5.jar" --source 21 "' + LOGS + '/p64p2-dbqc.java" "' + sqlFile + '"', { encoding: 'utf8' });

function q(name, sql) {
  fs.writeFileSync(LOGS + '/tmp-' + name + '.sql', sql, 'utf8');
  try { return J(LOGS + '/tmp-' + name + '.sql'); } catch (e) { return 'ERROR: ' + String(e.stdout || e); }
}

const out = [];
out.push('# 聚合会签实机链（实例 9c3aba21-c49d-11f1-8088-00ff9e2a8dfb，def_version=5）对象回读');
out.push('# 场景：node_a（候选=甲/乙）甲办理填 handlers=[丁,丙,丁]；node_b（候选=丙）丙填 handlers=[甲]（负控）；');
out.push('#       node_agg=CONSENSUS，participant=NODE_FORM_AGGREGATE{nodeKey:node_a, formField:handlers, round:CURRENT}');
out.push('# 断言：聚合恰生成 丁+丙 两会签任务（[丁,丙,丁] 去重），甲（node_b 填入）不出现；实例 APPROVED');
out.push('');
out.push('## 实例终态（sw_bpm_instance，含两轮自清理前遗留 RUNNING 实例对照）');
out.push(q('inst2', "SELECT id, process_instance_id, business_key, status, def_version, create_time, update_time FROM sw_bpm_instance WHERE process_instance_id IN ('9c3aba21-c49d-11f1-8088-00ff9e2a8dfb','6e30b060-c49d-11f1-8088-00ff9e2a8dfb','552d083f-c49d-11f1-8088-00ff9e2a8dfb');"));
out.push('');
out.push('## 节点表单数据来源回读（sw_bpm_task_form_data，来源/轮次可追溯）');
out.push(q('nf2', "SELECT node_key, task_id, round_no, status, submitted_by, data_text, submit_time FROM sw_bpm_task_form_data WHERE process_instance_id='9c3aba21-c49d-11f1-8088-00ff9e2a8dfb' AND deleted=0 ORDER BY submit_time;"));
out.push('');
out.push('## 参与人快照回读（sw_bpm_participant_snapshot）');
out.push(q('ps', "SELECT node_key, task_id, participant_id, participant_name, participant_status, invalid_reason FROM sw_bpm_participant_snapshot WHERE process_instance_id='9c3aba21-c49d-11f1-8088-00ff9e2a8dfb' ORDER BY create_time;"));
out.push('');
out.push('## 会签投票回读（sw_bpm_consensus_vote：丁/丙两票 APPROVE，ALL 结算）');
out.push(q('vote2', "SELECT node_key, task_id, actor_id, outcome FROM sw_bpm_consensus_vote WHERE process_instance_id='9c3aba21-c49d-11f1-8088-00ff9e2a8dfb' ORDER BY create_time;"));
out.push('');
out.push('## 会签运行任务回读（act_ru_task，聚合节点任务与指派）');
out.push(q('aggt', "SELECT t.id_, t.name_, t.task_def_key_, t.assignee_, t.proc_inst_id_ FROM act_ru_task t WHERE t.proc_inst_id_='9c3aba21-c49d-11f1-8088-00ff9e2a8dfb';"));
fs.writeFileSync(EV + '/05-aggregate-db.txt', out.join('\n') + '\n', 'utf8');
console.log('OK 05-aggregate-db.txt');
