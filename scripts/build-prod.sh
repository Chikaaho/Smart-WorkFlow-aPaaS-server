#!/usr/bin/env bash
# Phase 6B · 仓内唯一、可复用的正式生产构建入口。
#
# 语义（顺序固定，任一步失败即整体失败并 fail closed）：
#   1) 在**当前最终源码快照**上先跑完整测试门禁；
#   2) 以 `prod` profile 生成正式 Boot Jar（测试已在第 1 步于同一快照执行，此处跳过重复测试）；
#   3) 执行制品门禁（负向/正向清单 + 生产身份标记），不通过即失败；
#   4) 输出唯一命令、输出路径、大小、sha256 与生产身份标记，供回执记录。
#
# 环境变量：
#   MVN_FLAGS   追加给 mvn 的参数（本地可复跑的离线验证用 `MVN_FLAGS=-o`；CI 无需设置）
#   MVN_BIN     mvn 可执行文件（默认 mvn）
#
# 用法：scripts/build-prod.sh
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
MVN_BIN="${MVN_BIN:-mvn}"
export MAVEN_OPTS="-Xmx2g"

read -r -a mvn_flags <<< "${MVN_FLAGS:-}"

# Phase 6C · CI-friendly 版本身份：REVISION 可选透传。设置后 -Drevision=<value> 必须同时
# 作用于全量测试、prod 打包与制品门禁（不能测试一个版本、打包另一个版本），并以
# EXPECTED_VERSION 交给制品门禁做版本一致性断言；不设置则使用根 POM 的开发默认
# revision（0.1.2-SNAPSHOT），门禁此时只断言 build.version 已解析。
REVISION="${REVISION:-}"
read -r -a revision_args <<< "${REVISION:+-Drevision=${REVISION}}"
export EXPECTED_VERSION="${REVISION}"

cd -- "${REPO_DIR}"

echo "== [1/3] 全量测试门禁（同一源码快照）=="
# 入口自带 clean：maven-resources-plugin 只做拷贝、不清理上一次构建的产物，若此前跑过
# `-Pdev` 构建，`src/dev/resources` 的 dev/local 配置与 devseed 会残留在 target/classes
# 并被随后不带 clean 的 prod 打包带进正式 Boot Jar（Phase 6B 补证实测：门禁负向清单
# application-dev.yml / application-local.yml / devseed 三项因此为 1）。入口从干净输出开始，
# 保证无论本地先前构建过什么 profile，正式制品的输入都只来自当前源码快照的默认/profile 资源。
"${MVN_BIN}" "${mvn_flags[@]}" ${revision_args[@]+"${revision_args[@]}"} -B clean test

echo
echo "== [2/3] prod profile 打包（正式生产 Boot Jar）=="
"${MVN_BIN}" "${mvn_flags[@]}" ${revision_args[@]+"${revision_args[@]}"} -B -Pprod package -DskipTests

JAR="${REPO_DIR}/sw-bootstrap/target/bootstrap.jar"

echo
echo "== [3/3] 制品门禁 =="
bash "${SCRIPT_DIR}/check-prod-artifact.sh" "${JAR}"

echo
echo "== 生产构建摘要 =="
echo "命令      : MVN_FLAGS=\"${MVN_FLAGS:-}\" REVISION=\"${REVISION}\" scripts/build-prod.sh"
echo "生效版本  : ${REVISION:-<根 POM 默认 revision>}"
echo "输出路径  : ${JAR}"
echo "字节      : $(wc -c < "${JAR}" | tr -d ' ')"
echo "sha256    : $(shasum -a 256 "${JAR}" | awk '{print $1}')"
