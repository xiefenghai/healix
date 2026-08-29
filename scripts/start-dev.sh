#!/usr/bin/env bash
# 一键启动本地开发：后端 (8080) + 管理端前端 (5174)，释放旧端口后重启，并打开浏览器
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKEND_DIR="$ROOT/backend"
ADMIN_DIR="$ROOT/frontend-admin"

BACKEND_PORT=8080
ADMIN_PORT=5174
ADMIN_URL="http://localhost:${ADMIN_PORT}"

BACKEND_PID=""
ADMIN_PID=""

# Prefer JDK 21 for Spring Boot
if [[ -z "${JAVA_HOME:-}" ]] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21\.'; then
  if [[ -d /Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ]]; then
    export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
  elif command -v /usr/libexec/java_home >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  fi
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"

# Load repo .env if present
if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env"
  set +a
fi

kill_port() {
  local port="$1"
  local pids
  pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -z "$pids" ]]; then
    echo "端口 ${port} 空闲"
    return 0
  fi

  echo "检测到端口 ${port} 仍在占用 (PID: ${pids//$'\n'/ })，正在关闭..."
  # shellcheck disable=SC2086
  kill $pids 2>/dev/null || true

  local i=0
  while (( i < 20 )); do
    pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
    [[ -z "$pids" ]] && break
    sleep 0.25
    ((++i))
  done

  pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -n "$pids" ]]; then
    echo "优雅关闭超时，强制结束端口 ${port}..."
    # shellcheck disable=SC2086
    kill -9 $pids 2>/dev/null || true
    sleep 0.3
  fi

  if lsof -tiTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "错误: 无法释放端口 ${port}" >&2
    exit 1
  fi
  echo "端口 ${port} 已释放"
}

wait_port() {
  local port="$1"
  local name="$2"
  local max_attempts="${3:-120}"
  local i=0
  while (( i < max_attempts )); do
    if nc -z 127.0.0.1 "$port" 2>/dev/null; then
      echo "${name} 已就绪 (端口 ${port})"
      return 0
    fi
    sleep 0.5
    ((++i))
  done
  echo "错误: 等待 ${name} 就绪超时 (端口 ${port})" >&2
  return 1
}

cleanup() {
  echo ""
  echo "正在停止子进程..."
  [[ -n "$ADMIN_PID" ]] && kill "$ADMIN_PID" 2>/dev/null || true
  [[ -n "$BACKEND_PID" ]] && kill "$BACKEND_PID" 2>/dev/null || true
  # 再清一次监听，避免残留
  local port pids
  for port in "$ADMIN_PORT" "$BACKEND_PORT"; do
    pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
    if [[ -n "$pids" ]]; then
      # shellcheck disable=SC2086
      kill $pids 2>/dev/null || true
    fi
  done
}
trap cleanup EXIT INT TERM

echo "======== 释放端口 ========"
kill_port "$BACKEND_PORT"
kill_port "$ADMIN_PORT"

echo ""
echo "======== 启动后端 ========"
if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "错误: 未找到 JDK 21，请设置 JAVA_HOME" >&2
  exit 1
fi
echo "JAVA_HOME=${JAVA_HOME}"
(
  cd "$BACKEND_DIR"
  exec mvn -pl health-app -am spring-boot:run -q
) &
BACKEND_PID=$!

echo ""
echo "======== 启动管理端前端 ========"
(
  cd "$ADMIN_DIR"
  if [[ ! -d node_modules ]]; then
    echo "未找到 node_modules，正在安装依赖..."
    npm install
  fi
  exec npm run dev -- --host 127.0.0.1 --port "$ADMIN_PORT" --strictPort
) &
ADMIN_PID=$!

echo ""
echo "等待服务就绪..."
wait_port "$BACKEND_PORT" "后端" 180
wait_port "$ADMIN_PORT" "管理端前端" 60

open "$ADMIN_URL"
echo "已在浏览器打开 ${ADMIN_URL}"
echo ""
echo "后端:     http://localhost:${BACKEND_PORT}"
echo "管理端:   ${ADMIN_URL}"
echo "按 Ctrl+C 停止全部进程"
echo ""

# 任一子进程退出则结束（兼容 macOS bash 3.2，无 wait -n）
while kill -0 "$BACKEND_PID" 2>/dev/null && kill -0 "$ADMIN_PID" 2>/dev/null; do
  sleep 1
done
echo "有进程已退出，正在收尾..." >&2
exit 1
