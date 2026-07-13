# RSDP - 家具产品数字化管理平台

> RSDP（Retail/Sourcing Data Platform）是一个基于多模态 AI 的家具产品数据录入与分类平台。
> 核心目标：员工上传产品图片，AI 自动识别款式、风格、六维标签、颜色、材质，并判定是否与已有款式重复。

## 项目架构

```
┌─────────────┐      ┌─────────────┐      ┌─────────────────┐
│   Vue 3     │ ───▶ │  Nginx      │ ───▶ │   SpringBoot    │
│   前端页面   │      │  反向代理    │      │   业务后端 :8081  │
└─────────────┘      └─────────────┘      └────────┬────────┘
                                                    │
        ┌───────────────────┬───────────────────────┼───────────────────────┐
        ▼                   ▼                       ▼                       ▼
  PostgreSQL          ChromaDB                  MinIO                   Redis
   (元数据)             (向量索引)                (文件存储)               (缓存/任务)
        │                   │
        │    ┌──────────────┘
        │    │
        ▼    ▼
   AI 推理服务
   - MVP：DashScope qwen3-vl-plus
   - 目标：本地 Ollama
```

## 技术栈

| 层 | 技术 |
|:---|:-----|
| 前端 | Vue 3 + TypeScript + Vite + Naive UI + Pinia |
| 后端 | SpringBoot 3.4 + Java 21 + MyBatis-Plus |
| 数据库 | PostgreSQL 16+ |
| 向量库 | ChromaDB |
| AI 推理 | DashScope `qwen3-vl-plus`（MVP）/ Ollama（目标） |
| 文件存储 | MinIO（生产）/ 本地磁盘（开发） |
| 缓存 | Redis |
| 部署 | Docker Compose |

## 项目目录

```
.
├── AGENTS.md                  # 项目级指令（AI 宪法）
├── .ai-rules                  # AI 协作补充规则
├── README.md                  # 本文件
├── Makefile                   # 统一命令入口
├── KimiCode-大型项目协作指南.md # Kimi Code 大型项目协作指南
├── server/                    # SpringBoot 后端服务
├── web/                       # Vue3 前端应用
├── deploy/                    # Docker Compose / Nginx 部署配置
├── database/                  # PostgreSQL 脚本
├── docs/                      # 项目文档（按主题编号）
│   ├── 01-requirements/       # 需求文档
│   ├── 02-architecture/       # 架构 / 数据库 / API 设计
│   ├── 03-guides/             # 开发指南
│   ├── 04-decisions/          # 决策记录（ADR）
│   ├── 05-status/             # 当前进度 / 待办事项
│   ├── 06-reference/          # 业务规则 / 双层编码体系
│   └── 07-issues/             # 问题排障日志
├── scripts/                   # 常用脚本（setup / test / backup）
└── ops/                       # 运维脚本
```

## 快速开始

### 环境要求

- JDK 21 LTS
- Node.js 20+ + pnpm
- Maven 3.9+
- Docker Desktop（可选，用于 PostgreSQL / Ollama / ChromaDB / Redis / MinIO）
- NVIDIA RTX 3060/4060 或更高（推荐，用于本地 AI 推理加速）

### 方式一：生产/全服务部署（Docker Compose）

适合完整体验前后端 + Nginx + 所有基础设施。

```bash
# 1. 复制并填写环境变量（所有 <CHANGE_ME> 必须替换）
cp deploy/.env.example deploy/.env
# 编辑 deploy/.env：DASHSCOPE_API_KEY、RSDP_JWT_SECRET、RSDP_ENCRYPTION_KEY、各密码等

# 2. 全量启动（含 Nginx、后端、前端、PostgreSQL、ChromaDB、Redis、MinIO、Ollama）
cd deploy && docker compose up -d
```

首次启动时，PostgreSQL 会自动执行 `database/V1__init_db.sql`、`database/V1__seed_data.sql` 和 `database/V2__factory_module.sql` 完成数据库初始化。

访问：http://localhost（Nginx 会强转 HTTPS，若使用自签名证书请忽略浏览器安全警告）

