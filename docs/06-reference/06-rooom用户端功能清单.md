# rooom 用户端功能清单（抓包分析）

> 分析对象：https://www.rooom.vip/ （站点标题："rooom家居全案"）
> 分析时间：2026-07-21，来源：前端打包产物 www-index.js + 25 个页面 chunk
> 技术栈推断：Vue 3 + Element Plus + Pinia + vue-i18n + Vite；后端为 yudao(ruoyi-vue-pro) 风格 REST API（`/app-api/**`、`tenant-id: 1` 请求头、`{code,data}` 响应）；图片托管阿里云 OSS（`?x-oss-process=image/resize,w_N` 缩略图）；画布为独立子应用 `board.rooom.vip`；页面自动翻译用 translate.js。

---

## 1. 页面路由总表

全站共 6 个一级路由 + 用户中心 4 个子页，全部页面 `keepAlive`。未登录一律跳转 `/login`。

| 路由 | 页面 | 功能描述 |
|---|---|---|
| `/login` | 登录/注册一体页 | 密码登录 / 验证码登录两种 Tab；账号框支持"手机号或邮箱"；可填邀请码；必须勾选服务协议（协议从 `platform/content/get-by-code?code=platform_user_agreement` 动态拉取）；右上角语言切换（简中/English） |
| `/` | 首页 | ① 顶部分类快捷入口（新材料/石材/木饰面/玻璃/金属/瓷砖，跳产品库）；② 435px 竖向轮播 Banner（点击带 spuId/skuId 跳产品详情）；③ 新品上架（`spu/new-list`）；④ 落地案例图片墙；⑤ 产品定制卡片；⑥ 页脚平台介绍+使用导览。数据来自 `/app-api/platform/home/`（banners/cases/customizeds） |
| `/brand/:id` | 品牌主页 | 品牌信息（名称/所在地/介绍/等级）+ 该品牌产品瀑布列表（query 支持 categoryId1/2/3） |
| `/product` | 产品库（素材库） | 左侧三级分类树（可搜索逐级展开）；筛选器：色系、品牌（可搜索）、价格区间、是否允许定制、新材料认证、国产/进口、产地、产品等级、发货地区、发货时间、空间/属性动态参数（propertyId+valueId 多选）；关键词搜索；每页 50 条无限滚动。产品卡片：图/名称/价格/店铺名/"加入项目库"按钮/收藏按钮（弹收藏夹选择 Popover）。点击卡片 → 产品详情抽屉 |
| `/product?spuId+skuId` | 产品详情抽屉（全站共用） | SKU 规格切换、图片浏览、评分、售后评价标签、详细参数、富文本描述、案例图、结构图/细节图、产品报告、相关下载、"加入项目库"。数据含：型号/库存/材质/重量/体积/原工厂代码/SPU编号/产品等级/产地/预计生产日期 |
| `/template` | 产品组合（模板库） | 左侧标签列表（`template-tag/simple-list`）→ 右侧模板卡片（封面+名称）→ 模板详情（`project/get-template` + `inventory/list` 空间清单）→ "选用模版"（`project/create-by-template`，2 秒后自动跳项目画布） |
| `/project` | 项目管理 | 顶部 Tab：项目/订单/已结束；右上"创建项目"、"下载合同模版"。**项目列表列**：序号/项目名称/创建时间/空间清单/产品总数/产品总价/折后总价；操作：前往画布/修改（仅管理员）/删除/生成订单（弹窗：收件人/电话/地区级联/详细地址）。**订单列表列**：项目名称/创建时间/订单状态（字典 design_order_status，异常行标红）/预计交期/订单编号/产品总数/折后总价；操作：查看详情/导入合同模板（上传回填）/导出清单/修改订单/取消订单。**订单详情抽屉**：按"所属房间"分组明细表（图/名称/型号/规格/价格/数量/小计/备注），支持折扣率 priceRatio（到手价/原价/x 折）、数量修改 |
| `/favorites` | 收藏夹 | 左侧收藏夹树（新建/删除收藏夹）；关键词搜索；右侧产品网格（加入项目库/查看详情）；`status<0` 提示"产品已下架" |
| `/favorites/:id` | 收藏夹详情 | 返回 + 导出按钮（选择是否含供应商，导出 `<收藏夹名>.xls`）；产品卡片网格 |
| `/user` | 用户中心布局页 | 左侧菜单 4 个子页 ↓ |
| `/user/info` | 个人中心 | 头像上传（cropper 裁剪）；修改昵称/名称/性别/邮箱；验证码修改密码（60s 倒计时）；游客账号显示"认证设计师"入口（提交 `user/certified-designer` 升级） |
| `/user/company` | 企业信息（企业账号） | 企业 Logo 上传、企业名称、价格折扣率 priceRatio、管理员信息（`company/update-owner` 变更） |
| `/user/member` | 成员管理（企业账号） | 管理员卡片（"修改管理员"）；部门管理（新增/编辑名称+启停开关/删除）；成员列表按部门过滤；邀请成员（`user/search-user` 搜索 → `user/join-company`）、修改分组、移出企业（二次确认） |
| `/user/invitation` | 邀请用户 | 生成永久邀请链接 `/login?inviteCode=<我的邀请码>`，一键复制；下方邀请记录列表（`user/invite-list`） |

**全局布局**：左侧导航（首页/产品库/创建项目弹窗/项目管理/产品组合/收藏夹）；顶部 Header（头像下拉：昵称+账号类型徽标 TOURIST/DESIGNER/COMPANY、咨询 Popover=客服配置内容、个人中心、退出）；创建项目对话框（名称+项目分组）；"加入项目库"对话框（选已有项目/放入新项目，`project-library/create`）；收藏 Popover（选择/新建收藏夹）。

