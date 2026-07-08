#!/usr/bin/env bash
# RSDP 本地开发环境后端单独重启脚本（Git Bash / WSL / Linux / macOS）
# 只重启后端，不碰前端 dev server

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/deploy/.env"
LOG_DIR="$PROJECT_ROOT/logs"
mkdir -p "$LOG_DIR"

if [ ! -f "$ENV_FILE" ]; then
    echo "错误：找不到环境变量文件 $ENV_FILE"
    echo "请先复制 deploy/.env.example 为 deploy/.env 并填写真实密钥"
    exit 1
fi

# 读取 .env 文件中的变量（兼容 Windows CRLF）
set -a
# shellcheck source=/dev/null
source <(sed 's/\r$//' "$ENV_FILE")
set +a

export RSDP_JWT_COOKIE_SECURE=false
export DB_USERNAME="${POSTGRES_USER:-rsdp}"
export DB_PASSWORD="${POSTGRES_PASSWORD:-rsdp}"

REQUIRED_VARS=("RSDP_ENCRYPTION_KEY" "RSDP_JWT_SECRET")
for var in "${REQUIRED_VARS[@]}"; do
    value="${!var:-}"
    if [ -z "$value" ] || [ "$value" = "<CHANGE_ME>" ]; then
        echo "错误：环境变量 $var 未设置或为默认值 <CHANGE_ME>"
        exit 1
    fi
done

BACKEND_LOG="$LOG_DIR/backend.log"

echo "[1/2] 停止现有后端进程..."

stop_backend_by_port() {
  local port="$1"
  case "$(uname -s)" in
    MINGW*|CYGWIN*|MSYS*)
      # Windows Git Bash：通过占用端口的 PID 结束进程
      powershell.exe -Command "try { \$p = Get-Process -Id (Get-NetTCPConnection -LocalPort $port -ErrorAction Stop).OwningProcess; Stop-Process -Id \$p.Id -Force } catch {}" >/dev/null 2>&1 || true
      ;;
    *)
      # Linux / macOS / WSL
      pkill -f "mvn spring-boot:run" || true
      pkill -f "rsdp-server" || true
      ;;
  esac
}

stop_backend_by_port 8081
sleep 2

echo "[2/2] 启动后端 Spring Boot（端口 8081）..."
cd "$PROJECT_ROOT/server"
nohup mvn spring-boot:run -Dspring-boot.run.profiles=dev > "$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!
echo "后端 PID: $BACKEND_PID"
echo "后端日志: $BACKEND_LOG"
echo "后端启动中，约需 10~20 秒..."
