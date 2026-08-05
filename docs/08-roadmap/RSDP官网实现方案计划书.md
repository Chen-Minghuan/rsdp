# RSDP 项目「宜家式官网」实现方案计划书

> 版本：v1.0 ｜ 日期：2026-08-05
> 依据：①《宜家官网构成与设计解析》（对 ikea.cn 的实测分析）；② 对 GitHub 仓库 Chen-Minghuan/rsdp（main 分支，2026-08 最新提交）的全量代码勘察
> 目标：在现有 RSDP 平台之上，新增一个**面向 C 端访客的宜家式官网**（商品浏览 + 场景灵感 + 风格内容 + 设计服务线索），**暂不含在线支付**，最大化复用现有数据与 AI 资产。

---

## 一、项目现状盘点（代码勘察结论）

### 1.1 当前形态
RSDP 已远超"AI 录入工具"定位，是一个**数据与 AI 能力高度成熟、B 端闭环已完成**的家具产品数据中台 + 设计师工作台：

- 后端：Spring Boot 3.4 / Java 21 / MyBatis-Plus，单体 Maven 工程，**39 个 Controller**，808 个后端测试全绿
- 前端：Vue 3.5 + TS + Vite 6 + **Naive UI** + Pinia，单 SPA，37+ 视图，含官网 CMS 后台（`admin/platform/`）
- 数据：PostgreSQL 约 60 张表（V1~V30 迁移）+ ChromaDB 向量库 + Redis + MinIO
- AI：DashScope `qwen3-vl-plus` 多模态识别 + `multimodal-embedding-v1` 向量 + 风格匹配公式库

### 1.2 核心资产：RSPU / 变体 / RSKU 三层商品模型
| 层 | 表 | 内容 | 对官网的价值 |
|---|---|---|---|
| RSPU（设计原型） | `rspu_master` | 品名、**零售参考价 retail_price（不加密）**、主风格、品类、六维标签 JSONB、颜色、材质、面料、场景、关键词规格 | 直接作为官网"商品" |
| 变体 | `rspu_variant` | 尺寸文本/三维 JSONB、颜色、材质、面料（工厂方言+归一码双存） | 直接作为官网"规格选择器"数据 |
| RSKU（供货单元） | `rsku_supply` | 工厂、**出厂价 AES 密文**、MOQ、交期 | **B 端敏感，官网必须脱敏隔离** |
| 图片 | `image_assets` | white_bg / factory_photo / detail / **scene** / original 五类，可挂 SPU/变体 | 直接支撑宜家式"商品图/场景图双图策略" |

### 1.3 已建一半的官网能力（重要发现）
- **官网 CMS 五表已建**：`platform_banner`、`platform_case`（案例富文本）、`platform_content`（内容块）、`platform_custom_dict`、`platform_customized`（定制入口卡）
- **公开接口网关模式已建立**：`/api/v1/public/home`（首页聚合：Banner+案例+定制，免登录）、`/api/v1/public/content/{code}`、`GET /api/v1/images/**` 已 permitAll
- **前端雏形**：`HomeView`（`/` 路由，`public:true`）已有 Banner 轮播/案例/定制营销区 + 按空间·风格·品类·材质分级导航
- 配套后台：`admin/platform/` 五个 Tab 的 CMS 管理界面

### 1.4 缺口（做官网必须补齐）
1. **访客无法浏览商品**：产品库/详情接口全部要求 `product:read` 权限，无公开商品 API
2. **无 C 端展示前端**：Naive UI 偏后台风格；SPA 无 SSR，SEO 不达标
3. **无脱敏策略**：出厂价、工厂信息、review_status 等绝不能出现在公开接口
4. **无 C 端账号/线索体系**：现有 sys_user 面向内部角色
5. 已知风险：P0 缺陷未结（Excel 导入图片/报价挂接丢失）；风格案例库仅覆盖客厅场景

---

## 二、官网蓝图：对标宜家的页面体系（按 RSDP 现状裁剪）

宜家全站很重，我们按"展示型官网 + 线索转化"裁剪为 **8 类页面**：

