# RSDP 官网与管理端改造 · Kimi Code 实施指令手册

| 项目 | 内容 |
|---|---|
| 编写日期 | 2026-08-10 |
| 仓库基线 | 迁移 V33 · 101 测试类约 888 测试 · Excel 治理 P0 剩 6 项 · CMS 表（platform_banner/case/content/custom_dict）已就绪 |
| 总策略 | **先换肤（低风险快赢）→ 再补后端门面 → 最后建用户端新站**。全程组件业务逻辑零重写 |

---

## 一、文件放置：你已经放了 4 个，建议再补这些

> ⚠️ 仓库 docs/ 下现有 01~08 共 8 个文件夹（06 是 06-reference），**请新建 `docs/09-design/`** 再放文件，符合仓库的编号目录约定：
> ```bash
> mkdir -p docs/09-design/assets
> ```

### 已放（建议位置 `docs/09-design/`）

| 文件 | 作用 |
|---|---|
| `index.html` | 用户端首页视觉参照（Kimi Code 照它还原） |
| `admin.html` | 管理端工作台视觉参照 |
| `RSDP用户端官网设计文档.md` | 用户端规范（tokens/区块/组件/接口映射） |
| `RSDP管理端工作台设计文档.md` | 管理端规范 |

### 建议补充（共 3 类）

| 文件 | 放哪 | 为什么 |
|---|---|---|
| **`products.html`** | `docs/09-design/` | PLP 是"宜家排列"的精髓页，缺了它 Kimi Code 只能凭文字想象筛选条/双视图/对比开关，强烈建议补 |
| 原型素材图 5 张（hero-living/sofa/tea-table/tv-cabinet/chair .png） | `docs/09-design/assets/` | 让 index/products/admin.html 在你本地和 Kimi Code 预览时能显示出图（否则 HTML 里 `assets/xxx.png` 全是裂图）；**仅作设计参考，不进生产代码** |
| `户型图空间搭配链路完整方案.md`（之前给你的） | `docs/05-status/` 或 `docs/09-design/` | 阶段 5 的 AI 户型搭配入口要用到里面的 V34 DDL 与接口设计 |

### 不需要放进项目的

- screenshots/ 截图、zip 包、三份早期方案文档（《宜家官网构成与设计解析》《RSDP官网实现方案计划书》《RSDP官网分步实施方案》）——关键结论已全部合并进两份新设计文档，放了反而让 Kimi Code 读到过时信息（如 V31 编号）。

---

## 二、总顺序（6 个阶段，✅= 可并行）

```
阶段 0  基线确认与环境准备（0.5 天）
   ├─✅ 阶段 1  管理端换肤（1~2 天，纯前端，零风险）
   └─✅ 阶段 2  后端门面补全（2~3 天：V34 迁移 + public 接口）
        ↓（1、2 都完成后）
阶段 3  管理端新模块：留资线索页（1 天）
阶段 4  用户端 Nuxt 3 新工程：tokens + 组件 + 首页（3~4 天）
阶段 5  用户端 PLP + AI 搭配入口（2~3 天）
阶段 6  联调验收（1 天，按两份文档第十章 Checklist）
```

**为什么这个顺序**：
- 阶段 1 只改 tokens 和主题覆盖，不动任何业务逻辑，当天可见效果，建立信心；
- 阶段 2 的 V34 迁移（platform_lead）是后面留资、CTA、线索页的公共依赖，必须先于阶段 3/4；
- 阶段 1 和 2 互不依赖，可以让 Kimi Code 分两个会话并行做；
- 用户端新站（阶段 4/5）放最后，因为它依赖阶段 2 的 public 接口出数据。

---

## 三、逐阶段 Kimi Code 指令（复制即用）

### 阶段 0 · 基线确认

> 目的：让 Kimi Code 先读懂现状，避免它凭假设动手。

```text
先不要改任何代码。请完成以下确认并汇报：
1. 阅读 README.md、docs/05-status/当前进度.md、docs/09-design/ 下的 4 个设计文件；
2. 告诉我：database/migrations 下最新迁移编号、当前测试总数、web/ 工程的样式入口文件路径；
3. 跑一遍后端测试确认基线全绿，再跑一遍 web/ 的 build 确认无错。
确认无误后回复"基线 OK"，等待我下一条指令。
```

### 阶段 1 · 管理端换肤（预计 1~2 天）

