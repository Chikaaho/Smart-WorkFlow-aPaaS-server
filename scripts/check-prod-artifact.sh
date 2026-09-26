#!/usr/bin/env bash
# Phase 6B · 正式生产 Boot Jar 制品门禁（fail closed）。
#
# 用途：在任何发布动作之前校验 Boot Jar 是否满足生产制品的负向清单与正向清单。
#   负向清单必须全部为 0：H2 驱动、五个 dev-only 验证适配器（含嵌套类）、MockCloudProvider、
#                         application-dev.yml / application-local.yml、db/migration/devseed/**。
#   正向清单必须全部存在：application.yml、application-prod.yml、PostgreSQL 驱动、
#                         生产 Flyway 迁移、正式 IoT provider、AgentGraphDebug*。
#   另校验生产身份标记（prod profile 注入的 META-INF/production-build.properties）。
#
# 退出码：0 = 通过；非 0 = 不通过（任一断言失败即 fail closed）。
# 用法：scripts/check-prod-artifact.sh <boot-jar>   （默认 sw-bootstrap/target/bootstrap.jar）
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
JAR="${1:-${REPO_DIR}/sw-bootstrap/target/bootstrap.jar}"

[[ -f "${JAR}" ]] || { echo "FAIL: 未找到制品 ${JAR}" >&2; exit 2; }
command -v unzip >/dev/null 2>&1 || { echo "FAIL: 缺少 unzip，无法校验制品" >&2; exit 2; }

WORK="$(mktemp -d)"
cleanup() { rm -rf -- "${WORK}"; }
trap cleanup EXIT

failures=0
ok()   { printf '  OK   %s\n' "$1"; }
bad()  { printf '  FAIL %s\n' "$1"; failures=$((failures + 1)); }

# 外层清单（含 BOOT-INF/classes/** 与 BOOT-INF/lib/*.jar 的名字）
unzip -Z1 "${JAR}" > "${WORK}/outer.txt"
# 全部 class 条目（外层 classes + 所有嵌套 jar 内的条目）
: > "${WORK}/classes.txt"
: > "${WORK}/resources.txt"

# 1) BOOT-INF/classes 下的资源与类
grep -E '^BOOT-INF/classes/' "${WORK}/outer.txt" >> "${WORK}/resources.txt" || true

