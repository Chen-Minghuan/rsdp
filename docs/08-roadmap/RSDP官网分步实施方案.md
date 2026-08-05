# RSDP「宜家式官网」分步实施方案（可执行版）

> 版本：v1.0 ｜ 日期：2026-08-05
> 前置阅读：《宜家官网构成与设计解析.md》《RSDP官网实现方案计划书.md》
> 使用方式：按阶段 0→5 顺序执行。每个阶段包含【目标】【分步操作】【验收标准】【Kimi Code 指令】四部分。所有文件路径均基于仓库真实结构（main 分支 2026-08）。

---

## 阶段 0：前置准备（0.5 天）

### 目标
确认本地环境可跑通即可。商品数据允许为空，官网先以空数据状态开发；P0 缺陷（Excel 导入）延后处理，不阻塞官网开发。

### 分步操作

**0.1 确认本地环境可跑**
```powershell
make infra        # postgres/chromadb/redis/minio
make init-db      # V1~V30 迁移 + 种子
make seed-style   # 风格知识库
make backend      # :8081
make frontend     # :5173
```
- 验收：登录 admin 后台，产品库有数据、首页 `/` 营销区可见 Banner

**0.2 商品数据摸底（仅了解情况，不要求补数据）**
在 postgres 里执行：
```sql
-- 可用于官网展示的商品量（有零售参考价 + 有主图 + 审核通过）
SELECT count(*) FROM rspu_master r
WHERE r.review_status = 'APPROVED' AND r.status = 'ACTIVE'
  AND r.retail_price IS NOT NULL
  AND EXISTS (SELECT 1 FROM image_assets i WHERE i.rspu_id = r.id AND i.is_primary = true);

-- 场景图数量
SELECT count(*) FROM image_assets WHERE image_type='scene';
```
- 结果只作参考，**数据为空也照常开工**——前端各页面需做好空状态设计（空列表提示 + 引导图），后续商品录满后自然呈现

---

## 阶段 1：后端公开只读 API 层（3~4 天）

### 目标
新增 `/api/v1/public/**` 下的商品/风格/场景/线索接口，**零改动现有领域模型与表结构**（仅 +1 张线索表），出厂价与工厂数据物理隔离。

### 分步操作

**1.1 新建迁移脚本 `database/V31__platform_lead.sql`**
```sql
CREATE TABLE platform_lead (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(64) NOT NULL,
  phone VARCHAR(32) NOT NULL,
  space_type VARCHAR(32),          -- 复用场景字典码
  style_code VARCHAR(32),          -- 复用风格字典码
  budget_range VARCHAR(32),
  message TEXT,
  source_page VARCHAR(128),        -- 来源页面，转化追踪
  status VARCHAR(16) NOT NULL DEFAULT 'NEW',  -- NEW/CONTACTED/DONE
  created_at TIMESTAMP NOT NULL DEFAULT now(),
  updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_lead_status ON platform_lead(status, created_at DESC);
```

**1.2 新建公开 DTO（脱敏的关键防线）**
位置：`server/src/main/java/com/rsdp/dto/response/`
- `PublicProductListVO`：rspuCode、productName、categoryCode/Name、primaryStyleCode/Name、retailPrice、priceDisplay（见 1.6 价格策略）、primaryImageId、sceneImageId、colorPrimary、keySpecs、dimTags（六维展示名）
- `PublicProductDetailVO`：以上 + description、variants[]（variantCode/sizeText/dimensions{w,d,h}/colorText/materialText/fabricText/images[]）、images[]（按 white_bg/scene/detail 分组）、relatedProducts[]（来自 `rspu_relation`，type=official/ai_verified）
- `PublicStyleVO`：styleCode、styleName、百科 JSON 摘要（取自 `style_case.ai_raw_output`：color_palette/materials/lighting/design_principles/typical_furniture）
- `PublicSceneVO`、`LeadCreateRequest`（含 phone 格式校验注解）
- **红线**：VO 中禁止出现 factory*、reviewStatus、status 内部码、styleVector、任何 RSKU 字段

