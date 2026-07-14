# RSDP × rooom 设计体系整合实现方案

> 创建时间：2026-07-13
> 状态：实施中（阶段 0 设计系统与导航、阶段 1 品牌首页+收藏夹+产品库筛选 已于 2026-07-14 完成）
> 参考系统：admin.rooom.vip（管理端）、www.rooom.vip（用户端）

---

## 第 1 章 参考系统分析

### 1.1 管理端 admin.rooom.vip

**技术栈**：基于「芋道 yudao（ruoyi-vue-pro）」开源框架二次开发。

| 层 | 技术 |
|---|---|
| 前端 | Vue 3 + Composition API + TypeScript + Vite + Element Plus + form-create/form-designer（低代码表单） |
| 后端 API | `https://api.rooom.vip/admin-api` |
| 认证 | `/system/auth/login` + 图形验证码 + 多租户（按域名/租户名识别） |
| 路由 | 后端菜单驱动：`get-permission-info` 拉取菜单树，前端按 component 字符串动态映射页面 |
| 字典 | 全局 `DictTag` 组件，如 `design_project_type`、`design_order_status` |

页面组织套路统一：每个模块 = `index.vue`（搜索栏 + 表格 + 分页 + 按钮权限）+ `XxxForm.vue`（对话框表单）+ 独立 API 封装。

**自研 design 模块（5 个页面）**：

| 页面 | 路由 | 接口 | 关键字段/功能 |
|---|---|---|---|
| 项目管理 | `/design/project` | `/design/project/page\|create\|update` | 项目名称、项目类型（字典）、企业ID/名称、创建人 |
| 项目订单 | `/design/order` | `/design/project-order/page\|update\|invite-order\|get-tabs` | 订单编号、收件人（姓名/手机/地区/地址）、产品数量、原价/折后价/折扣率、订单状态（字典）、预计交期、合同、商品明细（图片/属性/单价/总价）；`invite-order` = 邀请下单；`get-tabs` = 状态 Tab 计数 |
| 订单统计 | `/design/orderStatistics` | `/design/project-order/order-statistics` | 三维度：产品统计（SPU 编码/名称、总数量/总金额）、供应商统计（供应商编号、订单总数/总支付金额）、邀请统计（邀请人昵称/手机号、邀请成功人数）；时间筛选 |
| 模板项目 | `/design/templateProject` | `/design/template-project/*` | 项目名称、项目标签、描述、状态；子页 templateConfig = 物料板（MaterialBoard）可视化配置 |
| 模板标签 | `/design/templateTag` | `/design/template-tag/page\|create\|update\|simple-list` | 标签名称 CRUD |

**platform 模块（官网 CMS）**：Banner、案例（cases）、内容管理、自定义字典、定制（customized）。

其余为 yudao 标准模块：system（用户/角色/菜单/字典/租户/日志）、infra（代码生成/文件/定时任务）、mall（SPU/订单/营销/DIY 装修）、member（会员全景）、pay（支付渠道/订单/退款）。

### 1.2 用户端 www.rooom.vip

**技术栈**：Vue 3 + Vite + Element Plus SPA，i18n（`t("menu.xxx")`），暗色模式（`html.dark`），标题「rooom家居全案」。

**页面与路由**：

| 路由 | 页面 | 说明 |
|---|---|---|
| `/` | 首页 | 平台介绍、分级导航（按空间/风格/品类/材质多维分类）、平台使用导览（①按空间风格筛选 ②多条件智能筛选 ③一键收藏与对比 ④创建方案并导出分享 ⑤在线客服）、内容营销区（产品定制/新品上架/新材料/落地案例——对应管理端 platform 模块） |
| `/product` | 产品库 | 品类筛选（`product/category/list`）、颜色筛选（`product/color/simple-list`）、收藏（`product/favorite/*`） |
| `/template` | 模板 | 浏览模板项目（标签筛选），「选用模板」→ `design/project/create-by-template` → 跳转项目画布 |
| `/project` | 项目 | 我的项目列表、创建项目、项目画布（组方案）、项目内下单（图片/型号/原价/到手价）、合同模板下载（`合同模版.docx`）、修改/取消订单 |
| `/favorites` | 收藏夹 | 收藏产品列表 |
| `/user` | 用户中心 | 企业信息（`member/company/*`）、团队分组（`member/group/*`）、成员邀请（`member/user/invite-list`）、认证设计师（`member/user/certified-designer`） |
| `/login` | 登录 | 邮箱验证码 / 短信验证码 / 社交登录 |

**核心 app-api 接口**：

```
design:    /app-api/design/project/{list,create,update,create-by-template}
           /app-api/design/project-library/{list,create}
           /app-api/design/project-order/{create,page,update,update-status}
           /app-api/design/template-tag/simple-list
config:    /app-api/infra/config/get-price-rate        # 全局折扣率
product:   /app-api/product/spu/page  /category/list  /color/simple-list
           /app-api/product/favorite/{create,delete,exits,list}
member:    /app-api/member/auth/{login,sms-login,email-code-login,send-*-code}
           /app-api/member/company/{get,update-company,update-owner}
           /app-api/member/group/{create,list,update}
           /app-api/member/user/{get,list,update,invite-list,join-company,certified-designer,...}
```