# 2) 逐个嵌套 jar 展开（模块 jar 内的类与资源）
mkdir -p "${WORK}/lib"
while IFS= read -r entry; do
    case "${entry}" in
        BOOT-INF/lib/*.jar) ;;
        *) continue ;;
    esac
    name="$(basename -- "${entry}")"
    unzip -p "${JAR}" "${entry}" > "${WORK}/lib/${name}"
    unzip -Z1 "${WORK}/lib/${name}" >> "${WORK}/classes.txt" 2>/dev/null || true
    echo "NESTED-LIB ${name}" >> "${WORK}/resources.txt"
done < "${WORK}/outer.txt"

printf '制品：%s\n' "${JAR}"
printf '字节：%s\n' "$(wc -c < "${JAR}" | tr -d ' ')"
printf 'sha256：%s\n' "$(shasum -a 256 "${JAR}" | awk '{print $1}')"
echo

echo "== 负向清单（必须为 0）=="
n=$(grep -cE '^BOOT-INF/lib/h2-[^/]*\.jar$' "${WORK}/outer.txt" || true)
[[ "${n}" -eq 0 ]] && ok "不含 H2 驱动 jar" || bad "含 H2 驱动 jar（${n}）"

for t in P58DebugNotifyController P58DebugNotifyChannelAdapter P58DebugParticipantAdapter; do
    n=$(grep -cE "(^|/)${t}([$][^/]*)?[.]class$" "${WORK}/classes.txt" || true)
    [[ "${n}" -eq 0 ]] && ok "不含 ${t}（含嵌套类）" || bad "含 ${t}（${n} 个 class）"
done
for t in VerificationRunner BpmVerificationRunner; do
    n=$(grep -cE "(^|/)${t}([$][^/]*)?[.]class$" "${WORK}/classes.txt" || true)
    [[ "${n}" -eq 0 ]] && ok "不含 ${t}（含嵌套类）" || bad "含 ${t}（${n} 个 class）"
done

n=$(grep -cE "(^|/)MockCloudProvider([$][^/]*)?[.]class$" "${WORK}/classes.txt" || true)
[[ "${n}" -eq 0 ]] && ok "不含 MockCloudProvider" || bad "含 MockCloudProvider（${n} 个 class）"

for r in BOOT-INF/classes/application-dev.yml BOOT-INF/classes/application-local.yml; do
    if grep -qx -- "${r}" "${WORK}/resources.txt"; then bad "含 ${r}"; else ok "不含 ${r}"; fi
done

n=$(grep -cE '^BOOT-INF/classes/db/migration/devseed/' "${WORK}/outer.txt" || true)
[[ "${n}" -eq 0 ]] && ok "不含 db/migration/devseed/**" || bad "含 db/migration/devseed/**（${n} 项）"

echo
echo "== 正向清单（必须存在）=="
for r in BOOT-INF/classes/application.yml BOOT-INF/classes/application-prod.yml; do
    if grep -qx -- "${r}" "${WORK}/resources.txt"; then ok "存在 ${r}"; else bad "缺少 ${r}"; fi
done

n=$(grep -cE '^BOOT-INF/lib/postgresql-[^/]*\.jar$' "${WORK}/outer.txt" || true)
[[ "${n}" -ge 1 ]] && ok "存在 PostgreSQL 驱动（${n}）" || bad "缺少 PostgreSQL 驱动"

n=$(grep -cE '^BOOT-INF/classes/db/migration/(postgresql|h2)/V[0-9]+__.*\.sql$' "${WORK}/outer.txt" || true)
[[ "${n}" -ge 1 ]] && ok "存在生产 Flyway 迁移（${n} 个）" || bad "缺少生产 Flyway 迁移"

n=$(grep -cE "(^|/)TencentCloudProvider\.class$" "${WORK}/classes.txt" || true)
[[ "${n}" -ge 1 ]] && ok "存在正式 IoT provider（TencentCloudProvider）" || bad "缺少正式 IoT provider"

n=$(grep -cE "(^|/)AgentGraphDebug[A-Za-z0-9_]*\.class$" "${WORK}/classes.txt" || true)
[[ "${n}" -ge 1 ]] && ok "存在 AgentGraphDebug* 正式能力（${n} 个 class）" || bad "缺少 AgentGraphDebug* 正式能力"

echo
echo "== 生产身份标记 =="
# Spring Boot repackage 把源 jar 的 META-INF/** 保留在**制品根目录**，故按根优先、BOOT-INF 兜底定位。
marker=""
for candidate in META-INF/production-build.properties BOOT-INF/classes/META-INF/production-build.properties; do
    if grep -qx -- "${candidate}" "${WORK}/outer.txt"; then marker="${candidate}"; break; fi
done
if [[ -n "${marker}" ]]; then
    echo "  位置: ${marker}"
    unzip -p "${JAR}" "${marker}" | sed 's/^/  /'
    if unzip -p "${JAR}" "${marker}" | grep -qE '^build[.]profile=prod$'; then
        ok "生产身份标记 build.profile=prod"
    else
        bad "生产身份标记缺少 build.profile=prod"
    fi
    # Phase 6C · 版本身份断言：build.version 必须已解析；release 模式（提供 EXPECTED_VERSION）
    # 时必须与期望发布版本精确一致，且期望版本自身不得为 SNAPSHOT/占位符。
    marker_version="$(unzip -p "${JAR}" "${marker}" | grep -E '^build[.]version=' | head -1 | cut -d= -f2- || true)"
    if [[ -z "${marker_version}" ]]; then
        bad "身份标记缺少 build.version（Phase 6C 版本身份缺失）"
    elif [[ "${marker_version}" == *'${'* || -z "${marker_version//[[:space:]]/}" ]]; then
        bad "build.version 未解析（占位符泄漏）: ${marker_version}"
    else
        ok "build.version 已解析: ${marker_version}"
    fi
    if [[ -n "${EXPECTED_VERSION:-}" ]]; then
        if [[ "${EXPECTED_VERSION}" == *SNAPSHOT* || "${EXPECTED_VERSION}" == *'${'* || -z "${EXPECTED_VERSION//[[:space:]]/}" ]]; then
            bad "期望发布版本不合法（不得为 SNAPSHOT/占位符）: ${EXPECTED_VERSION}"
        elif [[ "${marker_version}" == "${EXPECTED_VERSION}" ]]; then
            ok "build.version 与期望发布版本一致: ${EXPECTED_VERSION}"
        else
            bad "build.version=${marker_version:-<空>} 与期望发布版本 ${EXPECTED_VERSION} 不一致"
        fi
    fi
else
    bad "缺少生产身份标记 production-build.properties（该制品不是以 prod profile 构建）"
fi

echo
if [[ "${failures}" -eq 0 ]]; then
    echo "RESULT: PASS（负向 0 违规，正向齐备）"
    exit 0
fi
echo "RESULT: FAIL（${failures} 项不满足）" >&2
exit 1
