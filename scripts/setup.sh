#!/usr/bin/env bash
set -e

echo "=== RSDP 开发环境一键搭建（PostgreSQL）==="

# 启动基础设施
echo "启动基础设施（PostgreSQL + Ollama + ChromaDB + Redis + MinIO）..."
docker compose -f deploy/docker-compose.yml up -d postgres ollama chromadb redis minio

echo "等待 PostgreSQL 就绪..."
sleep 5

# 初始化数据库
echo "初始化 PostgreSQL 数据库..."
docker exec -i rsdp-postgres psql -U "${POSTGRES_USER:-rsdp}" -d rsdp -f /docker-entrypoint-initdb.d/01-init.sql

# 导入种子数据
echo "导入种子数据..."
docker exec -i rsdp-postgres psql -U "${POSTGRES_USER:-rsdp}" -d rsdp -f /docker-entrypoint-initdb.d/02-seed.sql

echo "=== 环境搭建完成 ==="
echo "请确认已设置 DASHSCOPE_API_KEY 环境变量（如使用 DashScope）。"
echo "请手动启动后端：cd server && mvn spring-boot:run -Dspring-boot.run.profiles=dev"
echo "请手动启动前端：cd web && pnpm install && pnpm dev"