### 1.3 两端关联关系

```
管理端 admin（平台运营）                    用户端 www（设计师/客户）
┌─────────────────────────┐          ┌──────────────────────────┐
│ 模板项目 + 物料板配置     │ ──发布──▶ │ /template 浏览选用模板     │
│ 模板标签 CRUD            │ ──标签──▶ │ 模板筛选                  │
│ 折扣率配置 price-rate    │ ──比率──▶ │ 下单时计算到手价           │
│ 项目管理（全量）          │ ◀──同步── │ /project 创建/编辑项目     │
│ 项目订单管理/状态流转     │ ◀──下单── │ 项目内创建订单/取消        │
│ 邀请下单 invite-order    │ ──链接──▶ │ 客户通过邀请链接下单       │
│ 订单统计（产品/供应商/邀请）│ ◀──数据── │ 订单数据沉淀              │
│ platform CMS            │ ──内容──▶ │ 首页营销区                │
└─────────────────────────┘          └──────────────────────────┘
              共享 design 业务域（project / template-tag / project-order / project-library）
```

**核心业务闭环**：浏览产品库 → 收藏/对比 → 选模板创建项目 → 项目画布组方案 → 下单（原价 × 折扣率 = 到手价）→ 合同/履约跟踪 → 管理端统计。

### 1.4 用户端视觉设计 token

| token | 值 | 用途 |
|---|---|---|
| `--color-highlight` | `#2453FC` | 品牌主色（按钮/链接/选中态） |
| `--color-bg` | `#FAFAFA` | 页面底色 |
| `--color-black-5` | `#243443` | 主文本色 |
| `--color-black-4` / `--color-tips` | `#999999` | 辅助文本 |
| `--color-black-2` | `#D9D9D9` | 边框/分割线 |
| `--color-menu-light` | `#EAEAEA` | 菜单底色 |
| `--color-serve-bg` | `#F6F7F9` | 服务区底色 |
| `--color-price` | `#FF0000` | 价格色 |
| `--color-transparent-black` | `rgba(0,0,0,.6)` | 蒙层 |
| 正文字体 | `"Helvetica Neue", Helvetica, "PingFang SC", "Microsoft YaHei"` | 正文 |
| 展示字体 | `Gloock`（英文衬线展示体）、`ChuangKeTieJinGangTi`（中文展示体）、iconfont | 大标题/图标 |
| 暗色模式 | `html.dark` 下全量变量覆盖 | 预留能力 |

---

## 第 2 章 RSDP 现状对照

### 2.1 能力映射表

| 业务能力 | RSDP 现状 | rooom 对应物 | 结论 |
|---|---|---|---|
| 产品录入 | 图片 AI 识别、Excel AI 导入（内嵌图+多价格列）、PDF 目录导入、工厂单条录入、Excel 模板导入 | 无 | **RSDP 领先，完全保留** |
| 产品库 | RSPU 双层编码、多维筛选、以图搜图、向量检索 | SPU 列表 + 品类/颜色筛选 | RSDP 领先，补收藏/对比即可 |
| 工厂报价 | RSKU + 能力等级 + 交期规则 + 价格加密 | 供应商概念弱（仅统计维度） | RSDP 领先 |
| 搭配方案 | scheme + AI 搭配（RoomScheme/Anchor）+ 报价单 + Excel 导出 | 项目画布内手工组方案 | RSDP 领先，补项目归属与模板化 |
| 设计项目 | ❌ 无 | project + 项目画布 | **新增** |
| 方案模板化 | ❌ 无 | 模板项目 + 标签 + create-by-template | **新增** |
| 收藏夹 | ❌ 无 | favorite + favorites | **新增** |
| 订单 | ❌ 无（报价单是一次性计算） | project-order（快照/折扣/状态机/合同/邀请） | **新增** |
| 订单统计 | ❌ 无 | order-statistics 三维度 | **新增（可选）** |
| 企业/团队 | sys_user 单用户体系 | company/group/invite | **轻量新增（可选）** |
| 品牌视觉 | 朴素顶栏工具风 | 品牌首页 + 设计 token + 暗色模式 | **重构** |
| 权限/审计 | RBAC + 数据权限 + 审计日志 | yudao 体系 | RSDP 保留自有体系 |

### 2.2 缺口清单（按优先级）

1. **设计项目 Project**：顶层业务容器，方案/报价/订单归属项目，提供「项目画布」工作台
2. **方案模板化**：模板方案 + 标签 + 一键套用生成新方案
3. **收藏夹**：产品收藏、分组
4. **订单 Order**：价格快照、折扣率/到手价、收件人、状态机、合同下载、邀请下单链接
5. **品牌视觉与导航重构**：设计 token、品牌首页、工作流分组导航
6. **订单统计**（可选）
7. **企业/团队轻量版**（可选）

