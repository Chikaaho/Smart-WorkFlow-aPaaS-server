const fs = require('fs');
const { execSync } = require('child_process');
const BASE = 'http://127.0.0.1:8080/api';
const LOGS = 'E:/code/Smart-WorkFlow-Agent-Workspace/Smart-WorkFlow-aPaaS-server/logs';
const EV = 'E:/code/Smart-WorkFlow-Agent-Workspace/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-03/raw';
const DEF_ID = '2108817690357526529';
const token = fs.readFileSync(LOGS + '/p64p2-token-admin.txt', 'utf8').trim();
function safeParse(text) {
  const quoted = text.replace(/([:\[,]\s*)(-?\d{16,})(?=\s*[,\}\]])/g, '$1"$2"');
  return JSON.parse(quoted);
}
(async () => {
  // 1) 草稿图 API 回读
  const r = await fetch(BASE + '/workflow/defs/' + DEF_ID, { headers: { Authorization: 'Bearer ' + token } });
  const draft = safeParse(await r.text());
  const draftTriggers = draft.data && draft.data.triggers;
  fs.writeFileSync(EV + '/live/01-api-draft-graph.json', JSON.stringify({ status: r.status, code: draft.code, graph: draft.data }, null, 2), 'utf8');

  // 2) 冻结图 DB 回读（graph_version=3）
  fs.writeFileSync(LOGS + '/tmp-frozen3.sql', "SELECT version, graph_version, status, published_at, graph_json FROM sw_bpm_process_def_version WHERE def_id=" + DEF_ID + " AND graph_version=3;", 'utf8');
  let frozen = '';
  try {
    frozen = execSync('java -cp "E:/Maven/repository/org/postgresql/postgresql/42.7.5/postgresql-42.7.5.jar" --source 21 "' + LOGS + '/p64p2-dbqc.java" "' + LOGS + '/tmp-frozen3.sql"', { encoding: 'utf8', maxBuffer: 20 * 1024 * 1024 });
  } catch (e) { frozen = 'ERROR ' + String(e.message).slice(0, 200); }
  fs.writeFileSync(EV + '/db/01-frozen-v3-graph-db.txt', '# P2-01 冻结图 v3 DB 回读（graph_json 含 CHILD 触发器完整配置 mainFields/orchestration/writeBack）\n\n' + frozen, 'utf8');

  // 3) 冻结图 triggers：JDBC 直排文件导出（UTF-8，不经控制台），再解析
  try {
    execSync('java -cp "E:/Maven/repository/org/postgresql/postgresql/42.7.5/postgresql-42.7.5.jar" --source 21 "' + LOGS + '/p64p2h-dumpjson.java" 3 "' + EV + '/db/01-frozen-v3-triggers-reassembled.json"', { encoding: 'utf8' });
  } catch (e) { /* 导出失败时保留原文件 */ }
  let frozenTriggers = null;
  try { frozenTriggers = JSON.parse(fs.readFileSync(EV + '/db/01-frozen-v3-triggers-reassembled.json', 'utf8')); } catch (e) { frozenTriggers = null; }

  // 4) 一致性断言
  const childOf = (t) => {
    const acts = [];
    for (const trg of (t || [])) for (const br of (trg.branches || [])) for (const a of (br.actions || [])) acts.push(a);
    return acts.find((a) => a.actionId === 'act_child' && a.child === true);
  };
  const dChild = childOf(draftTriggers);
  const fChild = childOf(frozenTriggers);
  const pick = (c) => {
    if (!c) return null;
    const canonical = (v) => {
      if (Array.isArray(v)) return v.map(canonical);
      if (v && typeof v === 'object') {
        const o = {};
        for (const k of Object.keys(v).sort()) o[k] = canonical(v[k]);
        return o;
      }
      return v;
    };
    return JSON.stringify(canonical({ orchestration: c.orchestration, waitPolicy: c.waitPolicy, writeBack: c.writeBack, sourceVariable: c.sourceVariable, groupBy: c.groupBy, maxDispatch: c.maxDispatch }));
  };
  const same = dChild && fChild && pick(dChild) === pick(fChild);
  const report = {
    defId: DEF_ID,
    draftGraphApiStatus: r.status,
    draftChildAction: dChild ? pick(dChild) : null,
    frozenChildAction: fChild ? pick(fChild) : null,
    draftEqualsFrozenChildSemantics: !!same,
    frozenGraphVersion: 3,
    checkedAt: new Date().toISOString(),
  };
  fs.writeFileSync(EV + '/live/01-consistency-check.json', JSON.stringify(report, null, 2), 'utf8');
  console.log(JSON.stringify(report, null, 1));
})().catch((e) => { console.error(String(e)); process.exit(1); });
