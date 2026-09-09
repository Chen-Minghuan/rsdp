# AGENTS.md —— RSDP 项目级指令

> 本文件是 Kimi Code 协作的"项目宪法"。任何 AI Agent 在修改代码前必须先阅读本文件和 `docs/05-status/当前进度.md`。

---

## 一、项目概述

- **项目名称**：RSDP（Retail/Sourcing Data Platform）家具产品数字化管理平台
- **目标**：为家具产品建立"款式（RSPU）+ 供应（RSKU）"双层数字化档案，让 AI Agent 能自动看图识别产品、判定同款、匹配搭配、校验空间。
- **部署目标**：Windows 台式机/服务器本地部署（推荐 NVIDIA RTX 独立显卡），支持后续迁移到云端/私有服务器。

---

## 二、主技术栈

### 后端
- **语言**：Java 21 LTS
- **框架**：Spring Boot 3.4+
- **安全**：Spring Security + JWT（jjwt），接口级权限 + 数据归属隔离
- **ORM**：MyBatis-Plus 3.5+
- **数据库**：PostgreSQL 16
- **向量数据库**：ChromaDB 0.5+（REST API）
- **文件存储**：MinIO 8.5+（生产）/ 本地磁盘（开发）
- **缓存**：Redis 7.x（关闭时以 ConcurrentMapCacheManager 做 JVM 内存回退，见 `config/CacheConfig.java`）
- **AI 推理**：当前 MVP 通过 DashScope `qwen3-vl-plus`（OpenAI 兼容接口）完成视觉识别；后续目标切换为本地 Ollama 托管 Qwen 2.5-VL 7B + nomic-embed-text
- **HTTP 客户端**：Spring RestClient（虚拟线程）
- **Excel**：EasyExcel 3.3+
- **Office 文档**：Apache POI 5.3+（Excel 浮动图片锚点解析、采购合同 docx 生成）
- **PDF**：Apache PDFBox（PDF 导入渲染）
- **API 文档**：SpringDoc OpenAPI + Knife4j 4.5+
- **构建**：Maven 3.9+

### 前端
- **框架**：Vue 3.5+（Composition API + `<script setup lang="ts">`）
- **构建**：Vite 6.x
- **语言**：TypeScript 5.7+（strict: true）
- **UI 组件库**：Naive UI 2.41+
- **状态管理**：Pinia 2.3+
- **路由**：Vue Router 4.5+
- **HTTP**：Axios 1.7+
- **大表格**：VxeTable 4.9+
- **图表**：ECharts 6.x
- **工具库**：@vueuse/core、dayjs
- **测试**：Vitest 2.x + Playwright 1.49+（规划中）
- **包管理**：pnpm 9.x

### 基础设施
- **容器编排**：Docker Compose v2
- **反向代理**：Nginx 1.27-alpine
- **CI/CD**：GitHub Actions（预留）
- **版本控制**：Git（trunk-based：main → develop → feat/xxx）
- **提交规范**：Conventional Commits

---

## 三、目录结构

