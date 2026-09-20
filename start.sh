#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="${SCRIPT_DIR}/logs"
LOG_FILE="${LOG_DIR}/server.log"
PID_FILE="${SCRIPT_DIR}/server.pid"
APP_JAR="${APP_JAR:-${SCRIPT_DIR}/bootstrap.jar}"
JAVA_BIN="${JAVA_BIN:-java}"

if [[ -f "${PID_FILE}" ]]; then
    existing_pid="$(<"${PID_FILE}")"
    if [[ "${existing_pid}" =~ ^[0-9]+$ ]] && kill -0 "${existing_pid}" 2>/dev/null; then
        echo "服务已在运行，PID=${existing_pid}"
        exit 0
    fi
    rm -f -- "${PID_FILE}"
fi

if [[ ! -f "${APP_JAR}" ]]; then
    echo "未找到启动包：${APP_JAR}" >&2
    echo "请先构建 sw-bootstrap，或通过 APP_JAR 指定 jar 路径。" >&2
    exit 1
fi

if ! command -v "${JAVA_BIN}" >/dev/null 2>&1; then
    echo "未找到 Java 命令：${JAVA_BIN}" >&2
    exit 1
fi

mkdir -p -- "${LOG_DIR}"
touch -- "${LOG_FILE}"

java_opts=()
if [[ -n "${JAVA_OPTS:-}" ]]; then
    read -r -a java_opts <<< "${JAVA_OPTS}"
fi

nohup "${JAVA_BIN}" "${java_opts[@]}" -jar "${APP_JAR}" "$@" >>"${LOG_FILE}" 2>&1 &
server_pid=$!
printf '%s\n' "${server_pid}" >"${PID_FILE}"

sleep 2
if ! kill -0 "${server_pid}" 2>/dev/null; then
    rm -f -- "${PID_FILE}"
    echo "服务启动失败，请查看日志：${LOG_FILE}" >&2
    exit 1
fi

echo "服务启动成功，PID=${server_pid}"
echo "日志文件：${LOG_FILE}"