```text
任务：管理端视觉换肤为 style-b「现代极简工作台」。
参照文件：docs/09-design/RSDP管理端工作台设计文档.md（重点读第二章 Tokens 与第七章换肤路径）、docs/09-design/admin.html（视觉目标）。

严格遵守：
1. 只改样式，不改任何业务逻辑、路由、接口调用；
2. 第一步改 web/src/styles/tokens.css：--rsdp-primary 从 #2453fc 改为 #1a1a1a；--rsdp-price 从 #ff0000 改为 #1a1a1a（等宽字体加粗）；--rsdp-bg 改为 #f7f7f7；--rsdp-text 改为 #1a1a1a；新增 --rsdp-font-mono: 'JetBrains Mono','Consolas',monospace；把 --rsdp-font-display 的 'Gloock' 换成 'Noto Serif SC'（中文字体栈修复）；
3. 第二步在 n-config-provider 加 themeOverrides：primaryColor #1A1A1A、warningColor #C2622B、successColor #5B7163、borderRadius 6px；
4. 第三步新建 web/src/components/StatusPill.vue：输入后端原始值（active/已确认/待复核/存疑）映射颜色——已完成/active/已确认=#5B7163 系，待复核=#C2622B 系，存疑=#A1402F 系。然后把全站散落的 status 展示处替换为它；
5. 全站搜索确认不再有 #2453fc 和 #ff0000 残留；
6. 完成后跑 build + 启动 dev，逐页截图核对（首页/产品库/方案/报价）。

验收标准：对照 docs/09-design/admin.html 的气质：灰白底、黑白为主、赭石只出现在"待处理"状态、编码价格全部等宽字体。
```

### 阶段 2 · 后端门面补全（预计 2~3 天，可与阶段 1 并行）

```text
任务：为用户端官网补全 public 门面接口 + platform_lead 表。

严格遵守仓库纪律（先读 RSDP管理端工作台设计文档.md 第六章确认映射）：
1. 新建迁移 V34__create_platform_lead.sql（注意：当前最新是 V33，所以是 V34，不是任何文档里可能出现的旧编号），并三处同步：新 V-file + V1__init_db.sql 追加 + reset_db.sql 追加。表结构：lead_id varchar(64) 主键、name、phone、source varchar(32)（ai_match/site_form/design_booking）、intent text、budget varchar(32)、status varchar(16) default 'pending'、assignee varchar(64)、follow_log jsonb、created_at/updated_at；
2. SecurityConfig 中确认 /api/v1/public/** 为白名单（沿用现有 anyRequest permitAll 模式，不要动已认证接口）；
3. 新增接口：
   - GET  /api/v1/public/products：分页 + 筛选（category/seat_count/color/material/price_min/price_max/sort），联查 rspu + rspu_variant + image_assets（is_primary 主图 + scene 场景图 URL）；
   - GET  /api/v1/public/scenes：scene_dict + 每个空间一张代表图；
   - GET  /api/v1/public/categories：category_dict 两级树（注意复合键 dict_type+dict_code，无 path 字段）；
   - POST /api/v1/public/leads：留资写入 platform_lead；
   - GET  /api/v1/dashboard/summary：管理端统计带聚合（RSPU 总数/RSKU 总数/识别通过率/本月订单额/今日留资数）；
4. 红线：任何 /api/v1/public/** 响应中绝不包含 RSKU 工厂报价字段（AES 加密列不解密出库）；
5. 状态值纪律：review_status 中文（已确认/待复核/存疑）、status 小写（active）；
6. 每个新控制器配集成测试，风格沿用现有 101 个测试类；全部测试必须绿。

完成后汇报：接口清单 + 测试结果 + curl 示例响应各一条。
```

### 阶段 3 · 管理端留资线索页（预计 1 天）

```text
任务：管理端新增「留资线索」页面。
参照 docs/09-design/admin.html 右栏「最新留资线索」的结构与 RSDP管理端工作台设计文档.md 第 4.3 节。
1. 路由 /leads，菜单挂在「用户端官网」分组下，带未处理数角标；
2. 表格列：客户（姓名+脱敏手机号）/ 来源（ai_match=AI 户型搭配、site_form=官网表单、design_booking=设计服务预约）/ 意向 / 预算 / 提交时间 / 状态（用 StatusPill）/ 跟进人 / 操作；
3. 操作：分配（NModal 选跟进人）、记跟进（追加 follow_log jsonb）、状态流转（pending→contacted→done）；
4. 页面顶部一个来源分布小统计（三来源各多少条）。
接口用阶段 2 建的 /api/v1/leads（管理端侧，需认证）。配前端路由测试。
```

### 阶段 4 · 用户端 Nuxt 3 新工程（预计 3~4 天）

