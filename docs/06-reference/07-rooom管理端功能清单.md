# rooom 管理端功能清单（抓包分析）

> 分析对象：https://admin.rooom.vip/ （基于芋道 yudao / ruoyi-vue-pro 二开，Vue 3 + Element Plus）
> 分析时间：2026-07-21，来源：前端打包产物 admin-index.js（含 620 个 chunk 的视图映射表）+ 在线补抓的 design/platform 全部 20 个业务 chunk
> 标记说明：【自研】= rooom 定制模块；【yudao】= 框架标准模块

---

## 1. 模块总览表

| 模块 | 路由前缀 | 包含页面/功能 | 类型 |
|---|---|---|---|
| 设计管理 design | `/design` | 项目管理、项目订单、订单统计、模板项目、物料板配置(iframe)、模板标签 | 【自研】 |
| 官网平台 platform | `/platform` | Banner 管理、案例管理、内容管理、自定义字典、定制管理 | 【自研】 |
| 首页/仪表盘 | `/home` | Home Index/Index2 | yudao |
| 系统管理 system | `/system` | 用户/角色/菜单/部门/岗位/字典/参数/通知公告/操作日志/登录日志/短信/邮件/站内信/社交登录/OAuth2/租户/租户套餐/地区 | yudao |
| 基础设施 infra | `/infra` | 代码生成/数据源/定时任务/API 日志/文件管理/配置/各类监控/demo | yudao |
| 商城-商品 mall/product | `/mall/product` | SPU/SKU、分类、品牌、属性/属性值、评价、供应商评分 | yudao |
| 商城-交易 mall/trade | `/mall/trade` | 订单（详情/发货/改价/改地址/备注）、售后、快递/运费模板、自提、分销、交易配置 | yudao |
| 商城-营销 mall/promotion | `/mall/promotion` | 优惠券/秒杀/拼团/砍价/积分商城/满减送/好物推荐/文章/Banner/页面装修/客服 | yudao |
| 商城-统计 mall/statistics | `/mall/statistics` | 会员/商品/交易统计 | yudao |
| 会员 member | `/member` | 用户（详情 12 子页签）、等级、分组、标签(company 公司)、积分、签到、会员配置 | yudao（company 疑似定制扩展） |
| 支付 pay | `/pay` | 支付应用/渠道、支付订单、退款、转账、钱包、收银台、回调 | yudao |
| 物料板 MaterialBoard | `/design/config` | **独立子应用 https://board.rooom.vip，经 iframe 嵌入** | 【自研，独立部署】 |

## 2. design 自研模块详表

### 2.1 项目管理 `/design/project`
- 查询：项目名称模糊、固定 `type:[0,1]`（普通项目；type=2 模板项目走另一页）
- 列表列：id、项目名称 name、企业ID/名称、项目类型（字典 `design_project_type`）、创建人、创建时间、操作（新增/编辑/删除）
- 表单字段：`{ id, name, companyId, groupId, type }`
- 接口：`/design/project/{page,get,create,update,delete}`

### 2.2 项目订单 `/design/order`
- 查询：订单编号；**状态 Tab 栏**（各状态订单数 count，来自 `get-tabs`）
- 列表列：orderNo、**priceRatio 价格系数（xx.x%）**、项目名称、收件人+手机、收件地区（area/tree 转省市区文本）、收件地址、原价 originalTotalPrice、折后价 adjustTotalPrice、产品数量、订单状态、创建人、创建时间
- 行操作：导出订单（`export?orderId=` → `{orderNo}-订单明细.xlsx`）、订单详情、下载合同（row.contract 非空时）
- 详情/编辑弹窗三卡片：
  1. 基本信息：orderNo/priceRatio/projectName 禁改；**expectedDay 预计交期**、**status 状态**可改
  2. 收件人信息：姓名/手机必填、省市区级联、详细地址、**contract 合同文件上传（limit 1）**
  3. 商品明细 items[]：图片、spuName、SPU/SKU 编号、originalFactoryCode 原厂编码、parameter 参数、properties[{propertyName,valueName}]、数量、原价(单/总)、**折扣价(单) adjustPrice 可编辑（联动折扣总价）**、折扣价(总)、备注可编辑
