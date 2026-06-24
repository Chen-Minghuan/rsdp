# RSDP - 家具产品数字化管理平台

> RSDP（Room/Retail Scene Data Platform）是一个基于多模态 AI 的家具产品数据录入与分类平台。
> 核心目标：员工上传产品图片，AI 自动识别款式、风格、六维标签、颜色、材质，并判定是否与已有款式重复。

## 项目架构

```
┌─────────────┐      ┌─────────────┐      ┌─────────────────┐
│   Vue 3     │ ───▶ │  Nginx      │ ───▶ │   SpringBoot    │
│   前端页面   │      │  反向代理    │      │   业务后端 :8080  │
└─────────────┘      └─────────────┘      └────────┬────────┘
                                                    │
        ┌───────────────────┬───────────────────────┼───────────────────────┐
        ▼                   ▼                       ▼                       ▼
    SQLite              ChromaDB                  MinIO                   Redis
   (元数据)             (向量索引)                (文件存储)               (缓存/任务)
        │                   │
        │    ┌──────────────┘
        │    │
        ▼    ▼
   Ollama :11434
   /api/embeddings  向量编码
   /api/generate    标签生成 / 同款终审
```

## 技术栈

| 层 | 技术 |
|:---|:-----|
| 前端 | Vue 3 + TypeScript + Vite + Naive UI + Pinia |
| 后端 | SpringBoot 3.4 + Java 21 + MyBatis-Plus |
| 数据库 | SQLite（开发）/ PostgreSQL（生产建议）|
| 向量库 | ChromaDB |
| AI 推理 | Ollama（本地 Qwen 2.5-VL 7B + 嵌入模型）|
| 文件存储 | MinIO（生产）/ 本地磁盘（开发）|
| 缓存 | Redis |
| 部署 | Docker Compose |

## 项目目录

```
.
├── backend/                  # SpringBoot 后端
│   ├── src/main/java/        # Java 源码
│   ├── src/main/resources/   # 配置文件
│   ├── pom.xml               # Maven 配置
│   └── Dockerfile
├── frontend/                 # Vue3 前端
│   ├── src/                  # 源码
│   ├── package.json          # 依赖
│   ├── vite.config.ts
│   └── Dockerfile
├── docker/                   # Docker 部署配置
│   ├── docker-compose.yml
│   └── nginx/nginx.conf
├── scripts/                  # 数据库脚本
│   ├── init_db.sql
│   └── seed_data.sql
├── docs/                     # 项目文档
│   ├── RSDP-技术架构方案.md
│   ├── RSDP-多模态录入数据库设计.md
│   ├── RSDP-数据库设计与项目实现结合说明.md
│   ├── 双层编码体系.md
│   └── 双层编码体系-整合分析报告.md
├── .gitignore
└── README.md
```

## 快速开始

### 环境要求

- JDK 21+
- Node.js 20+ + pnpm
- Ollama
- Docker Desktop（可选，用于 ChromaDB/Redis/MinIO）
- NVIDIA RTX 3060/4060 或更高（推荐，用于加速 AI 推理）

### 1. 克隆仓库

```bash
git clone https://github.com/your-username/rsdp.git
cd rsdp
```

### 2. 初始化数据库

```bash
mkdir -p backend/data
sqlite3 backend/data/rsdp_dev.db < scripts/init_db.sql
sqlite3 backend/data/rsdp_dev.db < scripts/seed_data.sql
```

### 3. 启动 Ollama 并拉取模型

```bash
ollama pull qwen2.5-vl:7b
ollama pull nomic-embed-text
```

### 4. 启动后端

```bash
cd backend
./mvnw spring-boot:run
```

### 5. 启动前端

```bash
cd frontend
pnpm install
pnpm dev
```

访问 http://localhost:5173

## Docker 部署

```bash
cd docker
docker compose up -d
```

## 核心功能

- **多模态数据录入**：上传产品白底图，AI 自动识别
- **双层编码体系**：RSPU（款式概念）+ RSKU（工厂供应）
- **AI 识别分类**：风格、六维标签、颜色、场景、OCR
- **同款判定**：元数据预筛 → 向量精排 → LLM 终审
- **人工复核**：低置信度结果强制人工确认
- **工厂报价管理**：多厂比价、价格加密存储

## 文档

详见 `docs/` 目录：

- [RSDP-技术架构方案.md](docs/RSDP-技术架构方案.md)
- [RSDP-多模态录入数据库设计.md](docs/RSDP-多模态录入数据库设计.md)
- [RSDP-数据库设计与项目实现结合说明.md](docs/RSDP-数据库设计与项目实现结合说明.md)
- [双层编码体系.md](docs/双层编码体系.md)
- [双层编码体系-整合分析报告.md](docs/双层编码体系-整合分析报告.md)

## 开发路线

1. ✅ 项目骨架 + 数据库设计
2. 🔄 图片上传 + AI 识别（Ollama）
3. ⏳ 人工复核 + RSPU/RSKU 录入
4. ⏳ 同款判定 + 向量检索
5. ⏳ 工厂报价 + 比价
6. ⏳ 部署文档 + 生产优化

## 贡献

欢迎提交 Issue 和 Pull Request。
