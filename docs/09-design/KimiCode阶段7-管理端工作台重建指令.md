# 阶段 7（补丁）· 管理端首页重建为"产品数字化工作台"

> 使用方法：先把设计稿 `admin.html`（style-b 增强版工作台，即我给你的 admin(2).html）复制到仓库 `docs/09-design/admin-workbench.html`，然后把下面整段指令粘贴给 Kimi Code。

---

## 粘贴给 Kimi Code 的指令（开始）

阅读以下文件后再动手：
1. `docs/09-design/admin-workbench.html` —— 工作台目标设计稿（结构以此为唯一标准）
2. `docs/09-design/RSDP管理端工作台设计文档.md` —— tokens 与色彩铁律
3. `web/src/views/HomeView.vue` —— 当前首页（门户形态，本次要重建）
4. `web/src/api/dashboard.ts` —— 统计带 API（已就绪）
5. `web/src/components/StatusPill.vue` 和 `web/src/styles/tokens.css`

### 任务：把 `web/src/views/HomeView.vue` 从"门户首页"重建为"产品数字化工作台"

**结构必须严格对齐 admin-workbench.html，自上而下：**

1. **页头 hero**：mono 小标签 `DASHBOARD / OVERVIEW`（letter-spacing 3px）+ 30px 粗标题「产品数字化工作台」+ 一行副标题（关键数字用 `--rsdp-terra` 赭石加粗）+ 右侧两个按钮（主按钮墨底白字「批量导入」，次按钮白底描边「新建报价单」，跳转现有路由）
2. **统计带 stats**：5 格通栏分隔线式（沿用现有 stats-band 代码），数据源 `getDashboardSummary()`。改为**常驻渲染**：接口失败时数字显示 `--` 而不是整段隐藏
3. **左列（1.75fr）三个区块：**
   - 「产品库 · 最新入库」：grid4 产品卡（图 4:3、名称、mono 价格、mono 编码行、虚线分隔的 chips 行）。chips 规则：`报价×N` 用 terra 底（`--terra-bg`/`--terra`），缺场景图用 bad 底（`--bad-bg`/`--bad`）。数据取现有产品列表 API 最新 4 条，无数据显示空态文案
   - 「识别任务队列」：表格（任务/类型/提交人/状态/耗时/操作），状态列复用 `StatusPill.vue`（已确认=ok 绿系、待复核=terra 赭系、存疑=bad 红棕系、进行中=info 灰系）。数据取现有导入批次 API 最近 5 条，无数据显示空态
   - 「户型图分析 · 待复核队列」：行式列表（缩略图 40×30 + mono 编号 FPA-* + 房间数/尺寸 + StatusPill），右上「进入 AI 工作台 →」跳 `/matching/anchor`。数据取现有户型分析 API 待复核 3 条，无数据显示空态
4. **右栏（1fr）四个面板：**
   - 「今日待办」：任务行（8px 圆点：赭=待处理、绿=已完成、红棕=阻塞、灰=一般 + 文案 + mono 时间）
   - 「最新留资线索」：行式列表（姓名/来源/意向/StatusPill 状态），头部「全部线索 →」跳 `/leads`。数据取留资 API 最新 3 条
   - 「AI 搭配数据完备度」：3 条 meter（6px 高、墨色填充、低于 60% 用赭色），mono 百分比
   - 「快捷操作」：2×2 按钮格（批量导入/新建报价单/产品库/留资线索）
5. **移除**现有门户区块：banner/hero 营销区、新品上架、落地案例、产品定制、按维度找产品、五步完成产品数字化、快捷入口（这些已由官网 website/ 承担，管理端首页不再保留）

### 硬性约束（违反即返工）
- 所有颜色/字体/圆角只用 `tokens.css` 变量；**禁止出现 `#2453fc`、`#ff0000`**；不新建任何样式入口文件
- 数字、编码、价格、时间一律 `font-family: var(--rsdp-font-mono)`
- 赭石 `#C2622B` 只用于待处理/促销语义；状态色只能走 StatusPill 三色体系
- 每个区块独立 try/catch：接口失败显示该区块空态，**整页不许白屏、不许整块消失**（统计带显示 `--`）
- 不改动路由守卫与权限逻辑；统计带保持仅 ADMIN/EDITOR 可见，其余区块按现有权限 meta 控制
- 完成后必须 `pnpm build` 通过 + 现有 950 测试不红

### 验收标准
无后端启动前端时：页头/统计带（`--`）/三个左列区块空态/四个右栏面板全部可见，布局与设计稿一致；有后端时各区块填真实数据。

## 粘贴给 Kimi Code 的指令（结束）

---

## 完成后怎么验收

1. `cd web && pnpm dev`，直接访问 `http://localhost:5173/`（登录后首页）
2. 对照 `docs/09-design/admin-workbench.html` 逐区块比对：页头 → 统计带 → 左列三区块 → 右栏四面板
3. 起后端（`mvn spring-boot:run`）再刷新，统计带从 `--` 变真实数字、产品卡和队列出现数据
4. 把首页截图发我，我做最终比对验收