### 2.3 整合原则

1. **不切换 UI 框架**：保留 Naive UI，用 `themeOverrides` + CSS 变量还原参考站视觉。切换 Element Plus 需重写 20+ 页面，成本远超收益。
2. **不引入 yudao 体系**：多租户、member、mall、pay 均不需要；只取 design 域的业务概念。
3. **复用现有多模态录入链路**：录入能力不动，只做入口整合（录入中心）。
4. **遵守 AGENTS.md 铁律**：RSPU/RSKU 双层结构不动；订单价格快照从 RSKU 读取后落库，不改 `rspu_master`/`rsku_supply` 结构；出厂价保持 AES 加密，订单/公开页不泄露。
5. **每阶段独立可交付、可回滚**；数据库变更只增不删，新列可空。

---

## 第 3 章 总体目标架构

### 3.1 目标业务闭环

```
┌─ 录入中心 ─────────────────────────────────────────────┐
│ 图片AI录入 / 工厂录入 / Excel AI导入 / PDF导入           │
└──────────────────────┬─────────────────────────────────┘
                       ▼
┌─ 产品库 ── 收藏夹 ◀── 收藏/分组                         │
│  RSPU + 多维筛选 + 以图搜图                             │
└──────────────────────┬─────────────────────────────────┘
                       ▼
┌─ 设计项目（项目画布）                                     │
│  ├─ 从模板创建方案（套用 → 刷新当前价）                   │
│  ├─ AI 搭配生成方案                                     │
│  └─ 手动选品建方案                                      │
└──────────────────────┬─────────────────────────────────┘
                       ▼
┌─ 报价单（数量/小计/Excel 导出）                           │
└──────────────────────┬─────────────────────────────────┘
                       ▼
┌─ 订单（价格快照 × 折扣率 = 到手价、收件人、状态机、合同）   │
│  └─ 邀请链接 → 客户免登录确认                            │
└──────────────────────┬─────────────────────────────────┘
                       ▼
┌─ 订单统计（产品/工厂维度）                                │
└─────────────────────────────────────────────────────────┘
```

### 3.2 目标导航结构

```
┌ 品牌顶栏：Logo + RSDP 家居全案平台 ──────── 用户菜单（角色/设置/退出）┐
├ 录入中心 ▾ │ 产品库 │ 项目 │ 搭配方案 │ 报价 │ 工厂 │ 管理 ▾（管理员）  │
│  ├ 新品录入                                                          │
│  ├ 工厂录入（FACTORY_ADMIN）                                          │
│  ├ Excel AI 导入                                                     │
│  └ PDF 导入                                                          │
└─────────────────────────────────────────────────────────────────────┘
管理 ▾：用户管理 / 字典（后续）/ 系统监控（后续）
```

收藏夹入口放在产品库页内与用户菜单中；订单入口放在「报价」组内（报价单生成器 / 订单列表 / 订单统计）。

### 3.3 新增数据模型 ER 概览

```
sys_user 1 ──── n user_favorite n ──── 1 rspu_master
sys_user 1 ──── n project
project  1 ──── n scheme（scheme.project_id 可空）
scheme   1 ──── n scheme_item（现有）
scheme   1 ──── n design_order（由方案生成，可多次）
design_order 1 ── n design_order_item（价格/商品快照）
rspu_master / rsku_supply：结构不变，仅作为快照数据源
```

---

## 第 4 章 分阶段详细实现步骤

> 每阶段完成后必须：后端 `cd server && mvn test` 全量通过；前端 `pnpm lint` + `pnpm type-check` + `pnpm build` 通过；更新 `docs/02-architecture/04-API设计.md` 与 `docs/05-status/当前进度.md`。

### 阶段 0：设计系统与布局骨架（纯前端，约 0.5 天）

**目标**：建立设计 token 体系，导航重构为工作流分组，不改任何业务逻辑。

#### 4.0.1 新增 `web/src/styles/tokens.css`

```css
:root {
  /* 品牌色 */
  --rsdp-primary: #2453FC;
  --rsdp-primary-hover: #4A73FD;
  --rsdp-primary-pressed: #1B3FC4;
  /* 中性色 */
  --rsdp-bg: #FAFAFA;
  --rsdp-text: #243443;
  --rsdp-text-secondary: #999999;
  --rsdp-border: #D9D9D9;
  --rsdp-menu-bg: #EAEAEA;
  --rsdp-card-bg: #FFFFFF;
  --rsdp-serve-bg: #F6F7F9;
  /* 语义色 */
  --rsdp-price: #FF0000;
  --rsdp-mask: rgba(0, 0, 0, 0.6);
  /* 字体 */
  --rsdp-font-display: "Gloock", "Georgia", serif;
  --rsdp-font-body: "Helvetica Neue", Helvetica, "PingFang SC", "Microsoft YaHei", sans-serif;
  /* 尺寸 */
  --rsdp-radius: 8px;
  --rsdp-radius-lg: 12px;
  --rsdp-page-padding: 24px;
}
/* 暗色模式预留（本期不启用切换器） */
html.dark {
  --rsdp-bg: #14181C;
  --rsdp-text: #EFEFEF;
  --rsdp-card-bg: #1E242B;
  --rsdp-border: #595D64;
}
```

