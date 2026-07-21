# RSDP × rooom 全量复现与 AI 整合方案

> 创建时间：2026-07-21
> 状态：待实施（阶段 5 起）
> 前置：`RSDP-rooom整合实现方案.md` 阶段 0–4 已全部完成；本文档基于 2026-07-21 对两个站前端产物的完整抓包分析（《rooom 用户端/管理端功能清单》，分析过程稿见会话记录）
> 参考系统：admin.rooom.vip（管理端，yudao 二开）、www.rooom.vip（用户端）、board.rooom.vip（物料板独立子应用）

---

## 第 1 章 复现范围决策

### 1.1 复现 / 不复现清单

| rooom 能力 | 决策 | 理由 |
|---|---|---|
| design 业务域（项目/订单/订单统计/模板/标签） | ✅ 复现（阶段 0–4 已落地主体，本方案补缺） | 核心业务闭环 |
| platform CMS（Banner/案例/内容/自定义字典/定制） | ✅ 复现 | 官网营销区依赖，均为薄 CRUD |
| 用户中心（企业/分组/成员/邀请/认证设计师） | ✅ 复现 | B 端协作基础 |
| 三级账号体系（游客→设计师→企业） | ✅ 复现（映射到现有角色体系） | 低门槛转化设计 |
| 邀请裂变（永久邀请码 + 邀请记录） | ✅ 复现 | 获客机制 |
| 收藏夹两级模型（文件夹 + 条目 + 导出） | ✅ 复现（升级现有收藏夹） | 导出时可隐藏供应商是亮点 |
| 物料板 board.rooom.vip | ⚠️ 自研简化版（不做独立应用） | 独立微前端成本高；用 RSDP 站内「项目画布」增强替代（见 4.3） |
| 邮箱验证码登录 | ✅ 复现 | 短信需运营商通道，邮箱即可覆盖 |
| 短信验证码/社交登录 | ❌ 不做 | 无短信通道；社交登录国内生态不适用 |
| yudao 标准模块（多租户/mall/pay/member/infra 全家桶） | ❌ 不做 | 与 RSPU/RSKU 体系冲突，无业务需要 |
| i18n / 暗色模式 | ⏸️ 暂缓 | token 已预留，非核心 |

### 1.2 与 RSDP AI 能力的整合点（差异化）

| rooom 场景 | RSDP AI 增强 |
|---|---|
| 产品库 | AI 多模态录入（图片/Excel/PDF）+ 以图搜图 + 三层检索（**已有，rooom 无**） |
| 模板/方案 | AI 空间搭配、锚点搭配直接生成方案（**已有**），模板可由 AI 搭配结果一键沉淀 |
| 物料板（简化版画布） | AI 按空间/风格推荐物料组合，替代纯手工拖拽（**新机会**） |
| 项目库 | 加入项目库时可调 AI 校验风格冲突（**可选**） |
| 订单 | AES 出厂价 + 折扣率到手价双轨（**已有，比 rooom 更严谨**） |

---

## 第 2 章 缺口对照总表（现状 → 目标）

| # | 功能 | RSDP 现状 | rooom 目标 | 缺口 |
|---|---|---|---|---|
| 1 | 企业实体 | sys_user 两个文本字段（V6 轻量版） | company 实体（Logo/名称/折扣率/管理员） | **实体化** |
| 2 | 分组/部门 | 同上 | group 实体（名称/启停） | **实体化** |
| 3 | 成员管理 | 仅 ADMIN 建账号 | 搜索用户邀请加入、移出、调分组、变更管理员 | **新增** |
| 4 | 邀请裂变 | 无 | 永久邀请码 + `/login?inviteCode=` + 邀请记录 | **新增** |
| 5 | 认证设计师 | DesignerProfile 未接认证 | 游客一键认证升级设计师 | **新增** |
| 6 | 三级账号 | 角色固定 | TOURIST→DESIGNER→COMPANY 升级路径 | **映射+流程** |
| 7 | 收藏夹 | 单级（group_name 文本） | 文件夹实体 + 条目 + Excel 导出（可选隐藏供应商） | **升级** |
| 8 | 模板体系 | 方案 is_template + JSON 标签 | 独立模板标签 CRUD + 模板库页 + 模板详情 | **增强** |
| 9 | 物料板/画布 | 无（方案详情页充当） | 可视化画布（独立应用） | **自研简化版** |
| 10 | 官网 CMS | 无 | Banner/案例/内容/字典/定制 五模块 | **新增** |
| 11 | 首页营销区 | 静态导航卡 | 轮播 Banner + 新品 + 案例 + 定制入口（CMS 驱动） | **接入 CMS** |
| 12 | 订单行级改价 | 订单价快照不可改 | 行级 adjustPrice 可编辑联动总价 | **增强** |
| 13 | 订单导出清单 | 报价单 Excel（非订单） | 订单明细导出 xlsx | **新增** |
| 14 | 合同上传回填 | 只有模板下载 | 上传合同文件关联订单 | **新增** |
| 15 | 订单统计-邀请维度 | 产品/工厂两维 | + 邀请人维度（邀请成功人数/支付金额） | **新增** |
| 16 | 验证码登录 | 账号密码 | 邮箱验证码登录 | **新增** |
| 17 | 客服咨询 | 无 | 内容配置驱动的客服弹窗 | 随 CMS 附带 |
| 18 | 服务协议勾选 | 无 | 登录页协议弹窗（CMS 内容驱动） | 随 CMS 附带 |

