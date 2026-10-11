#!/usr/bin/env bash
# 本机打包 → 同步到服务器 → 重启容器 → 检查后端启动
#
# 用法：
#   ./deploy/docker/deploy.sh                 # 全部（后端 + C 端 + 管理端 + 手机工作台）
#   ./deploy/docker/deploy.sh backend         # 只发后端
#   ./deploy/docker/deploy.sh user admin staff # 只发前端
#   ./deploy/docker/deploy.sh config          # 只同步 compose / nginx 配置
#   ./deploy/docker/deploy.sh --rollback      # 回滚到上一次发布
#   加 -y 跳过"有未提交改动"的确认
#
# 服务器地址：环境变量 SERVER，或 deploy/docker/deploy.local 中的 SERVER=root@<IP>
# 备案号：VITE_ICP_NO / VITE_PSB_NO 环境变量，或写在 deploy.local 中
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RELEASE="$SCRIPT_DIR/release"
REMOTE_DIR="${REMOTE_DIR:-/opt/healix}"

# shellcheck disable=SC1091
[[ -f "$SCRIPT_DIR/deploy.local" ]] && set -a && source "$SCRIPT_DIR/deploy.local" && set +a
: "${SERVER:?请设置 SERVER，例如在 deploy/docker/deploy.local 写入 SERVER=root@1.2.3.4}"

log() { printf '\033[36m>> %s\033[0m\n' "$*"; }
die() { printf '\033[31m!! %s\033[0m\n' "$*" >&2; exit 1; }

TARGETS=()
ASSUME_YES=0
ROLLBACK=0
for arg in "$@"; do
  case "$arg" in
    backend|user|admin|staff|config) TARGETS+=("$arg") ;;
    all) TARGETS=(backend user admin staff) ;;
    -y|--yes) ASSUME_YES=1 ;;
    --rollback) ROLLBACK=1 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) die "未知参数：$arg（可用：backend user admin staff config all --rollback -y）" ;;
  esac
done
[[ ${#TARGETS[@]} -eq 0 ]] && TARGETS=(backend user admin staff)
has() { [[ " ${TARGETS[*]} " == *" $1 "* ]]; }

wait_backend() {
  log "等待后端启动"
  ssh "$SERVER" "cd $REMOTE_DIR && since=\$(date -u +%Y-%m-%dT%H:%M:%SZ) && sudo docker compose restart backend >/dev/null 2>&1 && \
    for i in \$(seq 1 60); do \
      out=\$(sudo docker logs --since \$since healix-backend 2>&1); \
      echo \"\$out\" | grep -q 'Started HealixApplication' && { echo '后端启动成功'; exit 0; }; \
      echo \"\$out\" | grep -q 'Application run failed' && { echo \"\$out\" | grep -E 'Caused by|ERROR' | tail -5; exit 1; }; \
      sleep 3; \
    done; echo '后端 3 分钟内未完成启动'; exit 1" \
    || die "后端启动失败。查看日志：ssh $SERVER 'cd $REMOTE_DIR && sudo docker compose logs --tail 200 backend'；回滚：$0 --rollback"
}

if [[ $ROLLBACK -eq 1 ]]; then
  log "回滚到上一次发布"
  ssh "$SERVER" "cd $REMOTE_DIR && test -d release.prev || { echo '服务器上没有可回滚的版本'; exit 1; }; \
    cat release.prev/VERSION 2>/dev/null; \
    rsync -a --delete release.prev/ release/ && sudo docker compose restart nginx >/dev/null 2>&1"
  wait_backend
  log "回滚完成"
  exit 0
fi

# ---- 发布前检查 ----
cd "$ROOT"
COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
DIRTY=""
if [[ -n "$(git status --porcelain 2>/dev/null)" ]]; then
  DIRTY="-dirty"
  echo "当前有未提交的改动（会一起发布）："
  git status --short | head -20
  if [[ $ASSUME_YES -ne 1 ]]; then
    read -r -p "继续发布？[y/N] " ans
    [[ "$ans" =~ ^[Yy]$ ]] || die "已取消"
  fi
fi
log "发布内容：${TARGETS[*]}  版本：${COMMIT}${DIRTY}  目标：$SERVER"

# ---- 本机构建 ----
npm_build() {
  local dir="$1"
  (
    cd "$ROOT/$dir"
    if [[ ! -d node_modules || package-lock.json -nt node_modules/.package-lock.json ]]; then
      npm ci --silent
    fi
    npm run build >/tmp/healix-build-"$dir".log 2>&1 || { tail -30 /tmp/healix-build-"$dir".log; exit 1; }
  ) || die "$dir 构建失败"
}

mkdir -p "$RELEASE/www"
if has backend; then
  log "打包后端"
  export JAVA_HOME="${JAVA_HOME_21:-$(/usr/libexec/java_home -v 21)}"
  mvn -q -f "$ROOT/backend/pom.xml" -pl health-app -am -DskipTests package || die "后端打包失败"
  cp "$(ls "$ROOT"/backend/health-app/target/health-app-*.jar | head -1)" "$RELEASE/healix.jar"
fi
if has user; then
  log "构建 C 端"
  npm_build frontend-user
  rm -rf "$RELEASE/www/user" && cp -R "$ROOT/frontend-user/dist" "$RELEASE/www/user"
fi
if has admin; then
  log "构建管理端"
  npm_build frontend-admin
  rm -rf "$RELEASE/www/admin" && cp -R "$ROOT/frontend-admin/dist" "$RELEASE/www/admin"
fi
if has staff; then
  log "构建手机工作台"
  npm_build frontend-staff
  rm -rf "$RELEASE/www/staff" && cp -R "$ROOT/frontend-staff/dist" "$RELEASE/www/staff"
fi
printf '%s%s %s %s\n' "$COMMIT" "$DIRTY" "$(date '+%F %T')" "${TARGETS[*]}" > "$RELEASE/VERSION"

# ---- 同步 ----
log "备份服务器当前版本"
ssh "$SERVER" "mkdir -p $REMOTE_DIR/release && cd $REMOTE_DIR && rsync -a --delete release/ release.prev/"

log "同步配置"
rsync -az \
  --exclude '.env' --exclude 'data/' --exclude 'logs/' --exclude 'certs/' \
  --exclude 'release/' --exclude 'release.prev/' --exclude 'deploy.local' \
  "$SCRIPT_DIR/" "$SERVER:$REMOTE_DIR/"

log "同步构建产物"
has backend && rsync -az "$RELEASE/healix.jar" "$SERVER:$REMOTE_DIR/release/healix.jar"
has user    && rsync -az --delete "$RELEASE/www/user/"  "$SERVER:$REMOTE_DIR/release/www/user/"
has admin   && rsync -az --delete "$RELEASE/www/admin/" "$SERVER:$REMOTE_DIR/release/www/admin/"
has staff   && rsync -az --delete "$RELEASE/www/staff/" "$SERVER:$REMOTE_DIR/release/www/staff/"
rsync -az "$RELEASE/VERSION" "$SERVER:$REMOTE_DIR/release/VERSION"

# ---- 重启 ----
log "更新容器"
ssh "$SERVER" "cd $REMOTE_DIR && test -f .env || { echo '服务器缺少 $REMOTE_DIR/.env'; exit 1; }; \
  sudo docker compose up -d --remove-orphans >/dev/null 2>&1 && sudo docker compose restart nginx >/dev/null 2>&1"
if has backend || has config; then
  wait_backend
fi

log "发布完成：$(cat "$RELEASE/VERSION")"