> 注：Gloock 字体文件若无授权，展示标题回退 Georgia 衬线；后续可替换为授权字体放 `web/public/fonts/`。

#### 4.0.2 `web/src/main.ts` 与 `App.vue`

- `main.ts`：`import '@/styles/tokens.css'`
- `App.vue` 的 `NConfigProvider` 增加：

```ts
const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#2453FC',
    primaryColorHover: '#4A73FD',
    primaryColorPressed: '#1B3FC4',
    borderRadius: '8px',
    fontFamily: 'var(--rsdp-font-body)'
  },
  Card: { borderRadius: '12px' },
  Button: { borderRadiusMedium: '8px' }
}
```

#### 4.0.3 导航重构（`App.vue`）

- 顶栏改为：左侧品牌区（Logo + 「RSDP 家居全案平台」），右侧用户下拉。
- 导航项收敛为分组（`NDropdown` / `NMenu` 水平模式）：
  - **录入中心**（`product:create` / `product:import` 可见）：新品录入 `/entry`、工厂录入 `/factory-entry`（FACTORY_ADMIN）、Excel AI 导入 `/products/excel-ai-import`、PDF 导入 `/products/document-import`
  - **产品库** `/products`（含收藏夹入口）
  - **项目** `/projects`（阶段 2 落地，先占位路由）
  - **搭配方案** `/schemes` + AI 搭配 `/matching/room-scheme`
  - **报价** ▾：报价单生成器 `/quotes/build`、订单列表 `/orders`（阶段 3）、订单统计 `/orders/statistics`（阶段 4）
  - **工厂** `/factories`
  - **管理** ▾（ADMIN）：用户管理 `/admin/users`
- 以图搜图 `/visual-search` 收入「产品库」下拉。
- 高亮规则：`route.path.startsWith(groupPrefix)`。

#### 4.0.4 新增 `web/src/components/PageContainer.vue`

统一页面容器：标题区（display 字体）+ 操作区插槽 + 内容区（`max-width: 1440px`、居中、`padding: var(--rsdp-page-padding)`、卡片圆角 12px）。本阶段只在首页套用，其余页面后续渐进迁移。

#### 4.0.5 验收

- [ ] `pnpm lint` + `pnpm type-check` + `pnpm build` 通过
- [ ] 全部现有路由可正常访问，权限显隐规则不变
- [ ] 主色、圆角、字体全局生效

---

### 阶段 1：品牌首页 + 收藏夹 + 产品库体验（约 1.5 天）

#### 4.1.1 品牌首页 `HomeView.vue` 重做

区块规格（套用 `PageContainer`）：

1. **Hero 区**：大标题（display 字体）「家居全案数字化平台」+ 副标题 + 主按钮「开始录入」/「浏览产品库」；底色 `--rsdp-serve-bg`
2. **分级导航卡片区**：四张卡片「按空间 / 按风格 / 按品类 / 按材质」，点击跳 `/products?scene=xx&style=xx&category=xx&material=xx`（复用产品库现有筛选参数）
3. **使用导览**：五步横向流程图——① 多模态录入（图片/Excel/PDF）② AI 自动识别 ③ 选品收藏 ④ 搭配方案 ⑤ 报价导出
4. **快捷入口卡片**：新品录入、Excel AI 导入、PDF 导入、以图搜图（按权限显隐）

#### 4.1.2 收藏夹

**DDL**（追加到 `database/V4__project_module.sql`）：

```sql
CREATE TABLE user_favorite (
    favorite_id  VARCHAR(40) PRIMARY KEY,           -- FAV-XXXXXXXX
    user_id      UUID NOT NULL REFERENCES sys_user(user_id),
    rspu_id      VARCHAR(40) NOT NULL REFERENCES rspu_master(rspu_id),
    group_name   VARCHAR(64),                        -- 收藏分组，可空
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, rspu_id)
);
CREATE INDEX idx_favorite_user ON user_favorite(user_id, created_at DESC);
```

**后端文件**：

| 文件 | 说明 |
|---|---|
| `entity/UserFavorite.java` | 实体，`@TableName("user_favorite")` |
| `mapper/UserFavoriteMapper.java` | MyBatis-Plus Mapper |
| `service/FavoriteService.java` | 收藏/取消/列表/批量检查；收藏时校验 RSPU 存在且未删除 |
| `controller/FavoriteController.java` | 见下接口 |
| `dto/request/FavoriteRequest.java` | `{rspuId, groupName?}` |
| `dto/response/FavoriteResponse.java` | `{favoriteId, rspuId, groupName, productSummary, createdAt}`（productSummary 复用产品摘要组装） |