**1.3 新建 `PublicProductController`（`/api/v1/public/products`）**
位置：`server/src/main/java/com/rsdp/controller/`
```
GET /api/v1/public/products
    参数：category, style, scene, dimA~dimF（六维字典码，复用管理端 JSONB 包含查询逻辑）,
         keyword（品名模糊）, sort=price_asc|price_desc|newest, page, size(≤48)
    硬过滤：review_status='APPROVED' AND status='ACTIVE'
GET /api/v1/public/products/{rspuCode}
    硬过滤同上；不存在 → 404
GET /api/v1/public/products/categories   # 品类树（category_dict 中 dict_type=品类，按 code/path 组树）
GET /api/v1/public/products/filters?category=xx  # 该品类下可用筛选项+计数（风格/场景/六维/价格带）
```
实现要点：注入现有 `ProductService`/对应 Mapper，复用管理端列表查询的六维 JSONB 筛选 SQL（管理端 `GET /api/v1/products` 已实现 dimA~dimF 筛选，照搬 WHERE 构造），但投影到公开 VO。

**1.4 新建风格/场景聚合接口**
```
GET /api/v1/public/styles            # 17 风格字典 + 每风格商品计数
GET /api/v1/public/styles/{styleCode} # 风格百科（style_case）+ 该风格商品分页
GET /api/v1/public/scenes            # 场景列表 + 代表场景图
GET /api/v1/public/scenes/{sceneCode} # 场景下商品分页 + scene 图集
```

**1.5 新建线索接口 `PublicLeadController`**
```
POST /api/v1/public/leads   # 提交线索：参数校验 + 简单防刷（同 IP 1 分钟 1 次，用 Redis 现有连接）
GET /api/v1/platform/leads  # 管理端查询（权限码 platform:manage，沿用 PlatformCmsController 的权限模式）
PUT /api/v1/platform/leads/{id}/status
```

**1.6 SecurityConfig 放行**
位置：`security/SecurityConfig.java`（现有 permitAll 清单处）
- 在现有 `/api/v1/public/**` matcher 下确认新路径被覆盖（现有模式是整前缀放行，新增 controller 落在该前缀下即自动放行——验证一次即可）
- 价格策略：retail_price 为空时 `priceDisplay="咨询获取"`；有值时直接展示（如 `¥1,299`）。**绝不允许**从 rsku_supply 取数

**1.7 安全回归测试（强制）**
新增测试断言：随机抽 10 个公开接口响应体，断言 JSON 中不含 `factory`、`factoryPrice`、`rsku`、`reviewStatus` 字段名。这是防止脱敏失守的自动化保险。

### 验收标准
- [ ] 未登录浏览器直接访问 `/api/v1/public/products?page=1` 返回脱敏商品分页
- [ ] 六维/风格/场景组合筛选结果与管理端同条件查询一致（抽查 3 组）
- [ ] 响应体无敏感字段（自动化测试通过）
- [ ] POST 线索成功入库，管理端可查到
- [ ] 现有 808 个后端测试保持全绿 + 新增测试通过

### Kimi Code 指令（阶段 1）
> 「在 server 模块新增官网公开只读 API。要求：① 新建 database/V31__platform_lead.sql（结构见方案文档）；② 新建 PublicProductController（/api/v1/public/products 列表+详情+categories+filters）、公开风格/场景聚合接口、PublicLeadController（POST /api/v1/public/leads），全部使用专用脱敏 VO，VO 中禁止出现 factory/价格密文/reviewStatus/vector 等内部字段，列表与详情硬过滤 review_status='APPROVED' AND status='ACTIVE'；③ 六维 JSONB 筛选逻辑复用现有 ProductController 管理端列表的实现；④ 不改动 RSPU/RSKU 表结构、编码体系与任何现有接口行为；⑤ 新增测试：公开接口响应体断言不含 factory/factoryPrice/rsku/reviewStatus 字段，且现有测试全绿。」

---

## 阶段 2：官网前端工程搭建（4~5 天）

### 目标
独立 Nuxt 3 SSR 站点，设计规范落地，完成全站布局 + 首页。

### 分步操作

**2.1 建工程（仓库内新目录，与 web/ 平级）**
```powershell
cd E:\roomvip
pnpm dlx nuxi@latest init website
cd website
pnpm add @pinia/nuxt @nuxtjs/tailwindcss dayjs
```
- 目录约定：`website/components/`（Site*/Product*/Style* 前缀）、`website/pages/`、`website/composables/useApi.ts`、`website/assets/css/tokens.css`
- `nuxt.config.ts` 关键配置：
  - `runtimeConfig.public.apiBase = 'https://你的域名/api/v1'`
  - ssr: true；routeRules 配置 ISR：`'/': { isr: 300 }`、`'/product/**': { isr: 600 }`、`'/products': { ssr: true }`

