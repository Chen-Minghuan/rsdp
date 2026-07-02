#!/usr/bin/env bash
# RSDP 本地开发环境一键停止脚本（Git Bash / WSL / Linux / macOS）
# 停止由 start-local.sh 启动的后端和前端进程

echo "正在停止 RSDP 后端（Java / Maven）..."
pkill -f "mvn spring-boot:run" || true
pkill -f "rsdp-server" || true

echo "正在停止 RSDP 前端（Vite）..."
pkill -f "pnpm dev" || true
pkill -f "vite" || true

echo ""
echo "前后端进程已尝试停止。"
