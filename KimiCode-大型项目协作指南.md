# Kimi Code 大型项目协作指南

> 本文档说明：在使用 Kimi Code 开发大型项目时，应在工作区准备哪些文件、如何组织它们，以最大程度发挥 AI Agent 的能力上限。

---

## 一、核心原则

Kimi Code 的能力上限不只取决于模型本身，更取决于**工作区信息的完整度和可检索性**。

| 原则 | 说明 |
|:---|:---|
| **重要信息落盘** | 不要只依赖对话上下文，关键决策、规范、状态都要写成文件 |
| **分层组织** | 项目级 → 模块级 → 任务级，逐级细化 |
| **AI 可读格式** | Markdown、JSON、YAML、SQL 优先，避免图片/PDF/二进制作为唯一信息源 |
| **持续更新** | 项目演进时同步更新文档，否则 AI 会按过期信息执行 |

---

## 二、必须准备的 7 类文件

### 1. `AGENTS.md` —— 项目级指令（最重要）

这是 Kimi Code 的官方约定文件。放在项目根目录或子模块目录，AI 会自动读取并遵循其中的指令。

**作用**：
- 定义项目结构
- 规定技术栈
- 约定编码规范
- 说明测试要求
- 指定构建/运行命令

**示例模板**：

```markdown
# AGENTS.md

## 项目概述
- 项目名称：<你的项目名>
- 目标：<一句话描述项目要解决的核心问题>
- 主技术栈：Spring Boot 3 + Java 21 + MyBatis-Plus + MySQL

## 目录结构
```
backend/           # 后端服务
frontend/          # 前端应用
docs/              # 文档
scripts/           # 脚本
```

## 编码规范
- Java：遵循团队编码规约，使用 Lombok，DTO 用 Record
- 后端接口：RESTful 风格，统一返回格式 `{ code, data, message }`
- 前端：Vue3 Composition API + TypeScript

## 构建命令
- 后端：cd backend && mvn clean package
- 前端：cd frontend && pnpm install && pnpm dev
- 全量启动：docker compose up -d

## 测试要求
- 核心业务逻辑必须有单元测试
- 涉及外部 HTTP 调用的测试使用 Mock 工具（如 WireMock）
- 提交前必须跑通测试

## 数据库
- 数据库文件/连接配置位置：根据项目实际填写
- 建表脚本：./scripts/init_db.sql
```

**建议位置**：
- 项目根目录：`/AGENTS.md`（全局规则）
- 子模块目录：`/backend/AGENTS.md`（后端模块专属规则）
- 深层目录规则优先于父目录

---

### 2. `README.md` —— 人类 + AI 都能读懂的项目入口

README 是给新成员（包括 AI）快速理解项目用的。

**应包含**：
- 项目一句话介绍
- 核心功能列表
- 快速开始（Quick Start）
- 技术栈
- 目录说明
- 常见问题或指向 docs/FAQ.md

**示例结构**：

```markdown
# 项目名称

一句话介绍：这个项目是做什么的，解决什么问题。

## 快速开始
1. 安装 Docker 和 Docker Compose
2. 复制 `.env.example` 为 `.env`
3. 执行 `docker compose up -d`
4. 访问 http://localhost

## 核心模块
- `backend/`：后端服务
- `frontend/`：前端应用

## 文档
详细设计见 `docs/` 目录。
```

---

### 3. `docs/` 文档目录 —— 按主题沉淀知识

不要把所有文档堆在根目录。建议按以下结构组织：

```
docs/
├── 01-requirements/          # 需求文档
│   ├── 产品需求.md
│   └── 技术需求.md
├── 02-architecture/          # 架构设计
│   ├── 整体架构.md
│   ├── 数据库设计.md
│   └── API 设计.md
├── 03-guides/                # 开发指南
│   ├── 本地开发环境搭建.md
│   ├── 编码规范.md
│   └── 测试指南.md
├── 04-decisions/             # 决策记录（ADR）
│   ├── 001-为什么选择当前技术栈.md
│   └── 002-为什么选择当前数据库.md
├── 05-status/                # 项目状态
│   ├── 当前进度.md
│   └── 待办事项.md
└── 06-reference/             # 参考资料
    ├── 业务规则说明.md
    └── 数据字典.md
```

**关键文件说明**：

| 文件 | 作用 |
|:---|:---|
| `业务规则说明.md` | 编码体系、命名规则、状态流转等业务核心规则 |
| `数据字典.md` | 关键枚举值、状态码、类型映射等标准字典 |
| `API 设计.md` | 接口契约，AI 写 Controller 时直接照做 |
| `数据库设计.md` | 表结构、字段含义、索引设计 |

---

### 4. `.cursorrules` / `.ai-rules`（可选但推荐）

虽然 Kimi Code 优先读取 `AGENTS.md`，但一些团队也会准备 `.cursorrules` 风格的规则文件作为补充。

**内容示例**：

```markdown
# AI 协作规则

## 写代码前
1. 先读 AGENTS.md 和 docs/当前进度.md
2. 涉及数据库改动时先读 docs/数据库设计.md
3. 不确定时提问，不要猜测

## 写代码时
1. 遵循项目中已有的代码风格
2. 新增 public 方法必须写 JavaDoc
3. 不要引入未在 AGENTS.md 中声明的新依赖
4. 修改后必须运行相关测试

## 写代码后
1. 简要说明改动点
2. 如果有接口变化，同步更新 docs/API 设计.md
```

---

### 5. `scripts/` 脚本目录 —— 自动化重复操作

把常用命令写成脚本，AI 执行时不易出错。