**2.2 设计 tokens（`tokens.css`，对标宜家的克制基调 + 你的品牌色）**
```css
:root {
  --brand: #8B5E3C;        /* 主色：暖木棕（按你展厅/Logo 实际色调整） */
  --brand-dark: #6B4630;
  --ink: #1a1a1a;          /* 近黑正文 */
  --ink-2: #555;
  --bg: #ffffff;
  --bg-soft: #f5f4f2;      /* 浅灰暖区块 */
  --line: #e8e6e3;
  --price: #c0392b;        /* 价格强调 */
  --radius: 8px;
  --max-w: 1280px;         /* 内容最大宽 */
}
```
- 字体栈：`-apple-system, "PingFang SC", "Microsoft YaHei", "Noto Sans SC", sans-serif`；价格数字加 `font-variant-numeric: tabular-nums`
- 栅格：12 列、max-w 1280、间距阶梯 4/8/16/24/32/64

**2.3 布局组件（第一天先把骨架立起来）**
- `SiteHeader`：Logo + 主导航（商品/空间/风格/灵感案例/关于我们）+ 搜索框 + "预约设计咨询"CTA 按钮；滚动时阴影
- `MegaMenu`：点击"商品"展开全屏面板——左列品类树（`/public/products/categories`），右列风格/空间快捷入口；对标宜家全屏菜单
- `SiteFooter`：四栏（购物指南/服务支持/关于我们/联系方式）+ 备案位 + 社交媒体图标
- `FloatConsult`：右下悬浮"设计咨询"按钮（对标宜家悬浮客服），点击开 `LeadFormModal`
- `LeadFormModal`：姓名/电话/空间下拉（场景字典）/意向风格下拉/预算/留言 → POST `/public/leads`

**2.4 首页 `pages/index.vue`（对接现有 `/api/v1/public/home`）**
模块顺序（对照宜家首页裁剪）：
1. `HeroBanner`：platform_banner 轮播（已有接口数据）
2. "按空间探索"：场景卡片网格（`/public/scenes`）
3. "按风格找灵感"：17 风格入口横向滚动卡（`/public/styles`）
4. "精选商品"：运营位（M1 阶段可先用 newest 排序接口兜底，后续可在 platform_content 加配置）
5. 案例区：`platform_case` 卡片流（已有数据）
6. 服务承诺条：送货/安装/设计咨询/售后 四卡（静态内容块）
7. 定制服务入口：platform_customized（已有数据）
8. CTA 区："免费获取全屋搭配方案" → LeadFormModal

**2.5 图片处理**
- `composables/useImage.ts`：`imgUrl(id, w)` → `/api/v1/images/{id}?w=400|800|1600`（若现有 ImageController 不支持尺寸参数，M2 期间在后端加一个 `w` 缩放参数——现有 ImageResizer 工具类现成）
- 所有列表图 `loading="lazy"` + 宽高占位防 CLS

### 验收标准
- [ ] `pnpm dev` 起站，首页 SSR 直出（查看网页源码含完整 HTML，非空壳 div）
- [ ] Mega Menu/页脚/悬浮咨询齐全，移动端折叠为汉堡菜单
- [ ] 首页各运营位均渲染真实接口数据

### Kimi Code 指令（阶段 2）
> 「在仓库根新建 website/ Nuxt 3 SSR 工程（pnpm + Tailwind），实现：① tokens.css 设计变量（色值见方案）；② SiteHeader/MegaMenu/SiteFooter/FloatConsult/LeadFormModal 布局组件，MegaMenu 数据来自 GET /api/v1/public/products/categories；③ 首页按方案 2.4 的 8 个模块对接现有 /api/v1/public/home 与公开接口；④ 图片走 /api/v1/images/{id}，懒加载+占位；⑤ 响应式（1280/768/375 三档）。风格参考宜家官网：白底、浅灰区块、大留白、线性图标。」

---

## 阶段 3：商品浏览闭环（5~6 天）

### 目标
分类导航 → PLP（筛选/排序）→ PDP（画廊/变体/搭配）完整可逛，这是官网的核心。

### 分步操作

**3.1 分类落地页 `pages/category/[code].vue`**
- 面包屑（按 category path 逐级）+ 子类目卡片网格 + 该品类精选商品 + 文案块（静态或 platform_content）
- URL：`/category/{categoryCode}`；面包屑层级从字典 path 解析