- 提交：`PUT /design/project-order/update`，body 含 items[{id, adjustPrice(分), count, remark}]
- 接口：`/design/project-order/{page,get,update,delete,export,get-tabs,order-statistics,invite-order}`

### 2.3 订单统计 `/design/orderStatistics` —— 三维度
- 筛选：时间范围（快捷：一个月/近三个月/近一年）
- 一次请求 `Promise.all([order-statistics, invite-order])`，三个 Tab：
  1. **产品统计**：SPU编码/名称、订单号、总数量（可排序）、总金额（可排序）
  2. **供应商统计**：供应商编号、订单总数、总数量、总金额（均可排序）
  3. **邀请统计**：邀请人昵称/手机号、邀请成功人数、总支付金额；**行展开**子表：用户昵称/手机号/支付金额/关联项目

### 2.4 模板项目 `/design/templateProject`
- 与项目管理共用 `/design/project/*` 接口，查询固定 `type:2`
- 列表列：id、项目名称、项目标签 tags（多标签，选项来自 `template-tag/simple-list`）、描述、状态、创建时间、操作（编辑/**配置模板**/删除）
- 「配置模板」→ `router.push('/design/config?projectId=' + id)` 进入物料板
- 表单字段：`{ id, name, tags[], description, status }`

### 2.5 物料板配置 `/design/config`（组件名 MaterialBoard）
- **不是本系统页面，是 iframe 嵌入独立子应用**：
  `src = https://board.rooom.vip?ACCESS_TOKEN=<硬编码固定令牌>&type=2&projectId={projectId}`
- 页面结构：ContentWrap(padding:0) + IFrame + v-loading
- 板材内容管理端完全不可见，全部在 board.rooom.vip 内部按 projectId 落库

### 2.6 模板标签 `/design/templateTag`
- 极简字典页：仅 `name 标签名称` 一个字段
- 接口：`/design/template-tag/{page,simple-list,get,create,update,delete}`

### 2.7 design 相关字典
- `design_project_type`：0/1=普通项目，2=模板项目
- `design_order_status`：订单状态（驱动 Tab、详情下拉、DictTag 颜色）

## 3. platform CMS 模块详表

五个页面均为标准「查询 + 表格 + 弹窗表单」CRUD，接口风格一致：`/platform/{资源}/{page,get,create,update,delete}`。

| 页面 | 路由 | 接口前缀 | 查询条件 | 字段 | 说明 |
|---|---|---|---|---|---|
| Banner 管理 | `/platform/banner` | `/platform/banner` | 位置/状态 | 标题、位置（字典 `platform_banner_position`）、图片、跳转地址、排序、描述、状态 | 官网轮播/广告位 |
| 案例管理 | `/platform/cases` | `/platform/cases` | 状态 | 名称、封面、内容（富文本）、状态 | 官网客户案例 |
| 内容管理 | `/platform/content` | `/platform/content` | 类型/状态 | 标题、编码 code、类型（字典 `platform_content_type`）、内容、状态 | **编辑器随类型切换**：type=0 单图上传；type=1 富文本；type=2 第三种植入；按 code 被官网区块引用（协议/客服/页脚） |
| 自定义字典 | `/platform/customDict` | `/platform/custom-dict` | 名称/类型 | 名称、类型（`platform_custom_dict_type`）、状态 | 官网自有业务字典 |
| 定制管理 | `/platform/customized` | `/platform/customized` | 状态 | 名称、封面、排序、状态 | 官网「定制服务」条目 |

## 4. API 接口总表（前缀均 `/admin-api`）

### design / platform 自研
```
/design/project/{page,get,create,update,delete}
/design/project-order/{page,get,update,delete,export,get-tabs,order-statistics,invite-order}
/design/template-tag/{page,simple-list,get,create,update,delete}
/platform/banner|cases|content|custom-dict|customized/{page,get,create,update,delete}
```

