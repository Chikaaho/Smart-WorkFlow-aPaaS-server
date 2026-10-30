#!/usr/bin/env bash
# Phase 6C · CI-friendly 版本身份门禁（fail closed，BAO-09）。
#
# 用法：
#   scripts/check-version-identity.sh pom
#       结构检查：34/34 POM；根 GAV 为 ${revision} 且定义唯一开发默认 <revision>；
#       33 个子 POM 的 com.sw.ck 父版本为 ${revision}；旧工程版本字面量 0.1.0 命中 0；
#       不存在 parent 块内的字面版本（工程父链全部表达式化）。
#
#   scripts/check-version-identity.sh develop
#       Maven effective version：全 reactor project.version 必须全部等于根 POM 的
#       <revision> 开发默认值（0.2.0-SNAPSHOT）；无空值、占位符残留或模块分叉。
#
#   scripts/check-version-identity.sh release <version>
#       显式 -Drevision=<version>：全 reactor project.version 必须全部等于 <version>；
#       <version> 不得含 SNAPSHOT 或占位符。
#
#   scripts/check-version-identity.sh installed <local-repo> <version>
#       可消费 POM 检查：在给定 Maven local repository 中，com.sw.ck 已安装 POM 的
#       工程版本/父版本必须全部为 <version>，${revision} 命中 0；release 版本不得含
#       SNAPSHOT。供「唯一临时 local repository 执行 release override install」之后使用。
#
# 退出码：0 = 通过；非 0 = 不通过（任一断言失败即 fail closed）。
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
MVN_BIN="${MVN_BIN:-mvn}"
export MAVEN_OPTS="${MAVEN_OPTS:--Xmx2g}"

read -r -a mvn_flags <<< "${MVN_FLAGS:-}"

die() { echo "FAIL: $*" >&2; exit 1; }
ok()  { printf '  OK   %s\n' "$1"; }
bad() { printf '  FAIL %s\n' "$1"; failures=$((failures + 1)); }
failures=0

root_revision() { sed -n 's/.*<revision>\(.*\)<\/revision>.*/\1/p' "${REPO_DIR}/pom.xml" | head -1; }

all_poms() { (cd "${REPO_DIR}" && find "${PWD}" -name pom.xml -not -path '*/target/*' | sort); }