```
.
├── AGENTS.md                      # 本文件（项目级指令）
├── README.md                      # 项目入口
├── Makefile                       # 统一命令入口（make help 查看全部命令）
├── .gitignore                     # Git 忽略规则
├── .ai-rules                      # AI 协作补充规则（可选但推荐）
├── KimiCode-大型项目协作指南.md   # Kimi Code 大型项目协作指南
├── docs/                          # 文档（按主题分类）
│   ├── 01-requirements/           # 需求文档
│   ├── 02-architecture/           # 架构设计
│   │   ├── 01-整体架构.md
│   │   ├── 02-数据库设计.md
│   │   ├── 03-数据库实现说明.md
│   │   ├── 04-API设计.md
│   │   └── 05-RSDP-数据库完善方案-工厂与导入模块.md
│   ├── 03-guides/                 # 开发指南（环境搭建/编码规范/测试/风格库导入格式）
│   ├── 04-decisions/              # 决策记录（ADR 001-007）
│   ├── 05-status/                 # 项目状态
│   │   ├── 当前进度.md
│   │   └── 待办事项.md
│   ├── 06-reference/              # 参考资料（编码体系、业务规则、权限矩阵、项目文件地图）
│   ├── 07-issues/                 # 问题记录与排障日志
│   ├── 08-roadmap/                # 后续开发计划、RSDP × rooom 整合方案
│   └── FAQ.md
├── server/                        # SpringBoot 业务主后端
│   ├── src/main/java/com/rsdp/   # Java 源码（包结构见 3.1）
│   ├── src/main/resources/        # 配置、Mapper XML
│   ├── src/test/java/com/rsdp/   # 测试
│   ├── pom.xml                    # Maven 配置
│   └── Dockerfile                 # 后端镜像
├── web/                           # Vue 3 前端
│   ├── src/
│   │   ├── api/                   # Axios 接口封装（实例在 client.ts）
│   │   ├── views/                 # 页面（产品/工厂/项目/方案/报价/订单/统计等）
│   │   ├── components/            # 通用组件（PageContainer 等）
│   │   ├── composables/           # 组合式函数（useEcharts、useRequestAbort）
│   │   ├── stores/                # Pinia 状态（user、excelImport、documentImport）
│   │   ├── router/                # 路由
│   │   ├── config/                # 导航分组配置 navigation.ts
│   │   ├── styles/                # 设计 token（tokens.css）
│   │   ├── types/                 # TypeScript 类型定义
│   │   └── utils/                 # 常量/工具
│   ├── public/                    # 静态资源
│   ├── index.html
│   ├── vite.config.ts
│   ├── tsconfig.json
│   ├── package.json
│   └── Dockerfile
├── website/                       # 用户端官网（Nuxt 3 SSR，style-a 暖调家居馆）
│   ├── assets/css/tokens.css      # style-a 设计 token（暖调，与管理端 tokens 不同体系）
│   ├── components/                # PriceText/ProductCard/SiteHeader/SiteFooter 等（原生组件，不用 Naive UI）
│   ├── composables/               # usePublicApi（/api/v1/public/** 封装 + 图片地址拼接）
│   ├── pages/                     # index.vue 首页（11 区块）、ai-match.vue、products/index.vue、products/[id].vue
│   ├── types/                     # 公开接口类型
│   └── nuxt.config.ts             # SSR + runtimeConfig.public.apiBase + Noto Serif SC CDN
├── deploy/                        # 部署配置
│   ├── docker-compose.yml         # 全服务编排
│   ├── .env.example               # 环境变量模板
│   └── nginx/nginx.conf           # 反向代理配置
├── database/                      # 数据库脚本（目录地图与执行手册见 database/README.md）
│   ├── schema/                    # 基线 DDL（唯一结构真相，01→99 按域拆分，编号即执行顺序）
│   ├── ops/reset_db.sql           # 数据库重置脚本（自包含单文件，schema+必需种子镜像）
│   ├── seed_style_knowledge.sql   # 风格知识库种子数据
│   └── seed_dev_data.sql          # 开发/演示种子（弱口令测试账号 + 演示工厂/产品，仅开发环境）
├── data/                          # 运行数据（uploads 上传文件、style-knowledge 风格素材）
├── ops/                           # 运维脚本
│   └── anchor_encode.sh           # 锚点图批量编码（未完成 stub）
└── scripts/                       # 常用脚本
    ├── setup.sh                   # 开发环境一键搭建
    ├── test.sh                    # 运行全部测试
    ├── backup_db.sh               # 数据库备份
    ├── start-local / stop-local / restart-backend（.sh/.bat）  # 本地启停
    ├── seed-demo-data.sh          # 演示数据导入（复制样例图 + 执行 database/seed_dev_data.sql）
    ├── check_entity_db_fields.js  # 实体-数据库字段对账
    ├── clean_test_data.sql        # 测试数据清理
    ├── gen-dev-cert.sh            # 开发证书生成
    ├── generate_six_dim_dict_seed.js  # 六维字典种子生成
    └── generate_style_knowledge_seed.js  # 风格知识种子生成
```

### 3.1 后端包结构（`server/src/main/java/com/rsdp/`）

```
├── controller/   # REST 接口层（薄层，30+ Controller）
├── service/      # 业务逻辑层（含 chroma/ 向量检索、storage/ 文件存储子包）
├── mapper/       # MyBatis-Plus Mapper
├── entity/       # 数据库实体
├── dto/          # 请求/响应 DTO（按 request/ response 分包，另有 excel/ 导入子包）
├── config/       # 配置类（AI、缓存、MinIO、MyBatis、TypeHandler、properties/）
├── security/     # Spring Security + JWT + 数据权限（datascope/）
├── event/        # 领域事件（如 RSPU 删除联动清理）
├── migration/    # 启动期数据迁移（如历史价格加密迁移）
├── common/       # Result / PageResult / 通用枚举
├── exception/    # 全局异常处理
└── util/         # 工具（AES、JWT、Excel/PDF 解析、图片处理）
```