### 认证与基础
```
POST /system/auth/{login,sms-login,social-login,register,logout,reset-password,send-sms-code,refresh-token}
GET  /system/auth/get-permission-info       （后端菜单驱动路由）
GET  /system/tenant/get-by-website?website= /get-id-by-name?name=   （多租户）
GET  /system/dict-data/{page,get,create,update,delete}
GET  /system/area/tree                      GET /infra/file/page  POST /infra/file/create
```

## 5. 物料板（MaterialBoard）机制还原

1. **定位**：独立微前端应用 `https://board.rooom.vip`，与 admin 主站分离部署；admin 仅有 `/design/config` 路由页用 IFrame 全屏嵌入
2. **鉴权**：URL query 传 `ACCESS_TOKEN`（硬编码固定令牌，物料板自行校验）+ `type=2` + `projectId`；与主站登录态解耦
3. **数据模型**：模板项目 = `design_project` 表 `type=2` 记录（name/tags/description/status 四字段）；tags 关联 `design_template_tag`；板材格子/材质/搭配内容全在 board 应用侧
4. **业务闭环**：模板项目「配置模板」→ 物料板拖拽配置 → 设计师端按 projectId 渲染 → 转项目订单（明细带 SPU/SKU/原厂编码/参数/属性，价格由 priceRatio 折算，管理端可再改折扣价）
5. **复刻建议**：RSDP 可拆两应用（iframe 壳 + 编辑器）或合并单 SPA；关键是 projectId+token 上下文传递与「type=2 模板/0-1 普通」共用 project 表的设计

## 6. yudao 标准模块页面清单（简表）

| 模块 | 页面 |
|---|---|
| system | 用户管理（导入/分配角色）、角色（菜单/数据权限）、菜单、部门、岗位、字典类型/数据、参数配置、通知公告、操作日志、登录日志、短信渠道/模板/日志、邮箱账号/模板/日志、站内信、社交客户端/用户、OAuth2 客户端/令牌、租户、租户套餐、地区 |
| infra | 代码生成、数据源、定时任务、API 访问/错误日志、文件管理/配置、Redis/Server/Druid/Swagger/SkyWalking/WebSocket 监控、demo01-03 |
| mall.product | 商品 SPU（五段表单 + SKU 列表/批量改价）、分类、品牌、属性/属性值、商品评价、供应商评分 |
| mall.trade | 订单（详情/发货/核销/改地址/改价/改备注）、售后、物流公司、运费模板、自提门店/订单、分销记录/用户/提现、交易配置 |
| mall.promotion | 优惠券、秒杀、拼团、砍价、积分商城、满减送、好物推荐、文章/分类、商城 Banner、页面装修（可视化编辑器）、客服会话/消息 |
| mall.statistics | 会员（漏斗/终端）、商品（排行/汇总）、交易（趋势/数值） |
| member | 会员用户（详情 12 页签：基本信息/地址/余额/积分/优惠券/订单/售后/收藏/浏览/分销/签到/经验；邀请用户、改积分/余额/等级）、等级、分组、公司（企业档案）、积分记录、签到配置/记录、会员配置 |
| pay | 支付应用（支付宝/微信/钱包/模拟）、支付订单、退款、转账、钱包余额/充值套餐/流水、收银台、支付回调 |

## 附：复刻取舍建议

- **必抄**：design 五页的字段与接口形状；订单「价格系数 + 行级折扣价」模型
- **platform CMS** 五页都是薄 CRUD，内容管理「type 决定编辑器形态」是唯一的巧思
- **mall/pay/member 全家桶**与 RSDP 的 RSPU/RSKU 体系冲突，不建议照搬；订单明细 `spuNo/skuNo/originalFactoryCode/parameter/properties[{propertyName,valueName}]` 结构与 RSDP 的 RSPU/RSKU+变体属性高度同构，可作映射参考