---

## 第 3 章 分阶段实施计划

> 每阶段独立可交付、可回滚；DDL 只增不删；遵守 AGENTS.md 铁律（RSPU/RSKU 结构不动、出厂价 AES、UUID 主键）。
> 每阶段强制：后端 `mvn test` 全量通过；前端 lint + type-check + build 通过；更新 API 文档与当前进度；涉权限更新权限矩阵。

### 阶段 5：用户中心 + 企业团队完整版（约 3 天）

**DDL（V8__member_module.sql）**：

```sql
CREATE TABLE company (                          -- 企业
    company_id    VARCHAR(40) PRIMARY KEY,      -- COM-XXXXXXXX
    company_name  VARCHAR(128) NOT NULL,
    logo_image_id VARCHAR(40),
    price_ratio   NUMERIC(5,4) DEFAULT 1,       -- 企业级折扣率（覆盖全局 price_rate）
    owner_id      VARCHAR(64) NOT NULL REFERENCES sys_user(user_id),
    status        VARCHAR(16) DEFAULT 'active',
    deleted_at    TIMESTAMP,
    created_at / updated_at
);
CREATE TABLE member_group (                     -- 企业内分组/部门
    group_id      VARCHAR(40) PRIMARY KEY,      -- GRP-XXXXXXXX
    company_id    VARCHAR(40) NOT NULL REFERENCES company(company_id),
    group_name    VARCHAR(64) NOT NULL,
    enabled       BOOLEAN DEFAULT true,
    deleted_at    TIMESTAMP, ...
);
ALTER TABLE sys_user ADD COLUMN company_id  VARCHAR(40) REFERENCES company(company_id);
ALTER TABLE sys_user ADD COLUMN group_id    VARCHAR(40) REFERENCES member_group(group_id);
ALTER TABLE sys_user ADD COLUMN invite_code VARCHAR(16) UNIQUE;      -- 永久邀请码
ALTER TABLE sys_user ADD COLUMN invited_by  VARCHAR(64);             -- 邀请人
ALTER TABLE sys_user ADD COLUMN certified_designer BOOLEAN DEFAULT false;
-- 历史数据迁移：company_name 文本 → company 实体（同名企业合并）
CREATE TABLE invite_record (                    -- 邀请记录
    id BIGSERIAL PRIMARY KEY, inviter_id, invitee_id, invite_code, created_at
);
```

**后端**：`CompanyService`/`GroupService`/`MemberService`/`InviteService`
- 企业 CRUD、变更管理员（`update-owner`）、企业折扣率
- 分组 CRUD + 启停
- 成员：按手机号/用户名搜索用户 → 邀请加入企业（`join-company`）、移出、调分组
- 认证设计师：`PUT /api/v1/member/certified-designer`（VIEWER → 挂 certified_designer + 补 DESIGNER 角色）
- 邀请码：注册/创建用户时生成 8 位码；`?inviteCode=` 注册绑定 `invited_by` + 写 invite_record
- 订单价格计算优先级：企业 price_ratio > 全局 price_rate

**前端**：`UserCenterView` 布局（左菜单 4 子页）：个人中心（资料/改密/认证设计师入口）、企业信息、成员管理、邀请用户（复制链接 + 邀请记录）。登录页支持 inviteCode 参数回填。

**角色映射**：rooom TOURIST → RSDP `VIEWER`；DESIGNER → `DESIGNER`；COMPANY 不是角色而是归属（company_id 非空即企业账号）。顶栏显示账号类型徽标。

### 阶段 6：收藏夹升级 + 模板体系增强（约 2 天）

**收藏夹两级模型**：
- 新表 `favorite_folder`（FAVD- 主键、user_id、名称、软删）；`user_favorite` 加 `folder_id`
- 接口：文件夹 CRUD + 收藏时选择文件夹 + 文件夹导出 Excel（`isSup` 参数控制是否显示供应商/工厂列——**保护货源，默认隐藏**）
- 前端：收藏夹页左侧文件夹树（新建/删除）+ 导出按钮

**模板体系**：
- 新表 `template_tag`（标签 CRUD，替代 scheme.template_tags 自由文本；存量标签迁移为标签实体）
- 模板库独立页 `/templates`：左侧标签列表 → 右侧模板卡片 → 模板详情（方案明细 + 空间清单）→「选用模板」→ 选项目套用（复用 copy-from-template）
- 管理端模板标签 CRUD 页（管理 ▾ 下）

### 阶段 7：官网 CMS + 首页营销区（约 2.5 天）