```
scripts/
├── init_db.sql              # 数据库初始化
├── seed_data.sql            # 测试数据
├── setup.sh                 # 开发环境一键搭建
├── test.sh                  # 运行全部测试
└── backup_db.sh             # 数据库备份
```

**对 AI 的好处**：
- 不用记复杂命令
- 减少环境差异导致的错误
- 操作可复现

---

### 6. `Makefile` 或 `package.json` scripts —— 统一命令入口

```makefile
# Makefile 示例
dev:
	docker compose up -d

clean:
	docker compose down -v

test:
	cd backend && mvn test
	cd frontend && pnpm test

lint:
	cd backend && mvn checkstyle:check
	cd frontend && pnpm lint
```

AI 可以直接调用 `make dev`、`make test`，不需要你每次复述命令。

---

### 7. 状态跟踪文件 —— 让 AI 知道"现在做到哪了"

大型项目最怕上下文丢失。准备以下文件作为"外部记忆"：

#### `docs/05-status/当前进度.md`

```markdown
# 当前进度

## 已完成
- [x] 数据库建表脚本
- [x] 用户认证模块
- [x] 基础 API 框架搭建

## 进行中
- [ ] 核心业务模块 A
- [ ] 数据导入导出功能

## 阻塞项
- 暂无

## 下一步
1. 完成核心业务模块 A 的单元测试
2. 接入缓存层
```

#### `docs/05-status/待办事项.md`

```markdown
# 待办事项

## P0（本周必须完成）
- [ ] 完成核心业务模块 A 接口开发
- [ ] 补充模块 A 单元测试

## P1（下周）
- [ ] 接入消息队列
- [ ] 前端核心页面开发

## P2（后续）
- [ ] 报表导出功能
- [ ] 操作日志审计
```

**使用建议**：每次让 AI 工作后，让它更新这些文件，下一轮从文件恢复状态。

---

## 三、按项目阶段准备文件

### 阶段 1：项目启动期

应准备：
- `README.md`
- `AGENTS.md`
- `docs/01-requirements/`
- `docs/02-architecture/`
- `docs/04-decisions/`（早期重要决策）

### 阶段 2：开发期

增加：
- `docs/03-guides/`
- `docs/05-status/当前进度.md`
- `docs/05-status/待办事项.md`
- `scripts/`
- `Makefile`

### 阶段 3：维护期

增加：
- `docs/06-reference/`（常见问题、运维手册）
- `CHANGELOG.md`
- `docs/04-decisions/` 持续补充

---

## 四、文件内容的最佳实践

### 1. 用 Markdown 而不是 Word/PDF

AI 可以直接读取 Markdown，但很难读取复杂格式文档。如果只有 PDF/Word，建议同时生成 Markdown 版本。

### 2. 关键信息放在文件开头

AI 读取长文档时，开头的信息权重更高。把最重要的约束放在前面。

### 3. 使用明确的标题层级

```markdown
## 二、编码规范
### 2.1 Java
#### 命名规范
```

这样 AI 能快速定位到相关章节。

### 4. 配置和常量单独成文件

不要把编码规则、材质映射、枚举值散落在多个文档里。建议集中成表：

```markdown
| 状态码 | 含义 | 说明 |
|:---|:---|:---|
| 0 | 待处理 | 新建工单初始状态 |
| 1 | 处理中 | 已被认领 |
| 2 | 已完成 | 流程结束 |
```

### 5. 示例数据很重要

文档中给出 2-3 个真实示例，AI 更容易理解规则。

```markdown
订单编号示例：ORD-2024-00001
- ORD = 订单前缀
- 2024 = 年份
- 00001 = 流水号
```

---

## 五、和 AI 协作时的标准话术

| 场景 | 你可以这样说 |
|:---|:---|
| 开始新任务 | 「先读 `AGENTS.md` 和 `docs/当前进度.md`，然后我们开始做 X」 |
| 让 AI 更新状态 | 「完成后更新 `docs/05-status/当前进度.md`」 |
| 防止 AI 乱加依赖 | 「新增依赖前先查 `AGENTS.md` 的技术栈列表，不确定就问我」 |
| 长任务分段 | 「这是第一阶段，先完成 X，不要动 Y」 |
| 上下文压缩后 | 「重新读一下 `docs/02-architecture/整体架构.md`，我们继续」 |

---

## 六、不推荐的做法

| ❌ 不推荐 | 原因 |
|:---|:---|
| 把重要信息只发在对话里 | 上下文压缩后会丢失 |
| 文档长期不更新 | AI 会按过期信息执行 |
| 所有文档堆在根目录 | AI 难以快速定位 |
| 用图片解释架构 | AI 可能读错或读不全 |
| 不写 `AGENTS.md` | AI 缺乏项目级约束，容易偏离方向 |

---

## 七、一句话总结

> **准备 `AGENTS.md` 作为项目级宪法，`docs/` 按主题沉淀设计/规范/状态，`scripts/` 和 `Makefile` 固化常用操作。让 AI 能随时从文件恢复上下文，而不是依赖对话记忆——这是大型项目用好 Kimi Code 的关键。**

---

**附：快速检查清单**

- [ ] 项目根目录有 `AGENTS.md`
- [ ] 有 `README.md` 说明快速开始
- [ ] 有 `docs/` 目录且按主题分类
- [ ] 有 `docs/05-status/当前进度.md`
- [ ] 有 `docs/05-status/待办事项.md`
- [ ] 关键业务规则（编码体系、状态流转、枚举字典等）单独成文档
- [ ] 常用命令有 `Makefile` 或脚本
- [ ] 重要决策写在 `docs/04-decisions/`
