#!/usr/bin/env bash
set -e

echo "=== RSDP 开发环境一键搭建（PostgreSQL）==="

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# 复用 Makefile 的单一开发环境入口：全新数据卷由 PostgreSQL entrypoint
# 自动执行 schema/，已有数据卷则由应用启动时的 Flyway 负责结构升级。
make -C "$PROJECT_ROOT" dev

if [ "${RSDP_WITH_OLLAMA:-0}" = "1" ]; then
    echo "启动 Ollama（需要 NVIDIA GPU）..."
    docker compose -f "$PROJECT_ROOT/deploy/docker-compose.yml" up -d ollama
else
    echo "提示：如需本地 AI 推理（Ollama，需 NVIDIA GPU），设置 RSDP_WITH_OLLAMA=1 后重跑。"
fi

echo "=== 环境搭建完成 ==="
echo "请确认已设置 DASHSCOPE_API_KEY 环境变量（如使用 DashScope）。"
echo "请手动启动后端：cd server && mvn spring-boot:run -Dspring-boot.run.profiles=dev"
echo "请手动启动前端：cd web && pnpm install && pnpm dev"
