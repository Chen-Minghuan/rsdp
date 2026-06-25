#!/usr/bin/env bash
set -e

echo "=== 运行 RSDP 全量测试 ==="

echo "后端测试..."
cd server
mvn test
cd ..

echo "前端依赖安装..."
cd web
pnpm install

echo "前端类型检查..."
pnpm type-check

echo "前端代码检查..."
pnpm lint

cd ..

echo "=== 全量测试完成 ==="
echo "提示：当前前端尚未配置 Vitest/Playwright，已用 type-check + lint 作为质量 gate。"
