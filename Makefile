.PHONY: help infra init-db seed seed-dev dev prod test clean lint build backend frontend

POSTGRES_USER ?= rsdp
POSTGRES_PASSWORD ?= rsdp

help: ## 显示可用命令
	@echo "RSDP 可用命令："
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2}'

infra: ## 启动基础设施（PostgreSQL + ChromaDB + Redis + MinIO），不含后端/前端/AI
	docker compose -f deploy/docker-compose.yml up -d postgres chromadb redis minio

infra-ai: ## 启动包含 Ollama 的基础设施（需要 NVIDIA GPU）
	docker compose -f deploy/docker-compose.yml up -d postgres ollama chromadb redis minio

init-db: ## 初始化 PostgreSQL 数据库（需先启动 postgres；schema/ 基线 + 字典种子按字母序逐文件执行）
	@for f in $$(docker exec rsdp-postgres ls /docker-entrypoint-initdb.d/*.sql); do \
		echo "执行 $$f"; \
		docker exec -i rsdp-postgres psql -U $(POSTGRES_USER) -d rsdp -v ON_ERROR_STOP=1 -f "$$f" || exit 1; \
	done
	@echo "数据库初始化完成"

seed: ## 导入 PostgreSQL 字典种子数据
	@PGPASSWORD=$(POSTGRES_PASSWORD) psql -h localhost -p 5432 -U $(POSTGRES_USER) -d rsdp -f database/schema/zz_seed.sql
	@echo "字典种子数据导入完成"

seed-style: ## 导入风格数据库案例/元素/公式种子数据
	@PGPASSWORD=$(POSTGRES_PASSWORD) psql -h localhost -p 5432 -U $(POSTGRES_USER) -d rsdp -f database/seed_style_knowledge.sql
	@echo "风格数据库种子数据导入完成"

seed-dev: ## 导入开发/演示种子（弱口令测试账号 + 演示工厂/产品；仅开发环境，绝不进生产；演示图片需另跑 scripts/seed-demo-data.sh）
	@PGPASSWORD=$(POSTGRES_PASSWORD) psql -h localhost -p 5432 -U $(POSTGRES_USER) -d rsdp -f database/seed_dev_data.sql
	@echo "开发/演示种子数据导入完成"

dev: ## 本地开发：启动基础设施 + 数据库（默认不含 Ollama）
	make infra
	@echo "等待 PostgreSQL 就绪..."
	@for i in 1 2 3 4 5 6 7 8 9 10; do \
		docker exec rsdp-postgres pg_isready -U $(POSTGRES_USER) -d rsdp >/dev/null 2>&1 && break; \
		sleep 1; \
	done
	make init-db
	make seed
	make seed-dev

prod: ## 生产部署：完整 Docker Compose 启动（需先复制 deploy/.env.example 为 deploy/.env）
	docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d

build: ## 构建前后端
	cd server && mvn clean package -DskipTests
	cd web && pnpm install && pnpm build

backend: ## 启动后端（需先启动 infra）
	cd server && mvn spring-boot:run -Dspring-boot.run.profiles=dev

frontend: ## 启动前端开发服务器
	cd web && pnpm install && pnpm dev

test: ## 运行全部测试
	cd server && mvn test
	cd web && pnpm install && pnpm type-check && pnpm lint
	cd web && pnpm test

lint: ## 代码检查
	cd server && mvn compile -DskipTests
	cd web && pnpm lint

clean: ## 清理容器、构建产物
	docker compose -f deploy/docker-compose.yml down -v
	cd server && mvn clean
	cd web && rm -rf dist node_modules