```text
任务：在仓库根目录新建 website/（Nuxt 3 SSR 工程），实现用户端官网首页。
视觉与结构唯一参照：docs/09-design/index.html + RSDP用户端官网设计文档.md（第一/二/三/五章）。

1. 初始化 Nuxt 3 + TS，配置 SSR；新建 assets/css/tokens.css，变量与设计文档 2.1/2.2 完全一致（--bg:#FAF7F2、--accent:#8B5E3C、--accent-deep:#6F4A2E、--suppl:#F3EADF、--terra:#C2622B、--font-serif 以 'Noto Serif SC' 开头）；字体用 fontsource 或 CDN 引入 Noto Serif SC；
2. 按设计文档第五章实现基础组件：PriceText（价格三段式：¥小标+serif 特大整数+.00，sale 态暖杏底+赭石字+划线原价）、ProductCard、TrioCards、ServiceCards、RoomGrid、InspirationWall、CtaLead、SiteHeader（含三轴导航 Mega Menu）、SiteFooter；
3. 首页 11 个区块严格按 index.html 的顺序与排列还原，但数据全部走接口：Banner/区块内容 → /api/v1/public/home 与 /api/v1/public/content/{code}；房间探索 → /api/v1/public/scenes；新品 → /api/v1/public/products?sort=newest&size=4；
4. CTA 表单提交 → POST /api/v1/public/leads（source=site_form）；
5. 质量标准：首屏 SSR 直出、图片懒加载（Hero 图除外）、 Lighthouse 性能 ≥ 85；
6. 验收：与设计文档第十章 Checklist 逐条核对，特别是"全站无 #2453fc/#ff0000、中文衬线生效、赭石仅促销位置"。
```

### 阶段 5 · 用户端 PLP + AI 搭配入口（预计 2~3 天）

```text
任务：website/ 增加商品列表页（PLP）与 AI 户型搭配入口。
参照：docs/09-design/products.html（如果已放入）+ RSDP用户端官网设计文档.md 第四章；AI 链路参照 docs/05-status/户型图空间搭配链路完整方案.md。

1. 路由 /products：面包屑 + 类目头（计数）+ 吸顶筛选药丸条（价格排序/座位数/商品分类/颜色/尺寸/材质）+ 已选 chips + 4 列商品网格 + 加载更多；筛选条件与 URL query 双向同步；
2. 两个宜家同款交互：商品图/场景图双视图切换（用接口返回的 sceneImageUrl）；商品对比开关（最多 3 件，对比抽屉列尺寸/材质/价格/评分）；
3. AI 户型搭配：导航第 7 项 + 首页 Hero 主按钮进入 /ai-match：上传户型图 → 调用识别接口 → 展示空间尺寸 → 生成客厅搭配方案（复用现有 generateRoomScheme 能力）→ 方案页内嵌留资（source=ai_match）；
4. 验收：对照设计文档第十章 PLP 相关 Checklist。
```

### 阶段 6 · 联调验收（预计 1 天）

```text
任务：总验收。不改功能，只做核对与修复。
1. 逐条核对 docs/09-design/RSDP用户端官网设计文档.md 与 RSDP管理端工作台设计文档.md 的第十章/第八章 Checklist，输出逐项 ✅/❌ 表；
2. 跑全部后端测试 + 两个前端 build，确认全绿；
3. 检查迁移纪律：V34 三处文件内容一致；
4. 安全扫描：确认 public 接口响应无 RSKU 字段；
5. 输出验收报告（docs/05-status/官网改造验收.md），列出 ❌ 项的修复建议。
```

---

## 四、给 Kimi Code 的通用纪律（每条指令都可附上）

```text
通用纪律：
- 任何数据库变更必须三处同步（新 V-file + V1__init_db.sql + reset_db.sql）；
- review_status 用中文值、status 用小写英文，前后端一致；
- RSKU 工厂报价（AES 加密）不出现在任何 public 接口；
- 新代码必须带测试，全量测试保持全绿；
- 视觉实现以 docs/09-design/ 下的 HTML 原型为准，两份设计文档为规则解释，冲突时以 HTML 视觉为准。
```

---

## 五、常见问题预判

| 情况 | 处理 |
|---|---|
| Kimi Code 把迁移写成 V31/V32 | 阶段 2 指令里已强调"当前最新 V33→写 V34"，若仍写错，让它读 database/migrations 目录自省 |
| 换肤后个别页面样式崩 | 多半是硬编码颜色没走 tokens，让它全局搜 `#2453` / `#ff0000` / `2453fc` 残留 |
| 用户端要不要用 Naive UI | 不要。website/ 是面向消费者的站点，用原生组件 + tokens.css 即可，Naive UI 留给管理端 |
| 想做 PDP/房间页 | 不在本手册范围，设计文档第九章有规划，等阶段 6 验收后再开新周期 |
