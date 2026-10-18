#!/bin/bash
# P64 阶段Ⅱ 回执03 Server 门禁重跑（engine → process → bootstrap 迁移锚）
# 有界执行：顺序三段，输出落盘 evidence/phase2-03/raw/gates/，每段记录退出码
set -u
ROOT=/e/code/Smart-WorkFlow-Agent-Workspace
SRV=$ROOT/Smart-WorkFlow-aPaaS-server
EV=$ROOT/product/p64-mes-advanced-orchestration/receipts/evidence/phase2-03/raw/gates
cd "$SRV" || exit 90
export MAVEN_OPTS="-Xmx2g"
{
  echo "== 运行身份 =="
  date -Iseconds
  git rev-parse HEAD
  git status --short | grep -v '^??' | head -5
  echo "tracked-dirty-count=$(git status --short | grep -cv '^??')"
} > "$EV/03-server-gates-run-identity.txt"
echo "[gates] engine start $(date -Iseconds)"
mvn -B -o -pl sw-biz/sw-bpm/sw-bpm-engine test > "$EV/03-server-gate-engine.log" 2>&1
echo "$?" > "$EV/03-server-gate-engine.exit"
echo "[gates] engine done exit=$(cat "$EV/03-server-gate-engine.exit") $(date -Iseconds)"
echo "[gates] process start $(date -Iseconds)"
mvn -B -o -pl sw-biz/sw-bpm/sw-bpm-process test > "$EV/03-server-gate-process.log" 2>&1
echo "$?" > "$EV/03-server-gate-process.exit"
echo "[gates] process done exit=$(cat "$EV/03-server-gate-process.exit") $(date -Iseconds)"
source logs/p64p2-session.env || true
export P64_PG_URL="jdbc:postgresql://127.0.0.1:5432/postgres?stringtype=unspecified"
export P64_PG_USERNAME=postgres
export P64_PG_PASSWORD=123456
echo "[gates] bootstrap anchors start $(date -Iseconds)"
mvn -B -o -pl sw-bootstrap test "-Dtest=FlywayFullChainH2Test,FlywayFullChainPostgresTest,P64AppendMigrationUpgradePostgresTest" -Dsurefire.failIfNoSpecifiedTests=false > "$EV/03-server-gate-bootstrap-anchors.log" 2>&1
echo "$?" > "$EV/03-server-gate-bootstrap-anchors.exit"
echo "[gates] anchors done exit=$(cat "$EV/03-server-gate-bootstrap-anchors.exit") $(date -Iseconds)"
echo "[gates] ALL DONE"
