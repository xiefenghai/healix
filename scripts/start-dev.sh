#!/usr/bin/env bash
# 启动本地 C 端 (5173) + B 端管理端前端 (5174)，释放旧端口后重启，并打开浏览器
# 后端请自行在 IDE / 终端启动（默认 http://localhost:8080）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
USER_DIR="$ROOT/frontend-user"
ADMIN_DIR="$ROOT/frontend-admin"

USER_PORT=5173
ADMIN_PORT=5174
BACKEND_PORT=8080
USER_URL="http://localhost:${USER_PORT}"
ADMIN_URL="http://localhost:${ADMIN_PORT}"
BACKEND_URL="http://localhost:${BACKEND_PORT}"

USER_PID=""
ADMIN_PID=""

# 可选：加载仓库根目录 .env（前端代理等环境变量）
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
  local max_attempts="${3:-60}"
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

check_backend() {
  if nc -z 127.0.0.1 "$BACKEND_PORT" 2>/dev/null; then
    echo "后端已运行: ${BACKEND_URL}"
    return 0
  fi
  echo "提示: 后端未检测到 (${BACKEND_URL})，请自行启动后再访问接口"
  echo "  示例: cd backend && mvn -pl health-app -am spring-boot:run"
  return 0
}

start_frontend() {
  local dir="$1"
  local port="$2"
  local name="$3"
  (
    cd "$dir"
    if [[ ! -d node_modules ]]; then
      echo "未找到 node_modules (${name})，正在安装依赖..."
      npm install
    fi
    exec npm run dev -- --host 127.0.0.1 --port "$port" --strictPort
  )
}

cleanup() {
  echo ""
  echo "正在停止前端..."
  [[ -n "$USER_PID" ]] && kill "$USER_PID" 2>/dev/null || true
  [[ -n "$ADMIN_PID" ]] && kill "$ADMIN_PID" 2>/dev/null || true
  local pids
  for port in "$USER_PORT" "$ADMIN_PORT"; do
    pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
    if [[ -n "$pids" ]]; then
      # shellcheck disable=SC2086
      kill $pids 2>/dev/null || true
    fi
  done
}
trap cleanup EXIT INT TERM

echo "======== 释放前端端口 ========"
kill_port "$USER_PORT"
kill_port "$ADMIN_PORT"

echo ""
echo "======== 检查后端（不启动） ========"
check_backend

echo ""
echo "======== 启动 C 端前端 ========"
start_frontend "$USER_DIR" "$USER_PORT" "C 端" &
USER_PID=$!

echo ""
echo "======== 启动 B 端管理端前端 ========"
start_frontend "$ADMIN_DIR" "$ADMIN_PORT" "管理端" &
ADMIN_PID=$!

echo ""
echo "等待前端就绪..."
wait_port "$USER_PORT" "C 端前端" 60
wait_port "$ADMIN_PORT" "管理端前端" 60

open "$USER_URL"
open "$ADMIN_URL"
echo ""
echo "已在浏览器打开:"
echo "  C 端:   ${USER_URL}"
echo "  管理端: ${ADMIN_URL}"
echo "  后端:   ${BACKEND_URL}（请自行启动）"
echo "按 Ctrl+C 停止前端"
echo ""

while kill -0 "$USER_PID" 2>/dev/null || kill -0 "$ADMIN_PID" 2>/dev/null; do
  sleep 1
done
echo "前端进程已退出" >&2
exit 1