**3.2 商品列表 PLP `pages/products.vue`**
- 布局：左侧筛选栏（品类/风格/场景/六维动态筛选项，来自 `/public/products/filters`）+ 右侧商品网格（4 列/2 列移动）
- 功能清单（逐项对照宜家 PLP）：
  - [ ] 商品计数（"共 N 件商品"）
  - [ ] 排序：综合/价格升/价格降/最新
  - [ ] 筛选联动：选中条件生成标签条，可单个移除；URL query 同步（可分享、SSR 直出）
  - [ ] **视图切换：场景图 / 白底图**（ProductCard 支持双图，hover 也可切换——宜家同款体验）
  - [ ] 分页（或"加载更多"，推荐分页利于 SEO）
- `ProductCard`：场景主图 + 白底 hover 图 + 风格 Badge + 品名 + 规格简述 + priceDisplay + "查看详情"

**3.3 商品详情 PDP `pages/product/[rspuCode].vue`**
模块（对照宜家 PDP 裁剪）：
- 面包屑
- 左：`ProductGallery`（场景图/白底图/细节图分组，缩略图 + 放大）
- 右购买盒：品名 + 风格标签 + 规格描述 + priceDisplay + `VariantSelector`（尺寸×颜色×面料，数据来自变体数组）+ "预约咨询/获取报价"主 CTA（开 LeadFormModal 并自动带入 source_page 与当前商品）
- 服务承诺条：正品保障/送货安装/设计服务/售后无忧
- Tab：商品详情（keySpecs + 尺寸表 + 材质面料说明）｜ 六维档案（把六维标签做成可视化卖点卡片——**你的差异化，宜家没有**）｜ 搭配推荐（rspu_relation 商品卡横滑）
- SEO：`<title>` 品名+品牌、`useSeoMeta` og 标签、JSON-LD Product 结构化数据

**3.4 搜索页 `pages/search.vue`**
- 对接 `/public/products?keyword=`；搜索框联想可先不做（P2）

### 验收标准
- [ ] 从首页 → 分类 → PLP → PDP 全链路无登录可访问
- [ ] 商品数据为空时，PLP/首页/场景页显示正常空状态（提示文案 + 占位图），不报错、不白屏
- [ ] 筛选/排序/视图切换/分页 URL 可分享且刷新后状态保持
- [ ] PDP 变体切换正确更新规格与图片；无零售价的商品显示"咨询获取"
- [ ] Lighthouse SEO ≥ 90，LCP < 2.5s（首页与 PLP）

### Kimi Code 指令（阶段 3）
> 「在 website/ 实现商品浏览三页：① /category/[code] 分类落地页（面包屑+子类目卡+精选商品）；② /products PLP——左侧动态筛选（品类/风格/场景/六维，来自 /public/products/filters）、价格排序、商品计数、场景图/白底图视图切换、分页、筛选条件 URL query 同步；③ /product/[rspuCode] PDP——图片画廊分组、变体选择器（尺寸×颜色×面料）、零售参考价展示（空值显示"咨询获取"）、商品详情/六维档案/搭配推荐三个 Tab（搭配数据来自详情的 relatedProducts）、og 标签与 JSON-LD Product 结构化数据。全部 SSR，免登录。」

---

## 阶段 4：内容与灵感页（3~4 天）

### 目标
把风格百科与场景资产变成内容页——这是你区别于普通家具官网的差异化内容。

**4.1 风格专题**
- `pages/styles/index.vue`：17 风格卡片墙（每风格一张代表场景图 + 名称 + 一句话定位）
- `pages/styles/[code].vue`：风格百科页，渲染 `style_case.ai_raw_output`：
  - 色彩体系（色板可视化）/ 材质与肌理 / 形态特征 / 灯光氛围 / 设计原则 / 典型家具元素
  - 底部："该风格的商品"（`/public/styles/{code}` 接口分页）
- ⚠️ 前置：风格库目前**仅覆盖客厅**，本阶段开工前先补种子：
  ```powershell
  # 参照 scripts/generate_style_knowledge_seed.js 的生成方式，
  # 在 data/style-knowledge/raw/ 下为核心风格补卧室/餐厅场景 JSON，再跑 make seed-style
  ```

**4.2 场景灵感页**
- `pages/scenes/index.vue`：场景网格（客厅/卧室/餐厅/书房…，各配代表场景图）
- `pages/scenes/[code].vue`：该场景的场景图集（image_type=scene）+ 场景内商品流（点图进 PDP）

