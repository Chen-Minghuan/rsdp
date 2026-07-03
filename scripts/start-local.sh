#!/usr/bin/env bash
# RSDP 本地开发环境一键启动脚本（Git Bash / WSL / Linux / macOS）
# 会在后台启动后端和前端 dev server，并输出日志到文件

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$PROJECT_ROOT/logs"
mkdir -p "$LOG_DIR"

# 本地开发默认密钥：仅在开发环境使用，生产环境必须通过环境变量注入真实密钥
export RSDP_ENCRYPTION_KEY="${RSDP_ENCRYPTION_KEY:-WYs8rXkOCYazXad8RLS2lP5qmWI6fYPCd0HQ72fXSnY=}"
export RSDP_JWT_SECRET="${RSDP_JWT_SECRET:-g3g6Ryj6ty4Dsw0jm1ImR59dbRAOI98q3qKVf7gz0jU=}"

BACKEND_LOG="$LOG_DIR/backend.log"
FRONTEND_LOG="$LOG_DIR/frontend.log"

echo "=========================================="
echo " RSDP 本地开发环境启动"
echo "=========================================="
echo ""
echo "项目根目录: $PROJECT_ROOT"
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
