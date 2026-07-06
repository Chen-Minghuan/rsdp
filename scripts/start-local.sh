#!/usr/bin/env bash
# RSDP 本地开发环境一键启动脚本（Git Bash / WSL / Linux / macOS）
# 会在后台启动后端和前端 dev server，并输出日志到文件
#
# 前置条件：
#   1. 复制 deploy/.env.example 为 deploy/.env
#   2. 在 deploy/.env 中填入真实密钥（RSDP_ENCRYPTION_KEY、RSDP_JWT_SECRET 等）
#   3. 运行 scripts/start-local.sh

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

# 本地开发使用 http，必须关闭 Cookie 的 Secure 属性，否则浏览器不会发送 Cookie
export RSDP_JWT_COOKIE_SECURE=false

# 让本地后端使用与 Docker Postgres 相同的账号密码
export DB_USERNAME="${POSTGRES_USER:-rsdp}"
export DB_PASSWORD="${POSTGRES_PASSWORD:-rsdp}"

# 校验关键敏感变量
REQUIRED_VARS=("RSDP_ENCRYPTION_KEY" "RSDP_JWT_SECRET")
for var in "${REQUIRED_VARS[@]}"; do
    value="${!var:-}"
    if [ -z "$value" ] || [ "$value" = "<CHANGE_ME>" ]; then
        echo "错误：环境变量 $var 未设置或为默认值 <CHANGE_ME>"
        echo "请在 $ENV_FILE 中设置强随机值后再启动"
        exit 1
    fi
done

# 若配置了 DASHSCOPE_API_KEY，则不允许使用占位符
if [ -n "${DASHSCOPE_API_KEY:-}" ] && [ "$DASHSCOPE_API_KEY" = "<CHANGE_ME>" ]; then
    echo "错误：环境变量 DASHSCOPE_API_KEY 不能为默认值 <CHANGE_ME>"
    echo "请在 $ENV_FILE 中填入真实的 DashScope API Key，或将其置空"
    exit 1
fi

BACKEND_LOG="$LOG_DIR/backend.log"
FRONTEND_LOG="$LOG_DIR/frontend.log"

echo "=========================================="
echo " RSDP 本地开发环境启动"
echo "=========================================="
echo ""
echo "项目根目录: $PROJECT_ROOT"
echo "环境文件: $ENV_FILE"
echo ""

echo "[1/2] 启动后端 Spring Boot（端口 8081）..."
cd "$PROJECT_ROOT/server"
nohup mvn spring-boot:run -Dspring-boot.run.profiles=dev > "$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!
echo "后端 PID: $BACKEND_PID"

echo "[2/2] 等待 8 秒后启动前端 Vite（端口 5173）..."
sleep 8

cd "$PROJECT_ROOT/web"
nohup pnpm dev > "$FRONTEND_LOG" 2>&1 &
FRONTEND_PID=$!
echo "前端 PID: $FRONTEND_PID"

echo ""
echo "=========================================="
echo " 启动完成"
echo "=========================================="
echo "后端地址: http://localhost:8081"
echo "前端地址: http://localhost:5173"
echo "后端日志: $BACKEND_LOG"
echo "前端日志: $FRONTEND_LOG"
echo ""
echo "停止服务请运行: scripts/stop-local.sh"
echo ""
