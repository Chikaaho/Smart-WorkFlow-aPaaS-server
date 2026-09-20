#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PID_FILE="${SCRIPT_DIR}/server.pid"
APP_JAR="${APP_JAR:-${SCRIPT_DIR}/sw-bootstrap/target/sw-bootstrap-1.0.0-SNAPSHOT.jar}"
STOP_TIMEOUT="${STOP_TIMEOUT:-30}"

if [[ ! "${STOP_TIMEOUT}" =~ ^[0-9]+$ ]] || (( STOP_TIMEOUT < 1 )); then
    echo "STOP_TIMEOUT 必须是大于 0 的整数。" >&2
    exit 1
fi

if [[ ! -f "${PID_FILE}" ]]; then
    echo "服务未运行：未找到 PID 文件。"
    exit 0
fi

server_pid="$(<"${PID_FILE}")"
if [[ ! "${server_pid}" =~ ^[0-9]+$ ]]; then
    echo "PID 文件内容无效：${PID_FILE}" >&2
    exit 1
fi

if ! kill -0 "${server_pid}" 2>/dev/null; then
    rm -f -- "${PID_FILE}"
    echo "服务未运行，已清理失效 PID 文件。"
    exit 0
fi

if [[ -r "/proc/${server_pid}/cmdline" ]]; then
    process_command="$(tr '\0' ' ' < "/proc/${server_pid}/cmdline")"
    if [[ "${process_command}" != *"${APP_JAR}"* ]]; then
        echo "拒绝停止：PID ${server_pid} 对应的进程不是目标服务。" >&2
        echo "实际命令：${process_command}" >&2
        exit 1
    fi
fi

kill "${server_pid}"

for ((elapsed = 0; elapsed < STOP_TIMEOUT; elapsed++)); do
    if ! kill -0 "${server_pid}" 2>/dev/null; then
        rm -f -- "${PID_FILE}"
        echo "服务已停止，PID=${server_pid}"
        exit 0
    fi
    sleep 1
done

echo "服务在 ${STOP_TIMEOUT} 秒内未退出，发送 SIGKILL。" >&2
kill -KILL "${server_pid}" 2>/dev/null || true

for _ in {1..5}; do
    if ! kill -0 "${server_pid}" 2>/dev/null; then
        rm -f -- "${PID_FILE}"
        echo "服务已强制停止，PID=${server_pid}"
        exit 0
    fi
    sleep 1
done

echo "无法停止服务，PID=${server_pid}" >&2
exit 1
