const fs = require('fs');
const { execSync } = require('child_process');
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const EV = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-03/raw/db';
function q(name, sql) {
  fs.writeFileSync(LOGS + '/tmp-' + name + '.sql', sql, 'utf8');
  try {
    return execSync('java -Dfile.encoding=UTF-8 -cp "E:/Maven/repository/org/postgresql/postgresql/42.7.5/postgresql-42.7.5.jar" --source 21 "' + LOGS + '/p64p2-dbqc.java" "' + LOGS + '/tmp-' + name + '.sql"', { encoding: 'utf8' });
  } catch (e) { return 'ERROR: ' + String((e.stdout || '') + (e.stderr || '')).slice(0, 300); }
}
const out = [];
out.push('# P2-07c 业务对象终态回读（收尾时点，服务停止前）');
out.push('');
out.push('## 批次终态（1 SETTLED + 4 BLOCKED 保留；BLOCKED 父实例均已 DISCARDED=无活动等待节点，无写回/推进资格）');
out.push(q('fb', "SELECT b.id, b.status, b.wait_policy, b.expected_count, b.settled_count, b.parent_instance_id, i.status AS parent_status FROM sw_bpm_child_batch b LEFT JOIN sw_bpm_instance i ON i.process_instance_id=b.parent_instance_id ORDER BY b.id;"));
out.push('');
out.push('## 批次项终态（WRITTEN 4 项保留；FAILED 6 项为历史链诊断留痕）');
out.push(q('fi', "SELECT status, count(*) FROM sw_bpm_child_item GROUP BY status;"));
out.push('');
out.push('## 委托关系终态（DISABLED，1 条，deleted=0）');
out.push(q('fd', "SELECT id, source_post_id, target_post_id, scope_type, status, deleted FROM sys_post_delegate;"));
out.push('');
out.push('## 实例终态（全部 31 实例两态分布；0 RUNNING）');
out.push(q('finst', "SELECT status, count(*) FROM sw_bpm_instance WHERE deleted=0 GROUP BY status;"));
out.push('');
out.push('## Flowable 运行任务（act_ru_task 应为 0 行）');
out.push(q('ftask', "SELECT count(*) AS running_tasks FROM act_ru_task;"));
out.push('');
out.push('## 运行实例（act_ru_execution 非流程根应无活动；总数只作参考）');
out.push(q('fexec', "SELECT count(*) AS ru_executions FROM act_ru_execution;"));
fs.writeFileSync(EV + '/07-final-object-states.txt', out.join('\n') + '\n', 'utf8');
console.log(out.join('\n').split('\n').filter((l) => !l.startsWith('#')).join('\n').slice(0, 1800));