mode_pom() {
    # Phase 6C 补证 01 · 逐项枚举式检查（修复历史探针缺陷）：
    # 旧实现把 all_poms（REPO_DIR 相对路径）交给在「调用者 cwd」执行的 grep/awk，
    # cwd != REPO_DIR 时全部探测静默落空、计数为 0 → 注入后仍 PASS。
    # 现改为：all_poms 输出绝对路径；对 34 个 POM 逐文件断言，不合规文件输出
    # 相对路径与实际值；聚合计数只作汇总，不作判定依据。
    local root="${REPO_DIR}/pom.xml"
    local root_ver
    root_ver=$(sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' "${root}" | head -1)
    if [[ "${root_ver}" == '${revision}' ]]; then
        ok "根 POM 工程 version = \${revision}"
    else
        bad "根 POM 工程 version = ${root_ver:-<空>}（应为 \${revision}）"
    fi
    grep -qE '<revision>[^<]+</revision>' "${root}" || die "根 POM 未定义 <revision> 开发默认值"
    local total violations=0 f rel parent_ver gav_ver
    total=$(all_poms | wc -l | tr -d ' ')
    [[ "${total}" -eq 34 ]] || die "POM 总数 ${total} != 34"
    while read -r f; do
        rel="${f#"${REPO_DIR}"/}"
        if [[ "${f}" == "${root}" ]]; then
            # 根 POM：首个 <version> 即工程 GAV version
            gav_ver=$(sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' "${f}" | head -1)
            if [[ "${gav_ver}" == '${revision}' ]]; then
                continue
            fi
            bad "不合规 POM（根 GAV）: ${rel} 实际值=${gav_ver:-<空>}"
            violations=$((violations + 1))
            continue
        fi
        # 子 POM：com.sw.ck parent 块内的 version 必须恰为 ${revision}
        parent_ver=$(awk '/<parent>/{p=1;next} p&&/<version>/{print;exit} /<\/parent>/{p=0}' "${f}" \
            | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
        if [[ "${parent_ver}" == '${revision}' ]]; then
            continue
        fi
        bad "不合规 POM（parent version）: ${rel} 实际值=${parent_ver:-<缺失>}"
        violations=$((violations + 1))
    done < <(all_poms)
    [[ "${violations}" -eq 0 ]] && ok "34 个 POM 逐项核验通过（根 GAV + 33 个 parent version 均为 \${revision}）" \
        || true
    # 字面量兜底扫描（任意版本字段出现 0.1.0 即点名）
    local lit
    lit=$( { all_poms | xargs grep -n '<version>0.1.0</version>' 2>/dev/null || true; } | sed "s|${REPO_DIR}/||")
    if [[ -z "${lit}" ]]; then
        ok "旧工程版本字面量 0.1.0 命中 0"
    else
        printf '%s\n' "${lit}" | while IFS= read -r line; do bad "字面量 0.1.0: ${line}"; done
        violations=$((violations + $(printf '%s\n' "${lit}" | grep -c . || true)))
    fi
    [[ "${violations}" -eq 0 ]] || true
    if [[ "${violations}" -gt 0 ]]; then
        echo "RESULT: FAIL（pom 模式：${violations} 个不合规项）" >&2
        exit 1
    fi
}

run_flatten() { # $@ = 追加 mvn 参数（如 -Drevision=...）
    # 全反应堆执行 process-resources：Flatten 在该阶段为每个模块产出
    # target/.flattened-pom.xml（CI 变量已解析的可消费模型）；validate 阶段的
    # Enforcer 收敛守门随之真实执行。逐 POM 独立 -f 求值不可用：BOM import
    # （sw-dependencies:${revision}）依赖反应堆/local-repo 解析。
    (cd "${REPO_DIR}" && "${MVN_BIN}" ${mvn_flags+"${mvn_flags[@]}"} "$@" -B -q process-resources)
}

check_flattened() { # $1=期望版本 $2=模式名
    local expected="$1" label="$2"
    local files count bad_placeholders bad_version=0 f
    # 反应堆项目数为 32（根 + 31）：sw-basic-job 与 sw-basic-storage 两个聚合 POM
    # 不在 <modules> 内、不属于反应堆，其 ${revision} 结构由 pom 模式核验。
    files=$( { find "${REPO_DIR}" -path '*/target/.flattened-pom.xml' 2>/dev/null || true; } | sort)
    count=$(printf '%s\n' "${files}" | { grep -c . || true; })
    [[ "${count}" -eq 32 ]] && ok "${label}: 32/32 反应堆项目 flattened POM 产出" \
        || bad "${label}: flattened POM 数量 ${count} != 32（反应堆项目数）"
    bad_placeholders=$( { printf '%s\n' "${files}" | xargs grep -l '\${revision}' 2>/dev/null || true; } | { grep -c . || true; })
    [[ "${bad_placeholders}" -eq 0 ]] && ok "${label}: flattened POM 无未解析 \${revision}" \
        || bad "${label}: ${bad_placeholders} 个 flattened POM 仍含 \${revision}"
    for f in ${files}; do
        if ! grep -q "<version>${expected}</version>" "${f}"; then
            bad "${label}: $(basename "$(dirname "$(dirname "${f}")")") 版本不是 ${expected}"
            bad_version=$((bad_version + 1))
        fi
    done
    [[ "${bad_version}" -eq 0 ]] && ok "${label}: 全部 flattened POM 的工程/父版本精确为 ${expected}" \
        || true
}

mode_develop() {
    local expected
    expected="$(root_revision)"
    [[ -n "${expected}" ]] || die "根 POM 缺少 <revision>"
    echo "  开发默认 <revision> = ${expected}"
    run_flatten
    check_flattened "${expected}" "develop 默认解析"
}

mode_release() {
    local expected="$1"
    [[ "${expected}" != *SNAPSHOT* && "${expected}" != *'${'* ]] \
        || die "release 版本不得含 SNAPSHOT 或占位符: ${expected}"
    echo "  显式 -Drevision=${expected}"
    run_flatten -Drevision="${expected}"
    check_flattened "${expected}" "release override"
}

mode_workflow() { # 静态结构检查：Release workflow 的版本链必须 Maven 求值 + 同源驱动
    local wf="${REPO_DIR}/.github/workflows/build-release.yml"
    [[ -f "${wf}" ]] || die "未找到 Release workflow: ${wf}"
    grep -q 'maven-help-plugin' "${wf}" && grep -q 'forceStdout' "${wf}" \
        && ok "workflow 以 Maven help:evaluate 求值 effective version" \
        || bad "workflow 未以 Maven 求值 effective version"
    if grep -q "grep -m1 -oE '<version>" "${wf}"; then
        bad "workflow 仍使用 XML grep 读取版本（会得到字面 \${revision}）"
    else
        ok "workflow 无 XML grep 读版本"
    fi
    grep -q "!= '0.2.0-SNAPSHOT'" "${wf}" \
        && ok "workflow 断言 develop 默认版本为 0.2.0-SNAPSHOT" \
        || bad "workflow 缺少 develop 默认版本断言"
    grep -q '%-SNAPSHOT' "${wf}" \
        && ok "workflow 由 effective version 剥离 -SNAPSHOT 解析正式值" \
        || bad "workflow 未从 effective version 解析正式值"
    grep -qE 'REVISION=.*steps\.version\.outputs\.release_version' "${wf}" \
        && ok "workflow 以 -Drevision 同版本驱动生产构建入口" \
        || bad "workflow 未将发布版本传入生产构建入口"
    grep -q 'build\.version' "${wf}" \
        && ok "workflow 以制品内 build.version 标记为发布版本唯一来源并做一致性断言" \
        || bad "workflow 未校验制品版本标记一致性"
    local n
    n=$(grep -c 'steps\.version\.outputs\.release_version' "${wf}" || true)
    [[ "${n}" -ge 3 ]] && ok "Release 标题/注释/上传制品名同源 release_version（引用 ${n} 处）" \
        || bad "发布元数据未同源 release_version（引用仅 ${n} 处）"
    if grep -E -- '--title|--notes' "${wf}" | grep -vE 'release_version' | grep -q .; then
        bad "发布标题/注释存在非 release_version 的版本来源"
    else
        ok "发布标题/注释无独立版本拼接"
    fi
}

case "${1:-}" in
    pom)
        mode_pom
        ;;
    workflow)
        mode_workflow
        ;;
    develop)
        mode_develop
        ;;
    release)
        [[ -n "${2:-}" ]] || die "用法: $0 release <version>"
        mode_release "$2"
        ;;
    installed)
        [[ -n "${2:-}" && -n "${3:-}" ]] || die "用法: $0 installed <local-repo> <version>"
        repo="$2"; expected="$3"
        [[ -d "${repo}" ]] || die "local repository 不存在: ${repo}"
        # 检查对象=被验证版本目录下的安装 POM（repo 可能同时缓存其它历史版本，如 0.1.0，
        # 那些与本门禁无关；占位符检查仍覆盖全部 com.sw.ck 安装 POM）。
        poms=$( { find "${repo}/com/sw/ck" -path "*/${expected}/*.pom" 2>/dev/null || true; } | sort)
        count=$(printf '%s\n' "${poms}" | { grep -c . || true; })
        # 反应堆项目 32 个（两个聚合 POM 不在 <modules> 内、不参与 install），故期望 32。
        [[ "${count}" -eq 32 ]] || bad "版本 ${expected} 下已安装 com.sw.ck POM 数 ${count} != 32（root + 31 个反应堆子模块）"
        ok "版本 ${expected} 下已安装 com.sw.ck POM：${count} 个"
        n=$(printf '%s\n' "${poms}" | { xargs grep -l '\${revision}' 2>/dev/null || true; } | { grep -c . || true; })
        [[ "${n}" -eq 0 ]] && ok "可消费 POM 无未解析 \${revision}" \
            || bad "${n} 个已安装 POM 仍含未解析 \${revision}"
        if [[ "${expected}" != *SNAPSHOT* ]]; then
            # 只检查版本字段（工程/父版本）不得为 SNAPSHOT；根 POM 的 <properties> 中
            # <revision> 属性定义由 flatten resolveCiFriendliesOnly 保留（属性不参与解析），
            # 不构成版本字段，不做字符串级否定匹配。
            n=$(printf '%s\n' "${poms}" | { xargs grep -lE '<version>[^<]*-SNAPSHOT</version>' 2>/dev/null || true; } | { grep -c . || true; })
            [[ "${n}" -eq 0 ]] && ok "可消费 POM 版本字段无 SNAPSHOT 残留" \
                || bad "${n} 个已安装 POM 版本字段含 SNAPSHOT"
        fi
        n=$(printf '%s\n' "${poms}" | { xargs grep -l "<version>${expected}</version>" 2>/dev/null || true; } | { grep -c . || true; })
        [[ "${n}" -eq "${count}" ]] && ok "可消费 POM 工程版本全部为 ${expected}（${n}/${count}）" \
            || bad "仅 ${n}/${count} 个已安装 POM 版本为 ${expected}"
        n=$(printf '%s\n' "${poms}" | while read -r f; do
                awk '/<parent>/{p=1} p&&/<version>/{print FILENAME": "$0} /<\/parent>/{p=0}' "${f}"
            done | { grep -v "<version>${expected}</version>" || true; } | { grep -c . || true; })
        [[ "${n}" -eq 0 ]] && ok "可消费 POM 父版本全部为 ${expected}" \
            || bad "${n} 处已安装 POM 父版本不是 ${expected}"
        ;;
    *)
        die "用法: $0 {pom|workflow|develop|release <version>|installed <local-repo> <version>}"
        ;;
esac

if [[ "${failures}" -eq 0 ]]; then
    echo "RESULT: PASS（check-version-identity ${1:-}）"
    exit 0
fi
echo "RESULT: FAIL（${failures} 项不满足）" >&2
exit 1