> **注意**：`docker-compose.yml` 中 Ollama 默认配置了 NVIDIA GPU。Windows 无 GPU 或不想使用本地 Ollama 时，请删除/注释 Ollama 服务的 `deploy.resources` 段，或改用下方的本地开发方式（MVP 默认使用 DashScope，可不启动 Ollama）。

### 方式二：本地开发（推荐）

适合日常开发调试，前后端在宿主机运行，数据库等基础设施用 Docker。

```bash
# 1. 复制并填写环境变量
cp deploy/.env.example deploy/.env
# 编辑 deploy/.env：至少填写 DASHSCOPE_API_KEY、RSDP_JWT_SECRET、RSDP_ENCRYPTION_KEY、POSTGRES_PASSWORD

# 2. 启动基础设施 + 初始化数据库（默认不含 Ollama）
make dev

# 3. 一键启动前后端（任选其一）
# 3a. 使用脚本（推荐，自动处理 Cookie Secure、DB 密码等本地细节）
scripts/start-local.sh        # Git Bash / WSL / macOS / Linux
scripts/start-local.bat       # Windows CMD

# 3b. 手动启动
# 后端
cd server && mvn spring-boot:run -Dspring-boot.run.profiles=dev
# 前端
cd web && pnpm install && pnpm dev
```

访问：http://localhost:5173

> 若切换到本地 Ollama，先启动 AI 基础设施：
> ```bash
> make infra-ai
> docker exec -it rsdp-ollama ollama pull qwen2.5-vl:7b
> docker exec -it rsdp-ollama ollama pull nomic-embed-text
> ```

## 常用命令

| 命令 | 说明 |
|:---|:---|
| `make help` | 显示所有可用命令 |
| `make infra` | 启动基础设施容器（PostgreSQL + ChromaDB + Redis + MinIO，不含 Ollama） |
| `make infra-ai` | 启动包含 Ollama 的基础设施（需要 NVIDIA GPU） |
| `make init-db` | 初始化数据库 |
| `make seed` | 导入种子数据 |
| `make dev` | 启动基础设施 + 初始化数据库（默认不含 Ollama） |
| `make backend` | 启动后端 |
| `make frontend` | 启动前端开发服务器 |
| `make test` | 运行全量测试 |
| `make build` | 构建前后端 |
| `make clean` | 清理容器与构建产物 |

## 核心功能

- **多模态数据录入**：上传产品白底图，AI 自动识别
- **双层编码体系**：RSPU（款式概念）+ RSKU（工厂供应）
- **AI 识别分类**：风格、六维标签、颜色、场景、OCR
- **同款判定**：元数据预筛 → 向量精排 → LLM 终审
- **人工复核**：低置信度结果强制人工确认
- **工厂报价管理**：多厂比价、价格加密存储

## 文档

详见 `docs/` 目录：

- [整体架构](docs/02-architecture/01-整体架构.md)
- [数据库设计](docs/02-architecture/02-数据库设计.md)
- [数据库实现说明](docs/02-architecture/03-数据库实现说明.md)
- [API 设计](docs/02-architecture/04-API设计.md)
- [本地开发环境搭建](docs/03-guides/01-本地开发环境搭建.md)
- [编码规范](docs/03-guides/02-编码规范.md)
- [测试指南](docs/03-guides/03-测试指南.md)
- [当前进度](docs/05-status/当前进度.md)
- [待办事项](docs/05-status/待办事项.md)
- [业务规则说明](docs/06-reference/01-业务规则说明.md)
- [双层编码体系](docs/06-reference/02-双层编码体系.md)

## 开发路线

1. ✅ 项目骨架 + 数据库设计
2. 🔄 图片上传 + AI 识别（DashScope → Ollama）
3. ⏳ 人工复核 + RSPU/RSKU 录入
4. ⏳ 同款判定 + 向量检索
5. ⏳ 工厂报价 + 比价
6. ⏳ 部署文档 + 生产优化

## 贡献

欢迎提交 Issue 和 Pull Request。
