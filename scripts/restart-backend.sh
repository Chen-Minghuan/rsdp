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

# 读取 .env 前，先保存关键环境变量的当前值（允许用户通过系统环境变量覆盖 .env 占位符）
REQUIRED_VARS=("RSDP_ENCRYPTION_KEY" "RSDP_JWT_SECRET" "DASHSCOPE_API_KEY")
declare -A ORIGINAL_ENV
for var in "${REQUIRED_VARS[@]}"; do
    ORIGINAL_ENV[$var]="${!var:-}"
done

# 读取 .env 文件中的变量（兼容 Windows CRLF）
set -a
# shellcheck source=/dev/null
source <(sed 's/\r$//' "$ENV_FILE")
set +a

# 占位符集合（支持 .env.example 中的 <CHANGE_ME> 和 application.yml 里的 your-api-key-here）
is_placeholder() {
    case "$1" in
        ""|"<CHANGE_ME>"|"your-api-key-here"|"YOUR_API_KEY_HERE"|"your-api-key")
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

# 如果 .env 把某个关键变量写成了占位符，但系统环境变量已经提供了真实值，则恢复系统值
for var in "${REQUIRED_VARS[@]}"; do
    value="${!var:-}"
    if is_placeholder "$value" && [ -n "${ORIGINAL_ENV[$var]:-}" ]; then
        export "$var=${ORIGINAL_ENV[$var]}"
    fi
done

export RSDP_JWT_COOKIE_SECURE=false
export DB_USERNAME="${POSTGRES_USER:-rsdp}"
export DB_PASSWORD="${POSTGRES_PASSWORD:-rsdp}"

for var in "${REQUIRED_VARS[@]}"; do
    value="${!var:-}"
    # 去除首尾空白
    value="$(echo "$value" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    export "$var=$value"
    if is_placeholder "$value"; then
        echo "错误：环境变量 $var 未设置或为默认值"
        echo "请在 deploy/.env 中填写，或在启动前通过系统环境变量导出真实值"
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