**4.3 静态内容页**
- `pages/about.vue`（品牌/工厂实力，可展示 S/A/B/C 分级供应链能力的对外表述版）、`pages/service.vue`（送货/安装/定制/售后）——内容走 `platform_content`，管理端 CMS 可改

### 验收标准
- [ ] 至少 5 个核心风格专题页内容完整（色系/材质/灯光/原则四块不缺）
- [ ] 场景页图集与商品流正确关联
- [ ] 内容页文字在管理端 CMS 修改后前台可见（验证 CMS 链路）

### Kimi Code 指令（阶段 4）
> 「在 website/ 实现：① /styles 风格卡片墙与 /styles/[code] 风格百科页——把接口返回的 ai_raw_output JSON（color_palette/materials/forms/textures/lighting/design_principles/typical_furniture）渲染成杂志式内容页，色板做可视化色块；② /scenes 场景网格与 /scenes/[code] 场景页（场景图集+商品流）；③ /about 与 /service 静态页，内容从 /api/v1/public/content/{code} 读取。」

---

## 阶段 5：转化、SEO 与上线（3~4 天）

**5.1 线索闭环**
- 全站 CTA 统一收口到 LeadFormModal；PDP/场景/风格页自动带 source_page
- 管理端 `admin/platform/` 新增"线索管理"Tab（列表/状态流转 NEW→CONTACTED→DONE/按来源统计）
- 线索提交成功页 + 微信二维码（人工承接）

**5.2 SEO 收尾**
- `@nuxtjs/sitemap`：sitemap 动态包含全部 PDP/PLP/风格/场景 URL
- robots.txt：放行页面、屏蔽 `/api/`
- 每页唯一 title/description；PDP JSON-LD
- ICP 备案号上页脚（国内服务器必需）

**5.3 部署集成**
- `website/Dockerfile`（node:20-alpine，pnpm build → node .output/server/index.mjs）
- `deploy/docker-compose.yml` 加 `website` 服务（:3000）
- `deploy/nginx/nginx.conf` 加 server 块：
  ```
  www.你的域名  → website:3000
  admin.你的域名 → web:80（现有管理端 SPA）
  /api          → server:8081（两个域名共用）
  ```
- HTTPS：正式证书（Let's Encrypt 或云厂商免费证书，替换现有自签名）

**5.4 上线检查单**
- [ ] 全站免登录可逛；管理端登录/权限不受影响（回归）
- [ ] 公开接口无敏感字段（跑阶段 1 的安全测试）
- [ ] 线索提交 → 管理端可见 → 状态流转正常
- [ ] sitemap 可访问；百度/Google Search Console 提交
- [ ] 备份脚本 `scripts/backup_db.sh` 纳入上线前例行

### Kimi Code 指令（阶段 5）
> 「① website/ 接入 @nuxtjs/sitemap 与 robots，PDP 输出 JSON-LD Product，全站页面唯一 title/description，页脚加备案位；② 管理端 admin/platform 新增"线索管理"Tab（对接 /api/v1/platform/leads，状态流转 NEW/CONTACTED/DONE，可按 source_page 筛选统计）；③ 编写 website/Dockerfile 并在 deploy/docker-compose.yml 与 nginx.conf 中集成（www 域名→website:3000，admin 域名→web:80，/api→server:8081），给出完整改动 diff。」

---

## 总览

| 阶段 | 内容 | 工期 | 关键产出 |
|---|---|---|---|
| 0 | 环境确认、数据摸底 | 0.5 天 | 可跑通的本地环境（数据可为空） |
| 1 | 公开只读 API + 脱敏 + 线索表 | 3~4 天 | `/api/v1/public/products` 等 |
| 2 | Nuxt 工程 + 设计规范 + 首页 | 4~5 天 | 官网骨架 |
| 3 | 分类/PLP/PDP 商品闭环 | 5~6 天 | 核心浏览体验 |
| 4 | 风格/场景/内容页 | 3~4 天 | 差异化内容 |
| 5 | 线索闭环 + SEO + 部署上线 | 3~4 天 | 正式上线 |
| **合计** | | **约 19~24 个工作日** | |

**三条铁律（贯穿全程）：**
1. 不改 RSPU/RSKU 表结构与编码体系，不动现有 39 个 Controller 的行为
2. 公开接口只出专用脱敏 VO，安全断言测试每次构建必跑
3. 每个阶段验收通过再进下一阶段