---

## 四、核心领域规则（不可违反）

### 4.1 双层编码体系

```
RSPU（设计原型）：[类别2位]-[风格/定位2位]-[流水3位]-[尺寸1位]
  例：FS-MC-001-M

RSKU（供应单元）：[RSPU ID]-[工厂代码3位]-[材质版本2位]
  例：FS-MC-001-M-A004-PE
```

**铁律**：
- `rspu_master` 绝对不含工厂代码、价格、交期、SKU。
- `rsku_supply` 必须外键关联 `rspu_id`，且自身携带工厂 + 材质 + 价格。

**主键与业务编码分离（不可违反）**：
- `rspu_master.rspu_id` 与 `rsku_supply.rsku_id` 使用 UUID 主键（如 `RSPU-XXXX`、`RSKU-XXXX`），与风格、尺寸、工厂、材质等业务属性解耦。
- 业务编码仅作为**展示/报表字段**使用，不能替代 UUID 主键；未来如需展示业务编码，应新增 `rspu_code`、`rsku_code` 等独立字段。
- 多风格、多尺寸、多材质通过关联表表达（`rspu_style`、`rspu_scene`、`rspu_variant`、`rsku_supply`），不允许把可变属性写死进主键。

原因与完整决策见 `docs/04-decisions/004-为什么RSPU和RSKU使用UUID主键而非业务编码作为主键.md`。
详细规则见 `docs/06-reference/02-双层编码体系.md`。

### 4.2 分类语义
- **家装家具**：RSPU 第 2 段用风格（MC/CR/IT/FR/WJ/NC/BA/IN/MP）。
- **办公家具**：RSPU 第 2 段用职级（EX/MG/ST/PU/CO），材质码 3 位。
- **吧椅**：类别前缀用 `BS`，第 4 段为座高档（6/7/8），RSKU 加第 7 段功能码。

详细规则见 `docs/06-reference/02-双层编码体系.md`。

---

## 五、编码规范

### 5.1 Java
- 使用 Java 21 特性：Record DTO、模式匹配、虚拟线程。
- 使用 Lombok 减少样板代码。
- DTO 放 `dto/` 包，按 `request/` / `response/` 分包。
- 新增 public 方法必须写 JavaDoc。
- JSON 字段在实体中用 `String` 存储，通过 `config/typehandler/JsonbTypeHandler` 序列化/反序列化。
- 敏感价格字段 `factory_price` 使用 AES 加密 TypeHandler。

### 5.2 RESTful API
- 统一前缀 `/api/v1`。
- 统一响应格式：`{ code, message, data }`（`common.Result`）。
- 分页响应格式：`{ total, page, rows }`（`common.PageResult`）。
- Controller 为薄层，业务逻辑放在 Service。

### 5.3 Vue 3 / TypeScript
- 使用 Composition API + `<script setup lang="ts">`。
- 类型定义放 `src/types/`。
- API 封装放 `src/api/`，Axios 实例放 `src/api/client.ts`。
- 常量/枚举放 `src/utils/constants.ts`。

### 5.4 数据库
- 元数据表用 PostgreSQL，JSON 字段统一存 JSONB。
- 向量主存 ChromaDB，PostgreSQL 只保留 `style_vector` 副本。
- 所有修改 RSPU/RSKU/工厂的操作必须记审计日志。

---

## 六、构建命令

> 所有 `make` 命令见 `Makefile`，`make help` 可查看完整列表。

### 后端
```bash
cd server && mvn clean package
```

### 前端
```bash
cd web && pnpm install && pnpm dev
```

### 全量启动（Docker Compose）
```bash
cd deploy && docker compose up -d
```

### 基础设施 + 数据库初始化（开发）
```bash
make dev
```

### 全量测试
```bash
make test
```

### 清理
```bash
make clean
```

---

## 七、测试要求

- 核心业务逻辑必须有单元测试。
- 涉及外部 HTTP 调用（AI / ChromaDB）的测试使用 WireMock 模拟。
- 提交前必须跑通 `make test`。
- 新增接口必须补充集成测试或至少 Controller 层测试。
- 涉及实体/表结构改动时，运行 `scripts/check_entity_db_fields.js` 做实体-数据库字段对账。

---

## 八、数据库脚本