## 2. API 接口总表

### design（设计/项目）
```
GET  /app-api/design/project/list                     POST /app-api/design/project/create
PUT  /app-api/design/project/update                   DELETE /app-api/design/project/delete?id=
POST /app-api/design/project/create-by-template       GET  /app-api/design/project/template-list?tag=
GET  /app-api/design/project/get-template?id=         GET  /app-api/design/template-tag/simple-list
GET  /app-api/design/inventory/list?projectId=
POST /app-api/design/project-library/create           DELETE /app-api/design/project-library/delete(-list)
GET  /app-api/design/project-library/list             GET  /app-api/design/project-role/join-project?code=
POST /app-api/design/project-order/create             PUT  /app-api/design/project-order/update(-status)
GET  /app-api/design/project-order/get|page|export
```

### product（商品）
```
GET /app-api/product/spu/page                 （关键词/分类/色系/品牌/价格/产地/等级/发货地区/交期/定制/属性参数）
GET /app-api/product/spu/get-detail?id=       GET /app-api/product/spu/new-list
GET /app-api/product/category/list            （三级分类树）
GET /app-api/product/brand/page|get           GET /app-api/product/color/simple-list
GET /app-api/product/property/simple-list?categoryId=
POST /app-api/product/favorite/create|delete  GET /app-api/product/favorite/exits|list
POST /app-api/product/favorites/create|delete GET /app-api/product/favorites/list
GET  /app-api/product/favorites/export-excel?favoritesId=&isSup=  （isSup=是否显示供应商）
```

### member（会员/企业）
```
POST /app-api/member/auth/login | sms-login | email-code-login | social-login | refresh-token
POST /app-api/member/auth/send-sms-code | send-email-code   （scene 区分登录/改密）
GET  /app-api/member/user/get              PUT /app-api/member/user/update
POST /app-api/member/user/update-password | email-update-password
PUT  /app-api/member/user/certified-designer
GET  /app-api/member/user/invite-list | search-user | list
POST /app-api/member/user/join-company | remove-company-member | update-group
GET  /app-api/member/company/get           PUT /app-api/member/company/update-company | update-owner
POST /app-api/member/group/create | update GET /app-api/member/group/list
```

### platform / infra / system
```
GET /app-api/platform/home/                 （首页配置 banners/cases/customizeds）
GET /app-api/platform/content/get-by-code?code=  （服务协议/客服咨询等富文本）
GET /app-api/infra/config/get-price-rate    （全局价格倍率）
POST /app-api/infra/file/upload | presigned-url | create  （OSS 预签名直传）
GET /app-api/system/area/tree               （行政区划）
GET /app-api/system/dict-data/get-types?types=  （product_area/expected_day/grade/design_order_status 等，缓存 60s）
```

## 3. 核心业务流程（用户旅程）

1. **注册/登录**：邀请链接（`?inviteCode=`）→ 手机号/邮箱 + 密码或验证码 → 勾选服务协议 → 默认游客账号
2. **认证升级**：游客点"认证设计师" → 设计师账号；进一步创建/加入企业 → 企业账号（含成员/部门/折扣率）
3. **选品**：首页 → 产品库多维筛选 → 产品详情抽屉
4. **收藏**：卡片收藏 → 选/建收藏夹 → 收藏夹详情可导出 Excel（可选隐藏供应商）
5. **组项目**：卡片"加入项目库" → 选已有/新建项目；或从产品组合（模板）按标签"选用模版"一键生成项目
6. **画布编辑**：项目"前往画布" → 新窗口 `board.rooom.vip?projectId=&ACCESS_TOKEN=&lang=`（token 直传免登）
7. **下单**：项目"生成订单" → 填收件信息 → 订单状态/交期跟踪 → 折扣率（到手价/原价/x 折）→ 导出清单、导入/下载合同模板 → 取消订单
8. **企业协作**：企业信息/折扣率维护 → 部门管理 → 搜索邀请成员/发邀请链接 → 分组/移出/变更管理员；项目可分配分组

## 4. 特色功能点

- **三级账号体系**：TOURIST→DESIGNER→COMPANY，顶栏账号类型徽标；游客一键"认证设计师"低门槛转化
- **邀请裂变**：永久邀请码 + `/login?inviteCode=` 注册绑定 + 邀请记录；另有项目级邀请 `project-role/join-project?code=`
- **企业折扣率**：企业实体带 priceRatio，订单明细按 priceRatio 显示"到手价 vs 原价 vs x 折"，B 端定价核心
- **模板化方案**：标签筛选 → 模板详情（含空间清单）→ 一键克隆成项目跳画布
- **画布分离架构**：管理列表站（www）与编辑器（board.rooom.vip）分离，URL 传 projectId+token 免登
- **订单-合同闭环**：下载合同模板 → 线下填 → "导入合同模板"上传回填 contract 字段；"导出清单"生成采购单
- **多维筛选模型**：分类(3级) × 属性动态参数 × 色系 × 品牌 × 价格 × 产地 × 等级 × 发货地区 × 交期 × 定制 × 新材料认证
- **收藏夹两级模型**：favorites(文件夹) + favorite(条目)，文件夹级导出 Excel（可选隐藏供应商=保护货源）
- **字典驱动**：等级/地区/交期/订单状态/认证类型/货币计量单位全走后端字典 + 本地缓存
- **内容可配置**：协议、客服、首页 Banner/案例/定制位均走后台内容配置，运营可改
- **下单防呆**：异常状态行标红、产品下架拦截、删除/移出/取消二次确认