| # | 页面 | 路由建议 | 对标宜家 | 数据来源（全部已存在） |
|---|---|---|---|---|
| 1 | 首页 | `/` | ikea.cn 首页 | `platform_banner` + `platform_case` + `platform_customized` + 新增"精选商品/风格入口"运营位 |
| 2 | 商品分类导航 | `/category/{categoryCode}` | 所有商品类目树 | `category_dict`（品类字典，已是树形 code/path） |
| 3 | 商品列表 PLP | `/products?category=&style=&scene=&dimA~F=` | `/cat/...` | `rspu_master` + 六维 JSONB 筛选（**管理端已实现同款筛选，逻辑照搬**） |
| 4 | 商品详情 PDP | `/product/{rspuCode}` | `/p/{货号}/` | `rspu_master` + `rspu_variant` + `image_assets` + `rspu_style` + `rspu_relation`（搭配） |
| 5 | 空间/场景灵感 | `/scenes/{scene}` | `/rooms/` + `/ideas/` | `rspu_scene` + scene 类型图片 + `platform_case` |
| 6 | 风格专题 | `/styles/{styleCode}` | 灵感页"按风格" | **17 种风格字典 + `style_case.ai_raw_output` 风格百科 JSON**（色系/材质/灯光/搭配建议/典型家具——现成的内容富矿） |
| 7 | 关于/服务/工厂实力 | `/about`、`/service` | 服务总览 | `platform_content` 内容块 |
| 8 | 设计咨询/预约线索 | 全站悬浮 + 落地页 `/consult` | 全屋设计预约弹窗 | 新增 `platform_lead` 表（姓名/电话/空间/预算/留言） |

**三条商品组织线索完全复刻宜家思路**：
- 商品维度 = 品类字典（`category_dict`）
- 空间维度 = 场景标签（`rspu_scene` / scene_tags）
- 风格维度 = 17 风格字典（`rspu_style`）—— 这是你相对宜家的**差异化优势**：六维标签 + AI 风格匹配是宜家都没有的能力

---

## 三、技术方案

### 3.1 总体架构（新增部分高亮）

```
C 端访客
   │
   ▼
┌──────────────────┐      ┌─────────────────────────────────┐
│ 官网前端（新增）  │ ───▶ │ Nginx（现有，新增 server/location）│
│ Nuxt 3 SSR 独立站 │      └──────────────┬──────────────────┘
└──────────────────┘                     ▼
                          ┌──────────────────────────────┐
                          │ 现有 Spring Boot 单体（不动）  │
                          │  新增公开只读接口层：           │
                          │  /api/v1/public/products     │ 商品列表/筛选
                          │  /api/v1/public/products/{c} │ 详情（脱敏DTO）
                          │  /api/v1/public/styles/{c}   │ 风格专题聚合
                          │  /api/v1/public/scenes/{c}   │ 场景灵感聚合
                          │  POST /api/v1/public/leads   │ 线索提交
                          │  ────────────────────────     │
                          │  已有：/public/home /content  │
                          │  已有：/images/**             │
                          └──────────────┬───────────────┘
                    PostgreSQL（零结构改动 + 1 张新表 platform_lead）
```

**关键决策与理由：**

1. **后端不改领域模型，只加"公开只读门面层"**
   - 新增 `PublicProductController`（`/api/v1/public/products`），内部复用现有 `ProductService` 的查询能力（六维/风格/场景/品类筛选在管理端已实现，JSONB 包含查询现成）
   - 必须新建**专用脱敏 DTO**（`PublicProductVO`）：只含品名、品类、风格、六维展示标签、零售参考价、变体规格、图片 ID；**绝不透传** factory_price、factory_code、review_status、status 内部态、vector 等
   - 只放出 `status=已上架 AND review_status=已通过` 的商品（在查询层硬过滤，不依赖前端）
   - SecurityConfig 只对 `/api/v1/public/products/**` 等新增 matcher 加 permitAll，沿用现有模式

2. **前端独立 Nuxt 3 SSR 站点，不与现有 Vue SPA 混合**
   - 理由：① SEO 是官网刚需，现有 SPA 改 SSR 代价远大于新建；② Naive UI 是后台风格，C 端视觉需要自建组件体系；③ 与宜家技术形态一致（宜家就是 Nuxt 3 SSR，实测）
   - 部署：新 docker-compose 服务 `website`（Node SSR），Nginx 增加域名分流——`www.你的域名` → Nuxt，`admin.你的域名` → 现有管理端 SPA
   - 降级选项（预算紧）：用 **Vite 预渲染（vite-plugin-prerender）** 代替 Nuxt，仍是 Vue 技术栈但可静态化 SEO；推荐直接 Nuxt，一步到位

3. **数据零迁移**：商品/图片/风格/场景全部读现有表；仅新增 1 张表：
   ```sql
   CREATE TABLE platform_lead (
     id UUID PRIMARY KEY,
     name VARCHAR(64), phone VARCHAR(32),
     space_type VARCHAR(32),      -- 空间：客厅/卧室…（复用场景字典码）
     style_code VARCHAR(32),      -- 意向风格（复用风格字典码）
     budget_range VARCHAR(32),
     message TEXT,
     source_page VARCHAR(128),    -- 来源页面（转化追踪）
     status VARCHAR(16) DEFAULT 'NEW',  -- NEW/CONTACTED/DONE
     created_at TIMESTAMP DEFAULT now()
   );
   ```
   管理端在现有 `admin/platform/` 加一个"线索管理"Tab 即可消化。