**接口**：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/favorites` | 收藏，重复收藏返回业务异常「已收藏」 |
| DELETE | `/api/v1/favorites/{rspuId}` | 取消收藏（按当前用户） |
| GET | `/api/v1/favorites?group=&page=&size=` | 我的收藏列表（分页，批量组装产品摘要+主图，避免 N+1） |
| GET | `/api/v1/favorites/check?rspuIds=a,b,c` | 批量检查收藏状态，返回已收藏的 rspuId 列表 |

权限：`authenticated()`（任意登录用户收藏自己的）；Service 层一律以 `SecurityOperatorContext.currentUserId()` 过滤。

**前端文件**：

| 文件 | 说明 |
|---|---|
| `web/src/types/favorite.ts` | `FavoriteItem` 类型 |
| `web/src/api/favorite.ts` | `addFavorite` / `removeFavorite` / `listFavorites` / `checkFavorites` |
| `web/src/views/FavoritesView.vue` | `/favorites`：分组筛选（NSelect）+ 产品卡片网格 + 多选「生成报价单」（复用 QuoteBuilder 的 rspuIds 入参） |

**交互**：产品库列表行与产品详情页增加收藏图标按钮（心形，已收藏高亮 `--rsdp-primary`）；进入列表时用 `checkFavorites` 批量回填状态。

#### 4.1.3 产品库筛选 UI

`ProductListView.vue` 增加左侧筛选面板（`NLayoutSider`，宽 240px，可折叠）：品类 / 风格 / 场景 / 材质分组复选（字典驱动，复用现有 `dicts` 接口），右侧列表保留表格/卡片切换。移动端不适配（内部工具）。

#### 4.1.4 测试

- `FavoriteServiceTest`：收藏成功、重复收藏拒绝、取消收藏、列表按用户隔离、RSPU 不存在拒绝、批量 check
- 前端 lint + type-check

---

### 阶段 2：设计项目 Project + 方案模板化（约 2 天）

#### 4.2.1 DDL（`database/V4__project_module.sql` 主体）

```sql
CREATE TABLE project (
    project_id    VARCHAR(40) PRIMARY KEY,          -- PROJ-XXXXXXXX
    project_name  VARCHAR(128) NOT NULL,
    project_type  VARCHAR(32),                       -- project_type 字典码
    company_name  VARCHAR(128),
    owner_id      VARCHAR(64) NOT NULL REFERENCES sys_user(user_id),
    status        VARCHAR(20) NOT NULL DEFAULT 'active',
    remark        VARCHAR(512),
    deleted_at    TIMESTAMP,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_project_owner ON project(owner_id) WHERE deleted_at IS NULL;

ALTER TABLE scheme ADD COLUMN project_id    VARCHAR(40) REFERENCES project(project_id);
ALTER TABLE scheme ADD COLUMN is_template   BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE scheme ADD COLUMN template_tags TEXT;    -- JSON 字符串，与项目 TEXT 约定一致
CREATE INDEX idx_scheme_project ON scheme(project_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_scheme_template ON scheme(is_template) WHERE is_template = true AND deleted_at IS NULL;
```

`project_type` 字典种子（追加 `database/V4__project_module.sql`）：`whole_house` 全屋 / `space` 单空间 / `custom` 定制。

权限种子：`project:read` / `project:create` / `project:update` / `project:delete`，ADMIN + DESIGNER + EDITOR 授予全部，VIEWER 仅 read。

#### 4.2.2 后端文件

| 文件 | 说明 |
|---|---|
| `entity/Project.java` | `@TableLogic` 逻辑删除 |
| `mapper/ProjectMapper.java` | |
| `service/ProjectService.java` | CRUD + 所有权校验（`assertOwnerOrAdmin`）；详情聚合项目下方案列表（批量查 scheme + 总价） |
| `controller/ProjectController.java` | 见下接口 |
| `dto/request/ProjectRequest.java` | `{projectName, projectType?, companyName?, remark?}`，`@NotBlank projectName`、`@Size(max=128)` |
| `dto/response/ProjectResponse.java` / `ProjectDetailResponse.java` | 详情含 `List<SchemeSummary>` |
| `service/SchemeService.java`（改） | 创建/更新支持 `projectId`（校验项目归属）；`copyFromTemplate`；`setTemplate` |
| `entity/Scheme.java`（改） | 增 `projectId` / `isTemplate` / `templateTags`（JsonbTypeHandler） |
| `security/Permission.java`（改） | 增 `project:*` 常量 |
| `security/SecurityConfig.java`（改） | `/api/v1/projects/**` 按权限控制 |

**接口**：

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/projects?keyword=&page=&size=` | `project:read` | 我的项目列表（ADMIN 看全部） |
| POST | `/api/v1/projects` | `project:create` | 创建项目 |
| GET | `/api/v1/projects/{id}` | `project:read` + 归属校验 | 详情（含方案列表与总价） |
| PUT | `/api/v1/projects/{id}` | `project:update` + 归属校验 | 更新 |
| DELETE | `/api/v1/projects/{id}` | `project:delete` + 归属校验 | 软删除（项目下方案保留，project_id 置空） |
| POST | `/api/v1/schemes/{id}/copy-from-template` | `scheme:create` | 套用模板：复制 scheme_item → 新方案（归属指定 projectId），**价格取当前 RSKU 最新价**，响应带 priceChanges 对比 |
| PUT | `/api/v1/schemes/{id}/template` | `scheme:update` + 归属校验 | 设为/取消模板（body `{isTemplate, templateTags?}`） |
| GET | `/api/v1/schemes?isTemplate=true&tag=` | `scheme:read` | 模板列表（扩展现有列表参数） |

#### 4.2.3 前端文件

| 文件 | 说明 |
|---|---|
| `web/src/types/project.ts` / `api/project.ts`（扩） | 类型与接口封装 |
| `web/src/views/ProjectListView.vue` | `/projects`：项目卡片网格（名称/类型标签/企业/方案数/更新时间）+ 创建弹窗 |
| `web/src/views/ProjectDetailView.vue` | `/projects/:id`「项目画布」：项目信息头 + 三个入口按钮（新建方案 / 从模板创建 / AI 搭配）+ 方案卡片列表（总价、价格变动提示徽标、进入详情） |
| `web/src/views/SchemeListView.vue`（改） | 增加「模板」开关筛选与标签筛选 |
| `web/src/views/SchemeDetailView.vue`（改） | 增加「设为模板」「套用此模板」按钮（按权限显隐） |

「从模板创建」交互：弹窗选择模板（标签筛选）→ 预览模板商品与当前价对比 → 确认生成新方案并跳转。

#### 4.2.4 测试

- `ProjectServiceTest`：CRUD、所有权 403、ADMIN 绕过、删除后方案 project_id 置空
- `SchemeServiceTest`（扩）：copyFromTemplate（价格刷新、priceChanges 正确、模板自身不被修改）、setTemplate 权限
- `ProjectControllerTest`：权限矩阵

---

### 阶段 3：报价单订单化 Order（约 2.5 天）

#### 4.3.1 DDL（`database/V5__order_module.sql`）

```sql
CREATE TABLE design_order (
    order_id              VARCHAR(40) PRIMARY KEY,        -- ORD-XXXXXXXX
    order_no              VARCHAR(32) NOT NULL UNIQUE,    -- DO-20260713-001
    project_id            VARCHAR(40) REFERENCES project(project_id),
    scheme_id             VARCHAR(32) REFERENCES scheme(scheme_id),
    receiver_name         VARCHAR(64),
    receiver_phone        VARCHAR(32),
    receiver_area         VARCHAR(128),
    receiver_address      VARCHAR(256),
    original_total_price  TEXT,                            -- AES 加密（与 factory_price 同策略）
    price_rate            NUMERIC(5,4) NOT NULL DEFAULT 1, -- 折扣率
    final_total_price     TEXT,                            -- AES 加密
    item_count            INT NOT NULL DEFAULT 0,
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    expected_lead_time    INT,                             -- 预计交期（天，取明细最大值）
    remark                VARCHAR(512),
    invite_token_hash     VARCHAR(128),                    -- 邀请 token 的 SHA-256（不存明文）
    invite_expire_at      TIMESTAMP,
    invite_confirmed_at   TIMESTAMP,
    created_by            UUID NOT NULL REFERENCES sys_user(user_id),
    deleted_at            TIMESTAMP,
    created_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_order_creator ON design_order(created_by) WHERE deleted_at IS NULL;
CREATE INDEX idx_order_status  ON design_order(status) WHERE deleted_at IS NULL;

CREATE TABLE design_order_item (
    id              BIGSERIAL PRIMARY KEY,
    order_id        VARCHAR(40) NOT NULL REFERENCES design_order(order_id),
    rspu_id         VARCHAR(40) NOT NULL,
    rsku_id         VARCHAR(40),
    variant_id      VARCHAR(40),
    product_name    VARCHAR(256),
    model           VARCHAR(128),
    image_id        VARCHAR(40),
    quantity        INT NOT NULL DEFAULT 1,
    original_price  TEXT,                    -- AES 加密单价快照
    final_price     TEXT,                    -- AES 加密到手单价快照
    factory_code    VARCHAR(16),             -- 供应商维度统计用
    snapshot_json   JSONB,                   -- 完整商品快照（规格/材质/颜色等）
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_order_item_order ON design_order_item(order_id);
CREATE INDEX idx_order_item_rspu  ON design_order_item(rspu_id);
CREATE INDEX idx_order_item_factory ON design_order_item(factory_code);
```

`price_rate` 全局配置：复用 `category_dict` 不合适，新增轻量 `sys_config` 表（key/value）或先放 `application.yml`（`rsdp.order.price-rate`，默认 1）。**推荐 `sys_config` 表**，便于管理端修改：

```sql
CREATE TABLE sys_config (
    config_key   VARCHAR(64) PRIMARY KEY,
    config_value TEXT,
    remark       VARCHAR(256),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW()
);
INSERT INTO sys_config VALUES ('order.price_rate', '1', '订单全局折扣率', NOW());
```

#### 4.3.2 状态机

| 当前状态 | 允许迁移 | 说明 |
|---|---|---|
| `PENDING` 待确认 | → `CONFIRMED` / `CANCELLED` | 客户或创建人确认 |
| `CONFIRMED` 已确认 | → `PRODUCING` / `CANCELLED` | |
| `PRODUCING` 生产中 | → `COMPLETED` | |
| `COMPLETED` 已完成 | 终态 | |
| `CANCELLED` 已取消 | 终态 | |

非法迁移抛业务异常（状态字典 `design_order_status` 同步入种子数据）。

#### 4.3.3 后端文件

| 文件 | 说明 |
|---|---|
| `entity/DesignOrder.java` / `DesignOrderItem.java` / `SysConfig.java` | 价格字段用 `EncryptTypeHandler` |
| `mapper/DesignOrderMapper.java` / `DesignOrderItemMapper.java` / `SysConfigMapper.java` | |
| `service/OrderService.java` | 生成订单（事务：锁 scheme_item → 查当前 RSKU → 组装快照 → 计算总价 × price_rate）、状态迁移校验、邀请 token 签发/校验（HMAC-SHA256，`rsdp.order.invite-secret` 配置）、统计 |
| `service/OrderNoGenerator.java` | `DO-yyyyMMdd-` + 当日序号（DB 计数器表或 `SELECT COUNT` 兜底，并发用唯一索引重试） |
| `controller/OrderController.java` | 见下接口 |
| `controller/PublicOrderController.java` | 公开邀请接口 |
| `service/ContractTemplateService.java` | POI 生成合同 docx 模板（复用现有 POI 依赖） |
| `dto/request/OrderCreateRequest.java` 等 | `{schemeId, projectId?, receiver*, remark?}` |

**接口**：

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/v1/orders` | `order:create` | 由方案生成订单（价格快照落库） |
| GET | `/api/v1/orders?status=&page=&size=` | `order:read` | 订单列表，响应含各状态计数（tabs） |
| GET | `/api/v1/orders/{id}` | `order:read` + 归属 | 详情（明细快照） |
| PUT | `/api/v1/orders/{id}` | `order:update` + 归属 | 仅 `PENDING` 可改收件信息/备注 |
| PUT | `/api/v1/orders/{id}/status` | `order:update` + 归属 | 状态迁移（状态机校验） |
| GET | `/api/v1/orders/contract-template` | `order:read` | 下载合同 docx 模板 |
| POST | `/api/v1/orders/{id}/invite` | `order:update` + 归属 | 生成邀请链接（token 明文仅本次返回，库内只存 hash；默认 7 天过期） |
| GET | `/api/v1/public/orders/invite/{token}` | 公开 | 查看邀请订单（仅展示商品名/图/数量/**到手价**，不含出厂价与工厂信息） |
| POST | `/api/v1/public/orders/invite/{token}/confirm` | 公开 | 客户确认 → `CONFIRMED`，记录 `invite_confirmed_at`，token 失效 |
| GET | `/api/v1/configs/{key}` / PUT | `order:read` / ADMIN | price_rate 读写 |

`SecurityConfig`：放行 `/api/v1/public/**`（permitAll），其余 `order:*` 权限常量（`order:read/create/update/delete`），ADMIN + DESIGNER 授予。

**安全要点**：
- token = `orderId + expireAt + nonce` 的 HMAC-SHA256 签名串（Base64URL），库内只存 hash；校验时比对签名、过期时间、`invite_confirmed_at IS NULL`
- 公开接口只返回到手价（`final_price`），**绝不返回 `original_price` / `factory_code`**
- 公开接口加限流预留（先记日志，后续接 Redis 计数器）

#### 4.3.4 前端文件

| 文件 | 说明 |
|---|---|
| `web/src/types/order.ts` / `api/order.ts` | 类型与接口 |
| `web/src/views/OrderListView.vue` | `/orders`：状态 Tab（带计数）+ 表格（订单号/项目/收件人/件数/原价/到手价/状态/创建时间/操作） |
| `web/src/views/OrderDetailView.vue` | `/orders/:id`：商品明细快照表（图/型号/原价/到手价/数量/小计）、收件信息、状态时间线、合同下载、取消订单、生成/复制邀请链接 |
| `web/src/views/InviteOrderView.vue` | `/invite/:token`（`meta: { public: true }` 免登录）：商品与到手价、确认按钮、过期/已确认提示 |
| `SchemeDetailView.vue` / `QuoteBuilderView.vue`（改） | 增加「转为订单」按钮（弹窗填收件信息） |
| `router/index.ts`（改） | 新路由 + 公开路由放行逻辑 |

#### 4.3.5 测试

- `OrderServiceTest`：快照金额计算（×price_rate）、明细快照完整性、状态机合法/非法迁移、`PENDING` 外修改拒绝
- `OrderInviteTest`：token 伪造拒绝、过期拒绝、重复确认拒绝、确认后状态正确
- `PublicOrderControllerTest`：响应不含出厂价/工厂字段（JSON 字段断言）
- `OrderNoGeneratorTest`：并发生成不重复

---

### 阶段 4：订单统计 + 企业团队（可选，约 1 天）

#### 4.4.1 订单统计

接口：`GET /api/v1/orders/statistics?dim=product|factory&from=&to=`（`order:read`，ADMIN 看全量，其余看自己）

- `dim=product`：按 `design_order_item.rspu_id` 聚合 → 总件数、总金额（到手价）、关联产品名/图
- `dim=factory`：按 `factory_code` 聚合 → 订单数、总件数、总金额，关联工厂名
- 时间范围按 `design_order.created_at` 过滤，排除 `CANCELLED`

前端 `OrderStatisticsView.vue`（`/orders/statistics`）：维度切换 + 日期范围 + 聚合表格 + 简易柱状图（Naive UI 无图表组件，用表格 + 进度条式占比即可，不引入图表库）。

#### 4.4.2 企业团队（轻量版）

- `ALTER TABLE sys_user ADD COLUMN company_name VARCHAR(128), ADD COLUMN group_name VARCHAR(64);`
- 用户管理页：列表增加企业/分组列与筛选；编辑表单增加两字段
- 项目创建时 `company_name` 默认取当前用户企业

---

## 第 5 章 数据库迁移与兼容性

| 脚本 | 内容 |
|---|---|
| `database/V4__project_module.sql` | `user_favorite`、`project`、`scheme` 三列扩展、`project_type`/`design_order_status` 字典、`project:*` 权限种子 |
| `database/V5__order_module.sql` | `design_order`、`design_order_item`、`sys_config`、`order:*` 权限种子 |
| `database/reset_db.sql` | 同步新增 DROP 语句 |
| `database/V1__init_db.sql` / `seed_data.sql` | 同步合并（保持新环境一键初始化） |

**兼容性**：
- `scheme` 新列均可空/有默认值，存量方案正常显示（无项目归属、非模板）
- 存量 RSPU/RSKU 结构零改动
- 订单价格为独立快照，不影响现有报价单逻辑

---

## 第 6 章 风险与边界

1. **邀请下单安全**：token HMAC 签名 + 过期 + 单次确认失效；公开页只读到手价；出厂价/工厂信息绝不外泄（出厂价是 AES 加密敏感字段）。
2. **订单价格快照不可变**：订单生成后 RSKU 涨价/降价不影响已下单金额；与现有方案 `priceChanges` 提示逻辑一致。
3. **并发**：订单号生成依赖唯一索引 + 重试；收藏依赖唯一索引。
4. **阶段 4 可暂缓**：统计与企业团队不影响主闭环。
5. **每阶段独立可回滚**：DDL 只增不删；前端路由新增不破坏旧路径。
6. **明确不做**：切换 Element Plus；引入 yudao 多租户/member/mall/pay；rooom 的 DIY 装修、积分/余额/分销体系。

---

## 第 7 章 里程碑与工作量估计

| 阶段 | 内容 | 估计 | 交付检查点 |
|---|---|---|---|
| 0 | 设计系统 + 导航重构 | 0.5 天 | 全页面回归通过 |
| 1 | 品牌首页 + 收藏夹 + 产品库筛选 | 1.5 天 | 收藏全链路可用 |
| 2 | 设计项目 + 方案模板化 | 2 天 | 项目画布闭环：模板→方案 |
| 3 | 报价单订单化 + 邀请确认 | 2.5 天 | 方案→订单→邀请确认闭环 |
| 4 | 订单统计 + 企业团队（可选） | 1 天 | 统计两维度可用 |

**每阶段强制要求**：
- 后端 `mvn test` 全量通过（新增 Service 必须有单元测试，外部调用用 WireMock/Mockito）
- 前端 `pnpm lint` + `pnpm type-check` + `pnpm build` 通过
- 更新 `docs/02-architecture/04-API设计.md` 与 `docs/05-status/当前进度.md`
- 涉及权限的新接口同步更新 `docs/06-reference/03-角色权限矩阵.md`

**总体验收标准**（阶段 3 完成后）：录入产品 → 产品库收藏 → 创建项目 → 从模板/AI/手动建方案 → 生成报价单 → 转为订单 → 邀请链接客户确认 → 订单状态流转，全链路可走通。