**DDL（V9__platform_cms.sql）**：`platform_banner`（位置字典/图/跳转/排序/状态）、`platform_case`（封面/富文本/状态）、`platform_content`（code 唯一/类型[单图/富文本/嵌入]/内容）、`platform_custom_dict`（名称/类型/状态）、`platform_customized`（封面/排序/状态）

**后端**：5 组薄 CRUD（`/api/v1/platform/*`，管理端 ADMIN 维护）+ 公开读取接口（`/api/v1/public/home` 聚合 Banner/案例/定制；`/api/v1/public/content/{code}` 按编码取内容，免登录）

**前端**：
- 管理端：管理 ▾ 新增「官网内容」组（5 个 CRUD 页，内容管理按类型切换编辑器：单图/富文本）
- 首页 HomeView 重做营销区：轮播 Banner（点击带 rspuId 跳产品详情）→ 新品上架 → 落地案例 → 产品定制 → 平台导览
- 登录页服务协议弹窗（`platform_content` code=platform_user_agreement）；顶栏「咨询」弹窗（code=platform_consulting_service）

### 阶段 8：订单增强（约 1.5 天）

- 订单明细行级 `adjust_price` 可编辑（仅 PENDING），联动折扣总价；DDL：`design_order_item` 加 `adjust_price`（AES）
- 订单导出清单：`GET /api/v1/orders/{id}/export` → `{orderNo}-订单明细.xlsx`（EasyExcel，复用报价导出样式）
- 合同上传回填：`design_order` 加 `contract_file_id`，订单详情页上传/下载合同文件
- 订单统计加邀请维度：`dim=inviter`（按 created_by 聚合邀请成功人数/支付金额，行展开被邀请人明细）

### 阶段 9：项目画布简化版 + AI 物料推荐（约 3 天，可选）

- 不复制 board.rooom.vip 独立应用，增强现有 `ProjectDetailView` 为「画布」：
  - 空间分区视图（按空间标签分组展示项目内方案产品，对齐 rooom inventory 空间清单概念）
  - 拖拽调整产品归属空间/排序（HTML5 drag，Naive UI 无 DnD 依赖，自实现轻量拖拽）
- **AI 物料推荐**：画布内「AI 补全空间」按钮——按空间类型 + 项目已有产品调 `AiMatchingService`，推荐缺失品类产品一键加入（RSDP 独有能力，替代 rooom 纯手工配置）
- 「加入项目库」对话框：产品卡片/详情页直接将产品加入指定项目（生成单产品方案或加入既有方案）

### 阶段 10：登录增强（约 1 天，可选）

- 邮箱验证码登录：`POST /api/v1/auth/email-code`（发码，6 位数字码 5 分钟有效，Redis/内存限流防刷）+ `email-code-login`
- 需配置 SMTP（spring-boot-starter-mail，**新依赖需先向用户说明**）；无 SMTP 时降级显示「未配置邮件服务」

---

## 第 4 章 关键设计决定

### 4.1 不引入 yudao 体系
多租户、member/mall/pay 全家桶与 RSPU/RSKU 双层编码冲突；认证沿用现有 JWT + RBAC + 数据权限，不切换。

### 4.2 企业 ≠ 角色
rooom 的 COMPANY 账号本质是「归属企业的用户」。RSDP 用 `sys_user.company_id` 表达归属，角色仍走 VIEWER/DESIGNER/EDITOR/ADMIN/FACTORY_ADMIN，数据权限按 company 维度扩展（DataScope 增加 COMPANY 范围）。

### 4.3 物料板不复制独立应用
board.rooom.vip 是独立微前端 + 硬编码 token，复制成本高且安全模型差。用站内画布增强 + AI 推荐替代，体验更连贯（详见阶段 9）。

### 4.4 收藏夹导出默认隐藏供应商
出厂价/工厂是 AES 敏感数据，导出 Excel 的 `isSup=true` 需 `factory:read` 权限，默认仅产品维度。

### 4.5 邀请码安全
邀请码仅用于注册归因（invited_by），不携带权限；订单邀请继续走 HMAC token（已有），两套机制不混用。

---

## 第 5 章 里程碑

| 阶段 | 内容 | 估计 | 交付检查点 |
|---|---|---|---|
| 5 | 用户中心+企业团队+邀请+认证 | 3 天 | 企业-分组-成员-邀请全链路 |
| 6 | 收藏夹升级+模板标签+模板库页 | 2 天 | 收藏夹导出、模板一键套用 |
| 7 | 官网 CMS+首页营销区 | 2.5 天 | CMS 配置驱动首页 |
| 8 | 订单行级改价/导出/合同上传/邀请统计 | 1.5 天 | 订单闭环与 rooom 对齐 |
| 9 | 项目画布简化版+AI 物料推荐（可选） | 3 天 | 画布内 AI 补全空间 |
| 10 | 邮箱验证码登录（可选） | 1 天 | 验证码登录可用 |

**总体验收**：对照《rooom 用户端/管理端功能清单》逐项核对，除明确「不做/暂缓」项外全部复现，且每个环节都有 RSDP AI 能力加持（录入/检索/搭配/推荐）。