- **基线 DDL（唯一结构真相）**：`database/schema/`（01_dict → 12_system 按域拆分 + cross_domain_fk 跨域后置外键，编号即执行顺序；全新初始化唯一建表来源。历史增量 V2~V45 已全部并入并归档删除，演进记录见 git 历史）
- **必需种子**：`database/schema/zz_seed.sql`（字典/权限，含在 schema/ 内，字母序最后执行，原 V1__seed_data.sql / seed_required_data.sql）
- **风格知识库种子**：`database/seed_style_knowledge.sql`
- **开发/演示种子**：`database/seed_dev_data.sql`（弱口令测试账号 + 演示工厂/产品；仅开发/演示环境 `make seed-dev` 执行，绝不进生产，initdb 不挂载）
- **重置脚本**：`database/ops/reset_db.sql`（纯开发工具：DROP + 重建 + 必需种子 + 开发测试账号，自包含幂等）
- **结构版本治理（Flyway，仅结构演进）**：标准 Spring Boot 自动配置，上下文初始化期自动执行（`flyway_schema_history` 表为唯一水位，脚本不可变，变更只能新增版本）。V 纯 SQL 脚本在 `server/src/main/resources/db/migration/`（V1 = schema/ 全量基线）。存量环境自动 baseline（`baseline-on-migrate`），全新环境从 V1 顺序执行。**数据修正不走 Flyway**：启动期幂等数据修正任务（价格加密/投影重算/六维归一）由 `DatabaseMigrationRunner` 按序调度一次；可重复长任务（向量重建）走 async_task 业务进度体系
- **同步约定（重要）**：开发期改结构 = 编辑 `database/schema/` 对应域文件 + 同步 `ops/reset_db.sql` + **新增 V 增量脚本**（schema/ 与 V1+ 合并结果必须一致，发布前跑 `make check-migration-sync` 强制校验）；种子数据变化直接改 `database/schema/zz_seed.sql`。所有 DDL 语句幂等可重复执行（IF NOT EXISTS / IF EXISTS 守卫）。实体/表结构改动后运行 `node scripts/check_entity_db_fields.js` 对账
- **迁移失败恢复**：迁移失败会阻断启动并留 failed 记录，恢复动作：修复根因 → `flyway repair`（或删除 flyway_schema_history 中 failed 行）→ 重启。详见 `docs/02-architecture/03-数据库实现说明.md`
- **开发数据库连接**：`server/src/main/resources/application-dev.yml`

---

## 九、AI 协作规则

### 写代码前
1. 先读 `AGENTS.md` 和 `docs/05-status/当前进度.md`。
2. 若仓库根目录存在 `.agent-notes.md`（本机个人规范，已 gitignore、不入库），一并阅读并遵守其中的本机约定；该文件不存在时忽略本条。
3. 涉及数据库改动时先读 `docs/02-architecture/02-数据库设计.md`。
4. 不确定时提问，不要猜测。

### 写代码时
1. 遵循项目中已有的代码风格。
2. 新增 public 方法必须写 JavaDoc。
3. 不要引入未在本文件中声明的新依赖；如确需引入，先向用户说明理由。
4. 修改后必须运行相关测试。

### 写代码后
1. 简要说明改动点。
2. 如果有接口变化，同步更新 `docs/02-architecture/04-API设计.md`。
3. 更新 `docs/05-status/当前进度.md`。

---

## 十、关键外部依赖地址（Docker 容器内）

| 服务 | 地址 |
|:-----|:-----|
| PostgreSQL | `postgres:5432` |
| Ollama | `http://ollama:11434` |
| ChromaDB | `http://chromadb:8000` |
| Redis | `redis:6379` |
| MinIO | `http://minio:9000` |

本地开发时：
- DashScope API：`https://dashscope.aliyuncs.com/compatible-mode/v1`
- 后端：`http://localhost:8081`
- 前端 dev server：`http://localhost:5173`

---

## 十一、一句话总结

> RSDP 是一个以"双层编码 + AI 看图识别 + 三层检索 + 空间校验"为核心的家具产品数字化平台。后端用 SpringBoot + Java 21 编排确定性业务与 AI 调用，前端用 Vue 3 + TypeScript 提供录入/检索/搭配/报价/项目/订单界面，数据落 PostgreSQL + ChromaDB + MinIO，AI 推理当前使用 DashScope 快速验证、后续目标为本地 Ollama，Docker Compose 一键部署。