4. **图片直接复用**：`GET /api/v1/images/{imageId}` 已公开。Nuxt 侧配置图片代理 + 懒加载 + 多规格（现有 ImageResizer 可在上传时预生成列表图/详情图两档尺寸）。

### 3.2 视觉设计规范（对标宜家，贴合家居行业）
- **基调**：白底 + 浅灰区块 + 近黑正文（宜家同款克制路线）；品牌主色用你们展厅/Logo 色作点缀（建议木色/暖棕系，区别于宜家蓝黄）
- **字体**：中文系统无衬线栈 + 数字/价格用 DIN 风格字体
- **商品卡片**：场景图为主图（hover 切白底图——你们 image_assets 两类图都有，天然支持宜家"商品图/场景图切换"）、品名 + 风格标签 + 规格简述 + 零售参考价
- **导航**：顶部固定导航（商品/空间/风格/灵感案例/关于我们）+ 全屏 Mega Menu + 面包屑 + 页脚胖导航
- **组件清单**（Nuxt 侧新建约 20 个）：SiteHeader、MegaMenu、HeroBanner、CategoryCard、ProductCard、FilterPanel（品类/风格/场景/六维）、SortBar、ProductGallery、VariantSelector、StyleBadge、SceneGrid、StyleArticleBlock、CaseCard、LeadFormModal、FloatConsult、SiteFooter 等

### 3.3 SEO 与性能
- Nuxt SSR 直出 + 语义化 URL（`/product/FS-MC-001-M` 或拼音 slug）+ og 标签 + sitemap.xml 动态生成（从公开商品接口出）+ robots.txt
- 首页/分类页走 Nuxt 路由级缓存（ISR，增量静态再生成，商品更新后自动刷新）
- 图片懒加载 + CDN（MinIO 前置 Nginx 缓存或接阿里云 OSS，与宜家同思路）

### 3.4 迭代排期建议（按单人全栈估算，可用 Kimi Code 加速）

| 迭代 | 内容 | 预估 |
|---|---|---|
| **M1 公开 API 层** | PublicProductController + 脱敏 DTO + 上架过滤 + 线索提交接口 + platform_lead 表 + 管理端线索 Tab；补 SecurityConfig matcher | 3~4 天 |
| **M2 官网骨架** | Nuxt 3 工程初始化 + 设计规范落地 + SiteHeader/MegaMenu/Footer + 首页（对接 /public/home） | 4~5 天 |
| **M3 商品浏览闭环** | PLP（筛选/排序/分页，复用六维筛选逻辑）+ PDP（画廊/变体/搭配推荐 rspu_relation）+ 分类导航 | 5~6 天 |
| **M4 内容与灵感** | 场景灵感页 + 风格专题页（style_case 百科 JSON 渲染成内容页，这是差异化亮点）+ 关于/服务页 | 3~4 天 |
| **M5 转化与上线** | 设计咨询线索表单 + 悬浮入口 + SEO（sitemap/og/结构化数据）+ Nginx 域名分流 + docker-compose 集成 + 灰度上线 | 3~4 天 |

**合计约 18~23 个工作日**（不含视觉稿打磨；若先出设计稿另加 3~5 天）。

### 3.5 与现有路线图的衔接
- 本方案与 `docs/08-roadmap` 中"阶段 7 = 官网 CMS + 首页营销区"**无缝衔接**：CMS 后台与公开聚合接口已建好，本方案补齐的是"公开商品 API + 独立 C 端前端"这最后两块
- 建议**先结 P0 缺陷**（Excel 导入图片/报价挂接丢失）再启动 M1，避免商品数据不全就对外展示
- 风格案例库目前仅覆盖客厅——M4 上线前建议补充卧室/餐厅场景的 style_case 种子，否则风格专题页内容单薄

### 3.6 风险清单
| 风险 | 应对 |
|---|---|
| 出厂价/工厂数据经公开接口泄露 | 专用 VO + 查询层硬过滤 + 新增公开接口的自动化测试断言"响应不含 factory/price 密文字段" |
| 零售参考价展示策略（有的商品可能没有 retail_price） | 无价商品显示"咨询获取"，引导线索表单 |
| 商品量不足导致官网空旷 | M3 前用现有种子数据 + 补录；首页运营位用 case/风格内容填充 |
| SSR 运维成本增加 | 官网是无状态只读站点，容器崩了自动重启即可；数据压力全在现有后端 |

---

## 四、一句话总结

**你的平台已经完成了宜家官网最难的部分（结构化商品数据中台 + 图片资产 + 风格内容体系 + CMS + 公开接口网关），距离一个宜家式官网只差"一层脱敏的公开商品 API + 一个 Nuxt SSR 展示站 + 一张线索表"，预计 4~5 周可完整上线。**
