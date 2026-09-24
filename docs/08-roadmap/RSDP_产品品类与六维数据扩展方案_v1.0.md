# RSDP 产品品类与六维数据扩展方案（不影响现有业务流程版）

> 版本：v1.0  
> 日期：2026-09-24  
> 目标：在**不修改现有 RSPU / RSKU 编码、不迁移存量产品、不改变当前九大品类识别结果、不改变当前报价/订单/同款流程**的前提下，系统性补充产品品类、二级产品类型和六维标签数据，为后续全案选品 / Marketing Agent / 空间搭配能力提供数据基础。  
> 使用对象：Codex / 开发人员 / 数据运营人员  
> 仓库：`https://github.com/Chen-Minghuan/rsdp`

---

## 0. 给 Codex 的执行摘要

本轮改造遵循以下硬约束：

1. **禁止修改任何已存在的 `rspu_code` / `rsku_code` / `variant_id`。**
2. **禁止修改现有 9 个业务品类编码的含义：**
   - `FS` 座椅
   - `SF` 沙发
   - `TB` 茶几
   - `FC` 柜类
   - `BS` 吧椅
   - `OF` 办公家具
   - `DT` 餐桌
   - `BD` 床
   - `LT` 灯具
3. **禁止为了补品类而迁移现有 RSPU。**
4. 现有六维枚举和现有识别逻辑必须保持兼容。
5. 新增能力采用：
   - **二级产品类型旁路扩展**
   - **新增独立品类仅用于现有九类无法准确描述的产品**
   - **新六维配置追加，不覆盖旧六维**
   - **Feature Flag / Shadow Mode 先验证后启用**
6. `E` 维仍保持当前约定：**不建立 `six_dim_E` 独立枚举，统一引用 `material / fabric` 字典。**
7. 六维新增值继续使用当前规则：
   - `dict_code = {品类码}-{中文名}`
   - `dict_name = 中文名`
   - aliases 存常见叫法
   - remark 存视觉判别锚点
8. Codex 开始修改前，先以当前仓库代码为准重新 `git grep` 相关消费点；本文件是设计输入，不可覆盖仓库中更新后的事实。

---

# 1. 当前系统基线

当前开发库已有：

- `category_dict`：592 条 / 29 类；
- `six_dim_schema`：60 条，10 套定义（9 个业务品类 + GENERIC）；
- 六维枚举：427 条；
- 风格正向案例：11 条；
- 风格搭配公式：11 条；
- 风格参考图：410 张；
- 当前正式产品品类 9 个：FS / SF / TB / FC / BS / OF / DT / BD / LT。

当前六维体系已经是 RSPU 产品形态描述的重要基础：

```text
AI 识别
  ↓
按 categoryCode 获取 six_dim_schema
  ↓
A/B/C/D/E/F 六维
  ↓
rspu_master.six_dim_tags
  ↓
展示 / 筛选 / 风格匹配 / 相似款辅助
```

仓库当前 `rspu_master` 已将稳定身份、业务编码和属性拆开：

```text
rspu_id               稳定主键
rspu_code             可读业务编码
category_code         业务大类
six_dim_tags          产品视觉结构特征
material_tags         材质
scene_tags            场景
...
```

当前业务编码计数器仍与品类 / 风格 / 材质有关：

```text
RSPU 流水：
category_code + style_code

RSKU 流水：
rspu_code + factory_code + material_code
```

因此本轮最重要的原则是：

> **知识分类可以越来越细，业务身份不能跟着知识分类反复变化。**

仓库当前 `rspu_variant` 已经明确采用“无业务含义顺序号，避免尺寸 / 材质变化导致编码变化”的设计，这一思想应继续推广到品类和知识扩展。

---

# 2. 外部市场品类研究结论

本次补充参考了多个不同类型的数据源：

## 2.1 IKEA

IKEA 当前产品分类中明确存在：

- Dressers / storage drawers
- Shelving furniture
- Display & storage cabinets
- Armoires & wardrobes
- TV & media furniture
- Room dividers
- Sofas & sectionals
- Sleeper sofas / sofa beds
- Armchairs
- Ottomans / footstools / poufs
- Chaise lounges
- Beds
- Mattresses
- Nightstands
- Lighting
- Outdoor furniture

来源：  
`https://www.ikea.com/us/en/cat/products-products/`

## 2.2 Minotti

Minotti 的室内产品体系包括：

- Sofas
- Chaise longue and Daybed
- Armchairs
- Tables and Writing Desks
- Little armchairs and stools
- Coffee tables
- Console tables
- Bookcases and sideboards
- Rugs
- Beds
- Night-tables

来源：  
`https://www.minotti.com/en/the-world-of-materials`  
`https://www.minotti.com/en/general-catalogue-the-complete-interior-collection`

## 2.3 Molteni&C

Molteni&C 的产品目录包括：

- Living systems and bookshelves
- Wardrobes and walk-in closets
- Sofas
- Armchairs
- Coffee tables
- Tables and desks
- Chairs and stools
- Beds and benches
- Drawer units
- Rugs
- Bed accessories

来源：  
`https://www.molteni.it/en/eu/products`

## 2.4 B&B Italia

B&B Italia 室内分类包括：

- Sofas
- Armchairs
- Chairs
- Beds
- Tables
- Small tables
- Ottomans
- Living storage units
- Night storage units
- Complements

来源：  
`https://www.bebitalia.com/en-us/products`

## 2.5 Architonic

Architonic 对家具进行了大量细分，例如：

- Chairs
- Stools
- Bar stools
- Benches
- Armchairs
- Sofas
- Modular seating
- Poufs
- Day beds / loungers
- Chaise longues
- Console tables
- Coffee tables
- Side tables
- Nesting tables
- Desks
- Shelving

来源：  
`https://www.architonic.com/en/products/furniture/0/3210002/1`  
`https://www.architonic.com/en/products/lapalma-chairs/3100076/3238273/1`  
`https://www.architonic.com/en/products/dreieck-design-console-tables/3101413/3238228/1`

## 2.6 Steelcase / 办公家具体系

办公家具常见独立产品族包括：

- Seating
- Desks + Tables
- Storage
- Architecture + Space Division
- Worktools

来源：  
`https://www.steelcase.com/products/`

## 2.7 研究结论

市场上的“产品品类”有两种不同层级：

### 第一层：结构完全不同的产品族

例如：

```text
沙发
柜子
桌
床
床垫
灯
镜子
地毯
屏风
窗饰
```

它们需要不同的六维 Schema。

### 第二层：同结构下的业务细分类

例如：

```text
座椅
├─ 餐椅
├─ 休闲椅
├─ 扶手椅
├─ 摇椅
├─ 长凳
├─ 坐墩
└─ 折叠椅

柜类
├─ 电视柜
├─ 餐边柜
├─ 床头柜
├─ 斗柜
├─ 衣柜
├─ 鞋柜
├─ 酒柜
└─ 展示柜
```

这类产品**没有必要全部生成新的 RSPU 一级 category_code**。

因此本方案采用：

> **稳定业务大类 + 二级产品类型 + 按结构差异新增六维 Schema**

而不是无限增加一级品类编码。

---

# 3. 推荐的品类扩展模型

## 3.1 第一原则：不要把所有市场分类都变成 category_code

建议将分类拆成：

```text
business_category
稳定业务大类
        │
        ├── product_type
        │   二级具体产品类型
        │
        └── six_dim_schema
            结构形态体系
```

示例：

```text
FS 座椅
│
├─ DINING_CHAIR       餐椅
├─ LOUNGE_CHAIR       休闲椅
├─ ARMCHAIR           扶手椅
├─ ROCKING_CHAIR      摇椅
├─ BENCH              长凳
├─ OTTOMAN            脚踏
└─ POUF               坐墩

六维仍使用 FS
```

这样：

```text
FS-MC-021-M
```

无需因为“休闲椅 / 餐椅”发生变化。

---

# 4. 建议新增 `product_type` 语义层

建议新增旁路表：

```sql
knowledge_product_type
```

推荐字段：

```text
type_code
type_name
business_category_code
schema_category_code
parent_type_code
aliases
room_tags
description
status
knowledge_version
created_at
updated_at
```

其中：

```text
business_category_code
```

决定它归入哪个现有业务大类；

```text
schema_category_code
```

决定使用哪一套六维。

例如：

| type_code | 名称 | business_category | schema |
|---|---|---|---|
| DINING_CHAIR | 餐椅 | FS | FS |
| LOUNGE_CHAIR | 休闲椅 | FS | FS |
| BENCH | 长凳/床尾凳 | FS | FS |
| OTTOMAN | 脚踏 | FS | FS |
| SIDE_TABLE | 边几 | TB | TB |
| NESTING_TABLE | 套几 | TB | TB |
| TV_STAND | 电视柜 | FC | FC |
| SIDEBOARD | 餐边柜 | FC | FC |
| NIGHTSTAND | 床头柜 | FC | FC |
| DRESSER | 斗柜 | FC | FC |

该表第一阶段只服务知识层和 Agent：

> **不进入 RSPU 编码规则。**

---

# 5. 现有九大类建议补充的二级产品类型

## 5.1 FS 座椅

建议补充：

```text
DINING_CHAIR       餐椅
LOUNGE_CHAIR       休闲椅
ARMCHAIR           扶手椅
ACCENT_CHAIR       装饰单椅
ROCKING_CHAIR      摇椅
FOLDING_CHAIR      折叠椅
HANGING_CHAIR      吊椅
BENCH              长凳 / 床尾凳
OTTOMAN            脚踏
POUF               坐墩
STOOL              矮凳
CHAIR_LOUNGER      单人躺椅
```

建议 aliases：

```text
椅子
单椅
餐椅
靠背椅
休闲椅
主人椅
扶手椅
老虎椅
翼背椅
摇椅
床尾凳
换鞋凳
脚踏
坐墩
鼓凳
```

### 六维处理

继续使用 FS，不新增业务编码。

---

## 5.2 SF 沙发

建议产品类型：

```text
LOVESEAT            双人沙发
STRAIGHT_SOFA       直排沙发
SECTIONAL_SOFA      转角沙发
MODULAR_SOFA        模块沙发
SOFA_BED            沙发床
RECLINER_SOFA       功能沙发
CURVED_SOFA         弧形沙发
CHAISE_SOFA         贵妃 / 躺榻组合
```

继续使用 SF 六维。

---

## 5.3 TB 茶几 / 小桌几

建议产品类型：

```text
COFFEE_TABLE        茶几
SIDE_TABLE          边几
END_TABLE           角几
C_TABLE             C形沙发边桌
NESTING_TABLE       子母 / 套几
DRUM_TABLE          鼓形几
PEDESTAL_TABLE      柱式小几
CONSOLE_TABLE       玄关台 / 窄边桌（过渡可归 TB）
```

> Console Table 在 Minotti / Architonic 中经常作为独立产品类型，但其视觉结构和 TB/DT 高度重合。为避免新增一级编码，第一阶段建议归 `TB + CONSOLE_TABLE`。

---

## 5.4 FC 柜类

建议产品类型：

```text
TV_STAND            电视柜
SIDEBOARD           餐边柜
CREDENZA            边柜
NIGHTSTAND          床头柜
DRESSER             斗柜
WARDROBE            衣柜
BOOKCASE            书柜
DISPLAY_CABINET     展示柜
SHOE_CABINET        鞋柜
WINE_CABINET        酒柜
ENTRY_CABINET       玄关柜
MOBILE_CART         移动柜 / 推车
```

继续使用 FC 六维。

---

## 5.5 BS 吧椅

建议细分：

```text
BAR_STOOL           吧凳
BAR_CHAIR           吧椅
COUNTER_STOOL       中岛凳
ADJUSTABLE_STOOL    升降吧椅
BACKLESS_STOOL      无靠背高凳
```

继续使用 BS 六维。

---

## 5.6 OF 办公家具

保留当前办公系统大类，但补 product_type：

```text
TASK_CHAIR
EXECUTIVE_CHAIR
GUEST_CHAIR
STAFF_DESK
EXECUTIVE_DESK
MANAGER_DESK
WORKSTATION
CONFERENCE_TABLE
TRAINING_TABLE
HEIGHT_ADJUSTABLE_DESK
FILING_CABINET
OFFICE_BOOKCASE
OFFICE_CREDENZA
```

> 家庭书房的书桌不建议长期继续挂 OF，因为 OF 还承担“总裁/经理/职员”等职级语义。家庭/酒店书桌建议新增 `DK`。

---

## 5.7 DT 餐桌

建议细分：

```text
DINING_TABLE
ROUND_DINING_TABLE
EXTENDABLE_DINING_TABLE
BANQUET_TABLE
BAR_TABLE
KITCHEN_ISLAND_TABLE
```

继续使用 DT。

---

## 5.8 BD 床

建议细分：

```text
UPHOLSTERED_BED
PLATFORM_BED
STORAGE_BED
BUNK_BED
LOFT_BED
CANOPY_BED
DAY_BED
KIDS_BED
```

其中 `DAY_BED` 若明显更接近坐具，可在人工复核中映射 FS；不要自动改已有 BD。

---

## 5.9 LT 灯具

建议细分：

```text
PENDANT_LIGHT
CHANDELIER
CEILING_LIGHT
FLOOR_LAMP
TABLE_LAMP
WALL_LIGHT
TRACK_LIGHT
RECESSED_LIGHT
CLAMP_LIGHT
DECORATIVE_LIGHT
```

继续使用 LT。

---

# 6. 真正建议新增的独立业务品类

综合“结构差异、全案使用频率、现有六维是否能表达”三个因素，建议第一批只新增 6 类：

| code | 品类 | 优先级 | 原因 |
|---|---|---:|---|
| DK | 书桌/写字台 | P0 | 家庭/酒店书桌不应长期混入 OF 职级体系 |
| MT | 床垫 | P0 | BD 六维无法表达床垫结构 |
| MR | 镜子 | P1 | 全案卧室/玄关/卫浴高频，结构独立 |
| RG | 地毯 | P1 | 高端全案方案高频，结构/图案体系独立 |
| PD | 屏风/隔断 | P1 | 现有 FC“隔断柜”无法覆盖屏风、声学隔断等 |
| CW | 窗帘/窗饰 | P2 | 全案必要，但更接近软装，可在第二批启用 |

## 暂时不要新增为一级品类

以下产品建议先作为 product_type：

```text
边几
角几
套几
玄关台
长凳
床尾凳
脚踏
坐墩
餐边柜
电视柜
床头柜
斗柜
衣柜
鞋柜
酒柜
书柜
展示柜
梳妆台
```

原因：

> 它们已有结构相近的大类可承载，贸然增加业务 code 会加大 RSPU 编码命名空间和 AI 分类边界冲突。

---

# 7. 新增六维 Schema：DK 书桌 / 写字台

## 7.1 Schema

| 维度 | 名称 | 定义 |
|---|---|---|
| A | 整体布局/轮廓 | 桌体俯视与整体布局 |
| B | 台面形态 | 工作台面的厚薄、层级、构造 |
| C | 侧部/连接结构 | 侧柜、挡板、走线、上架等 |
| D | 桌腿/支撑 | 桌面以下承重结构 |
| E | 表面材质 | 引用 material/fabric |
| F | 收纳/功能 | 抽屉、升降、充电、折叠等 |

## 7.2 A 整体布局/轮廓

```text
一字矩形
L型转角
双人长桌
弧形/曲线
窄长壁靠
上架一体
柜桌一体
模块组合
异形/其他
```

建议 aliases：

```text
一字矩形：书桌、直桌、写字台、电脑桌
L型转角：转角书桌、L桌、拐角桌
双人长桌：双人书桌、双工位
窄长壁靠：窄桌、靠墙桌、玄关书桌
上架一体：书架桌、带书架书桌
柜桌一体：书柜书桌一体、侧柜桌
模块组合：组合书桌、模块桌
```

## 7.3 B 台面形态

```text
平板薄面
厚台面
悬浮台面
内嵌/包框台面
双层台面
抬高层台面
翻折台面
拼接台面
异形/其他
```

## 7.4 C 侧部/连接结构

```text
无明显侧件
落地侧板
侧柜/抽屉箱
前挡板
桌面屏风
走线孔/线盒
上架连接
横梁拉杆
异形/其他
```

## 7.5 D 桌腿/支撑

```text
四直腿
外八/斜腿
U形/口字框架
T形底座
板式落地侧板
单侧柜支撑
双侧柜支撑
悬浮/壁挂
升降立柱
脚轮移动
异形/其他
```

## 7.6 F 收纳/功能

```text
无功能件
抽屉
侧柜
开放格
上置书架
走线管理
升降
折叠
插座/USB/无线充电
键盘托
脚轮
异形/其他
```

---

# 8. 新增六维 Schema：MT 床垫

> 注意：床垫很多内部结构仅靠白底外观无法确认。AI 必须遵循“可见/有明确商品文字才判断”的原则，不得根据品牌或外观猜弹簧、乳胶层等内部事实。

## 8.1 Schema

| 维度 | 名称 |
|---|---|
| A | 整体厚度/轮廓 |
| B | 顶面绗缝/表层 |
| C | 边缘工艺 |
| D | 侧围结构 |
| E | 表层材质 |
| F | 可见功能/结构特征 |

## 8.2 A 整体厚度/轮廓

```text
薄垫型
标准厚度
厚垫型
双层/加厚舒适层
枕顶型
折叠型
分体组合型
异形/其他
```

aliases：

```text
薄垫、榻榻米垫、宿舍垫
厚床垫、加厚床垫
pillow top、欧式枕顶
折叠床垫、三折垫
```

## 8.3 B 顶面绗缝/表层

```text
光面
菱形绗缝
方格绗缝
横条绗缝
波浪绗缝
深拉点/拉扣
立体花纹针织
分区纹理
异形/其他
```

## 8.4 C 边缘工艺

```text
直边
滚边/包边
双色撞色包边
加厚围边
圆角包边
双层围边
异形/其他
```

## 8.5 D 侧围结构

```text
素面侧围
绗缝侧围
透气网侧围
分段拼接侧围
带提手
带拉链外套
加固护边
异形/其他
```

## 8.6 F 可见功能/结构特征

```text
无明显功能
可拆洗外套
可折叠
双面可用
独立舒适层/薄垫组合
分区标识
防滑底面
卷包/压缩包装
异形/其他
```

---

# 9. 新增六维 Schema：MR 镜子

## 9.1 Schema

| 维度 | 名称 |
|---|---|
| A | 镜面整体轮廓 |
| B | 边框形态 |
| C | 镜面/边缘工艺 |
| D | 安装/支撑 |
| E | 边框/主体材质 |
| F | 附加功能 |

## 9.2 A 镜面整体轮廓

```text
圆形
椭圆形
矩形
拱形
跑道形
水滴形
不规则有机形
全身长镜
多片组合
异形/其他
```

## 9.3 B 边框形态

```text
无框
超窄框
标准细框
宽框
双层框
包覆软框
雕塑异形框
装饰雕花框
异形/其他
```

## 9.4 C 镜面/边缘工艺

```text
平面直边
磨边
斜边/车边
圆角
烟熏/灰镜
茶色镜
分割拼镜
波纹/艺术镜面
异形/其他
```

## 9.5 D 安装/支撑

```text
壁挂
落地斜靠
落地自立支架
台面摆放
旋转支架
吊带悬挂
柜体一体
嵌墙
异形/其他
```

## 9.6 F 附加功能

```text
无功能
环形灯带
背光
前置灯
储物
可调角度
双面旋转
放大镜
智能显示
异形/其他
```

---

# 10. 新增六维 Schema：RG 地毯

## 10.1 Schema

| 维度 | 名称 |
|---|---|
| A | 外轮廓 |
| B | 图案构成 |
| C | 边缘处理 |
| D | 绒高/织造表面 |
| E | 材质 |
| F | 工艺/功能 |

## 10.2 A 外轮廓

```text
矩形
方形
圆形
椭圆形
长条跑道/走廊毯
不规则有机形
拼接模块
异形/其他
```

## 10.3 B 图案构成

```text
纯色
几何
条纹
抽象
色块拼接
渐变
植物/花卉
东方/古典纹样
仿旧纹样
无规则艺术纹样
异形/其他
```

## 10.4 C 边缘处理

```text
直切边
包边
锁边
流苏
穗边
波浪/花边
自然毛边
不规则随形边
异形/其他
```

## 10.5 D 绒高/织造表面

```text
平织
短绒
中绒
高绒
长毛/Shaggy
圈绒
割绒
圈割结合
编织/辫织
高低立体纹理
异形/其他
```

## 10.6 F 工艺/功能

```text
常规固定
手工簇绒
手工打结
机织
可机洗
户外耐候
防滑底
双面可用
拼块组合
异形/其他
```

---

# 11. 新增六维 Schema：PD 屏风 / 空间隔断

## 11.1 Schema

| 维度 | 名称 |
|---|---|
| A | 整体组合形态 |
| B | 面板结构 |
| C | 连接结构 |
| D | 安装/底座 |
| E | 表面材质 |
| F | 附加功能 |

## 11.2 A 整体组合形态

```text
单片直立
双片折屏
三片折屏
多片折屏
弧形围合
模块拼接
滑动隔断
悬挂隔断
架体隔断
异形/其他
```

## 11.3 B 面板结构

```text
实体板
格栅
镂空花格
藤编/绳编
玻璃
布艺软包
声学吸音面
开放置物架
混合拼接
异形/其他
```

## 11.4 C 连接结构

```text
无连接单片
合页铰接
转轴连接
插接模块
金属连接杆
轨道连接
绳索/吊线连接
隐藏连接
异形/其他
```

## 11.5 D 安装/底座

```text
落地宽脚
T形脚
金属框架脚
脚轮移动
顶天立地
墙面固定
吊顶悬挂
地轨滑动
无明显底座
异形/其他
```

## 11.6 F 附加功能

```text
无功能
可折叠
可移动
置物/书架
挂衣
声学吸音
白板/展示
绿植组合
灯光
异形/其他
```

---

# 12. 新增六维 Schema：CW 窗帘 / 窗饰

> 该品类建议 P2 再启用。原因：它属于软装而非传统家具，且部分属性来自轨道、电机和面料规格，单张产品图不一定完整可见。

## 12.1 Schema

| 维度 | 名称 |
|---|---|
| A | 窗饰类型 |
| B | 帘头/上部结构 |
| C | 帘身褶型/组合 |
| D | 安装/驱动 |
| E | 面料/主体材质 |
| F | 遮光/功能 |

## 12.2 A 窗饰类型

```text
布艺开合帘
纱帘
罗马帘
卷帘
百叶帘
垂直帘
蜂巢帘
柔纱帘
面板帘
异形/其他
```

## 12.3 B 帘头/上部结构

```text
挂钩打褶
四爪/多褶
波浪帘
穿杆
打孔环
轨道带
明装帘头
盒式帘头
异形/其他
```

## 12.4 C 帘身褶型/组合

```text
单层
双层布纱
左右对开
单边开合
连续波浪
平面垂落
分幅拼接
异形/其他
```

## 12.5 D 安装/驱动

```text
窗帘杆
壁装轨道
顶装轨道
隐藏轨道
窗框内装
手拉
拉珠/拉绳
电动轨道
异形/其他
```

## 12.6 F 遮光/功能

```text
普通遮光
高遮光
全遮光
透光不透人
纯装饰
隔热
吸音
电动
阻燃
可水洗
异形/其他
```

---

# 13. 为什么暂时不把“装饰画 / 花器 / 摆件”硬塞入六维体系

六维体系的核心价值是：

> **稳定描述产品可见的结构形态。**

装饰画、雕塑、花器、艺术摆件的核心区分往往是：

```text
内容主题
艺术风格
颜色构成
视觉语义
作者/品牌
尺寸
摆放方式
```

这与：

```text
A 轮廓
B 上部
C 连接
D 底座
E 材质
F 功能
```

并不天然匹配。

因此建议后续单独建立：

```text
decor_semantic_tags
```

而不是为了“六维统一”强行制造低质量维度。

---

# 14. 对现有六维数据的补充建议

## 14.1 FS / SF

现有结构已经较完整，不建议大改。

建议通过 `product_type` 解决：

```text
长凳
坐墩
脚踏
贵妃榻
躺椅
摇椅
餐椅
休闲椅
```

仅在数据验证中发现某种视觉结构无法表达时，再添加六维枚举。

---

## 14.2 TB

TB 已能表达：

```text
圆形
方形
矩形
跑道形
鹅卵石
C形
鼓形
组合套几
```

因此：

```text
边几
角几
C形边桌
套几
```

无需新 Schema。

建议 product_type 补齐即可。

---

## 14.3 FC

当前 FC 已有：

```text
整体造型
门板/抽屉
拉手
底座
材质
内部结构
```

已经足以覆盖绝大多数：

```text
床头柜
电视柜
餐边柜
斗柜
衣柜
鞋柜
酒柜
展示柜
```

因此不要为这些建立独立业务 category。

建议优先补：

```text
product_type
aliases
```

而不是复制一套六维。

---

## 14.4 OF

OF 当前 A 维本身承担了“办公二级品类”职责。

长期建议把：

```text
办公椅
职员桌
屏风工位
文件柜
会议桌
班台
主管桌
培训桌
洽谈桌
升降桌
```

逐步迁移到 `knowledge_product_type`；

但本轮**不要改现有 A 维数据**，避免影响现有识别。

---

# 15. 品类判断的推荐两阶段模式

未来 AI 不要一次直接输出几十个业务 category code。

推荐：

```text
第一阶段：
判断稳定业务大类

第二阶段：
判断具体 product_type
```

例如：

```json
{
  "categoryCode": "FC",
  "productTypeCode": "NIGHTSTAND"
}
```

而不是给床头柜新建一个业务编码。

又例如：

```json
{
  "categoryCode": "FS",
  "productTypeCode": "LOUNGE_CHAIR"
}
```

### 新增独立类才输出新 categoryCode

例如：

```json
{
  "categoryCode": "MT",
  "productTypeCode": "HYBRID_MATTRESS"
}
```

---

# 16. 与 RSPU / RSKU 编码的兼容规则

## 16.1 现有产品

禁止执行：

```sql
UPDATE rspu_master
SET category_code = ...

UPDATE rspu_master
SET rspu_code = ...

UPDATE rsku_supply
SET rsku_code = ...
```

现有产品历史编码永不因为新分类模型自动变化。

---

## 16.2 新 product_type

`product_type` 不进入编码。

例如：

```text
rspu_code:
FS-MC-021-M

product_type:
LOUNGE_CHAIR
```

以后产品类型从：

```text
LOUNGE_CHAIR
```

人工改为：

```text
ARMCHAIR
```

也不能导致：

```text
rspu_code
```

改变。

---

## 16.3 新业务品类

新业务品类只影响**启用以后新录入的产品**。

例如：

```text
MT-CR-001-M
MR-IL-001-M
RG-WJ-001-L
```

旧产品不迁移。

---

# 17. 新品类必须采用 Feature Flag

为了保证上线前完全不影响当前识别：

建议新增配置：

```text
ai.category.extended.enabled=false
ai.category.extended.codes=DK,MT,MR,RG,PD,CW
```

默认：

```text
false
```

在关闭状态下：

```text
VisionService
```

仍只向模型暴露现有九大类。

新数据可以提前全部进库，但不参与分类。

验证后：

```text
dev / staging:
true

production:
灰度开启
```

---

# 18. 推荐 Shadow Mode

新增品类启用之前，对真实图片做旁路识别：

```text
正常业务：
旧九大类识别
   ↓
正常保存

旁路：
扩展品类识别
   ↓
只记录 shadow result
   ↓
不改 RSPU
```

建议记录：

```text
recognition_id
legacy_category
extended_category
product_type
six_dim_result
confidence
difference_reason
model_version
created_at
```

用于回答：

```text
新增 DK 后，多少原来被识别成 OF 的家庭书桌会被 DK 命中？

新增 MT 后，是否还有床垫被识别成 BD？

新增 PD 后，屏风是否还会误判 FC？
```

---

# 19. 前面“知识层补全方案”的最终结构

在本次品类 / 六维扩展之外，继续保留上一轮设计：

```text
Core Business
现有业务域
────────────────────
RSPU
RSKU
Factory
Quote
Import
Order

            ↑ 稳定 ID 关联

Knowledge Layer
知识增强域
────────────────────
Product Type
Six-Dim Knowledge
Style Knowledge
Room Knowledge
Matching Knowledge
Material Knowledge
Color Knowledge
Anti-pattern
Reference Image
```

## 19.1 Style

新增：

```text
knowledge_style_node
knowledge_style_relation
knowledge_style_profile
```

现有 `style_matching_formula` 第一阶段不改。

---

## 19.2 Room → Product

新增：

```text
knowledge_room_product_rule
```

例如：

```text
LIVING_ROOM
required:
  SF

recommended:
  TB
  FS
  FC
  RG
  LT

optional:
  MR
  PD
```

卧室：

```text
BEDROOM
required:
  BD

recommended:
  FC/NIGHTSTAND
  MT
  LT

optional:
  FS
  RG
  MR
  CW
```

---

## 19.3 Product × Product

新增：

```text
knowledge_product_matching_rule
```

例如：

```text
SOFA × COFFEE_TABLE
DINING_TABLE × DINING_CHAIR
BED × NIGHTSTAND
DESK × CHAIR
RUG × SOFA_GROUP
MIRROR × CONSOLE_TABLE
```

---

## 19.4 Reference Images

现有 410 张参考图建议新增独立关联表：

```text
knowledge_style_reference_image
```

记录：

```text
style
room
image
color tags
material tags
form tags
lighting
mood
embedding
quality score
```

不要第一阶段直接修改原有评分逻辑。

---

# 20. Codex 实施文件建议

以下以当前仓库为基线，Codex 应在开始操作时再次确认实际路径。

## 20.1 必查文件

```text
database/schema/02_product.sql
database/schema/zz_seed.sql
database/ops/reset_db.sql

scripts/generate_six_dim_dict_seed.js

docs/08-roadmap/六维标签体系完善方案.md
```

当前 `generate_six_dim_dict_seed.js` 的职责是：

```text
硬编码各品类 A/B/C/D/F 枚举
↓
生成 category_dict six_dim_A/B/C/D/F
↓
同步写入 zz_seed.sql / reset_db.sql
```

因此新品类六维应优先加入生成器的数据源，不要直接手写两份 seed 导致漂移。

---

# 21. 推荐的 Codex 修改步骤

## Phase A：仅补数据模型，不改变线上行为

### A1. 新增 `knowledge_product_type`

新增迁移，独立表。

不要在 `rspu_master` 增加必须字段。

若后续需要绑定：

```text
rspu_product_type
```

采用独立关联表：

```text
rspu_id
type_code
is_primary
source
confidence
review_status
```

这样 `rspu_master` 仍保持零侵入。

### A2. 新增 product_type 种子

至少补本文件第 5 节列出的产品类型。

### A3. 新增扩展品类数据资产

先准备：

```text
DK
MT
MR
RG
PD
CW
```

六维完整数据。

### A4. 新增 Feature Flag

默认 false。

### A5. 增加测试

确保开关 false 时旧系统返回结果与修改前一致。

---

## Phase B：扩展六维生成器

在：

```text
scripts/generate_six_dim_dict_seed.js
```

新增：

```text
LIST_DK
LIST_MT
LIST_MR
LIST_RG
LIST_PD
LIST_CW
```

并追加：

```text
CATEGORY_LISTS
```

要求：

1. 原已有 LIST 不修改；
2. 旧数据生成结果 byte-level 尽量保持稳定；
3. 新增数据只追加；
4. E 维不生成；
5. 每个 enum 都有：
   - name
   - aliases
   - remark

---

## Phase C：新增 six_dim_schema

为新增品类增加 6 行：

```text
A
B
C
D
E
F
```

对应本文件定义。

---

## Phase D：Shadow 识别

Feature Flag false 时：

```text
不进入正式结果
```

只在后台或测试接口执行扩展分类。

---

## Phase E：分批启用

建议顺序：

```text
第一批：
DK
MT

第二批：
MR
RG
PD

第三批：
CW
```

理由：

- DK / MT 是现有九类最明显无法准确覆盖的核心家具；
- MR / RG / PD 对全案价值高，但对现有产品主链影响较低；
- CW 带软装和安装属性，应最后启用。

---

# 22. Codex 必须增加的回归测试

## 22.1 旧品类回归

至少覆盖：

```text
FS
SF
TB
FC
BS
OF
DT
BD
LT
```

验证：

```text
category code 不变
six_dim schema 不变
旧枚举仍存在
rspu code generation 不变
rsku code generation 不变
```

---

## 22.2 新品类 schema 测试

每个新增品类：

```text
6 个 schema 全部存在
A/B/C/D/F 枚举非空
E 不存在独立 six_dim_E 枚举
parent_code 正确
dict_code 前缀正确
aliases JSON 正确
```

---

## 22.3 幂等测试

重复执行：

```text
make init-db
make seed
node scripts/generate_six_dim_dict_seed.js
```

不应产生：

```text
主键冲突
重复字典
重复 schema
```

---

## 22.4 编码保护测试

准备一组已有 RSPU / RSKU fixture：

```text
修改前记录 code
↓
执行新 migration / seed
↓
比较 code
```

必须：

```text
100% 不变
```

---

# 23. AI 六维识别的新增通用规则

建议在 Prompt 中加入以下约束：

```text
1. 只能判断图片或商品文字中可以确认的可见事实。
2. 不允许根据风格、品牌、价格猜内部结构。
3. A/B/C/D/F 优先从当前品类枚举选择。
4. 无法确认时输出“异形/其他”或“无明显功能”。
5. E 必须复用 material / fabric 标准字典。
6. product_type 和 six_dim 是两个不同任务：
   - product_type = 这是什么具体产品
   - six_dim = 它长什么样
7. 不允许把风格词写入六维：
   - “意式”
   - “中古”
   - “法式”
   这些只能进入 style。
8. 不允许把纯颜色写入六维。
```

---

# 24. 数据质量规则

每个六维枚举必须满足：

### 1. 可观察

模型从白底产品图或明确说明中能判断。

### 2. 与其他维度不重复

例如：

```text
台面悬浮 → B
底座悬浮 → D
```

不能两个维度同时定义同一概念。

### 3. 不把“产品名称”硬塞进形态

例如：

```text
床头柜
```

是 product_type；

```text
矮柜
双抽屉
高脚
```

才是 six_dim。

### 4. 不存纯风格

错误：

```text
A = 意式极简
```

正确：

```text
style = IL
A = 低矮横向
D = 悬浮底座
```

### 5. 不猜不可见内部材料

床垫尤其注意：

```text
“独立袋装弹簧”
```

如果只有外观图而无剖面 / OCR / 规格，不允许判断。

---

# 25. 建议的最终数据结构

最终一个产品应形成：

```text
RSPU
│
├─ Identity
│   ├─ rspu_id
│   └─ rspu_code
│
├─ Business
│   ├─ category_code
│   ├─ style
│   ├─ price
│   └─ status
│
├─ Semantic
│   ├─ product_type
│   ├─ scene
│   ├─ room
│   └─ style_variant
│
├─ Visual Structure
│   ├─ six_dim.A
│   ├─ six_dim.B
│   ├─ six_dim.C
│   ├─ six_dim.D
│   ├─ six_dim.E
│   └─ six_dim.F
│
├─ Material / Color
│
├─ Embedding
│
└─ Supply
    └─ RSKU
```

这比：

```text
所有信息都塞进 category_code / rspu_code
```

更适合长期企业系统。

---

# 26. 第一批建议实际补全的数据量

建议本轮先控制在一个可验证规模：

## product_type

约：

```text
50~70 个
```

覆盖九大现有品类的主要细分类。

## 新业务 category

第一轮：

```text
DK
MT
MR
RG
PD
```

CW 先建数据但默认不启用。

## 六维枚举

每个新增类建议：

```text
A：7~10
B：7~10
C：6~10
D：7~10
F：7~12
```

合计新增约：

```text
220~280 条 six_dim 枚举
```

不建议一开始追求 1000+ 条。

---

# 27. 后续第二批可研究品类

等第一批稳定后，再评估：

```text
KT  定制橱柜 / 厨房系统
BV  浴室柜
PL  花器 / 花盆
AR  艺术装饰
AC  家居配饰
OU  户外遮阳
```

其中：

```text
定制橱柜
浴室柜
```

不一定适合沿用普通家具六维，它们更可能需要：

```text
模块组成
柜体布局
门板系统
台面
五金
功能模块
```

建议另建“系统家具 / 定制家具”属性体系，不要提前硬塞进现在的六维。

---

# 28. 风格数据库补全的安全路线

本轮品类是主任务，但风格知识建议顺手按以下方式准备，不直接改现有评分：

## 28.1 保留当前 17 个 style code

不改旧 code。

## 28.2 新增父子风格知识

例如：

```text
IT 意式
├─ 意式极简
├─ 意式现代
└─ 意式轻奢

FR 法式
├─ 法式自然
├─ 法式复古
└─ 法式奶油
```

通过：

```text
knowledge_style_node
knowledge_style_relation
```

实现。

## 28.3 新风格 profile 不覆盖旧 formula

建立：

```text
knowledge_style_profile
```

包含：

```text
core_traits
preferred
compatible
discouraged
conflict
color_profile
material_profile
form_profile
texture_profile
lighting_profile
mood_profile
```

先 Shadow，不参与现有评分。

---

# 29. 不允许 Codex 做的事情

明确禁止：

```text
1. 重算历史 rspu_code
2. 重算历史 rsku_code
3. 为了新的 product_type 自动拆分历史 RSPU
4. 自动修改历史 category_code
5. 自动修改历史 primary style
6. 修改旧品类六维含义
7. 删除当前 GENERIC 兜底
8. 删除旧 style_matching_formula
9. 让新增知识规则直接覆盖当前生产评分
10. 把 product_type 塞进 RSPU 编码
```

---

# 30. 最终推荐实施顺序

```text
Step 1
冻结现有编码与旧九类行为

Step 2
新增 knowledge_product_type

Step 3
补 50~70 个现有品类二级 product_type

Step 4
准备 DK / MT / MR / RG / PD / CW 六维数据

Step 5
扩展 generate_six_dim_dict_seed.js

Step 6
补 six_dim_schema

Step 7
Feature Flag 默认关闭

Step 8
Shadow Mode 跑真实产品图

Step 9
人工复核误判边界

Step 10
先启用 DK / MT

Step 11
再启用 MR / RG / PD

Step 12
最后决定是否启用 CW

Step 13
Marketing Agent 开始消费 product_type + six_dim + room rules

Step 14
新知识评分经过灰度后才参与正式推荐排序
```

---

# 31. 验收标准

本轮完成后，应满足：

### 业务兼容

- 所有旧 RSPU 编码不变；
- 所有旧 RSKU 编码不变；
- 九大旧品类识别开关关闭时结果不变；
- 旧六维数据无需迁移即可继续展示；
- 报价、订单、同款判定无回归。

### 数据覆盖

- 九大旧类拥有 50~70 个语义子类型；
- 新增 5 个正式候选结构类 + 1 个软装候选类；
- 每个新类均有 A~F Schema；
- 每个新类 A/B/C/D/F 有标准枚举；
- E 统一复用 material/fabric。

### Agent 能力

系统可以从：

```text
“给我选一个床头柜”
```

得到：

```text
category = FC
product_type = NIGHTSTAND
```

从：

```text
“给我选一张家庭书桌”
```

得到：

```text
category = DK
product_type = WRITING_DESK
```

从：

```text
“这个卧室还缺什么”
```

通过 Room Knowledge 判断：

```text
BED
MATTRESS
NIGHTSTAND
LIGHT
RUG
MIRROR
CURTAIN
```

而不是只会检索已有沙发 / 茶几。

---

# 32. 结论

本轮不应以“新增越多一级品类越好”为目标。

最优结构是：

```text
现有稳定九大业务品类
+
二级 product_type 大量补全
+
少量真正独立的新结构品类
+
对应六维 Schema
+
旁路 Knowledge Layer
+
Feature Flag / Shadow Mode
```

第一批最值得新增独立六维的品类：

```text
DK 书桌/写字台
MT 床垫
MR 镜子
RG 地毯
PD 屏风/隔断
CW 窗帘/窗饰（先数据、后启用）
```

而：

```text
餐椅
休闲椅
长凳
脚踏
边几
套几
玄关台
电视柜
餐边柜
床头柜
斗柜
衣柜
书柜
鞋柜
```

优先通过：

```text
product_type
```

补全，不扩大 RSPU 一级编码。

这样既能明显提高平台的全案品类覆盖，又不会破坏现在已经跑通的：

```text
录入
→ AI 识别
→ 人工复核
→ RSPU
→ Variant
→ RSKU
→ 报价
→ 同款
→ 订单
```

业务链路。

---

# 33. 参考资料

## RSDP 当前仓库

- `database/schema/02_product.sql`  
  `https://raw.githubusercontent.com/Chen-Minghuan/rsdp/main/database/schema/02_product.sql`

- `database/schema/zz_seed.sql`  
  `https://raw.githubusercontent.com/Chen-Minghuan/rsdp/main/database/schema/zz_seed.sql`

- `scripts/generate_six_dim_dict_seed.js`  
  `https://raw.githubusercontent.com/Chen-Minghuan/rsdp/main/scripts/generate_six_dim_dict_seed.js`

- `docs/08-roadmap/六维标签体系完善方案.md`  
  `https://raw.githubusercontent.com/Chen-Minghuan/rsdp/main/docs/08-roadmap/%E5%85%AD%E7%BB%B4%E6%A0%87%E7%AD%BE%E4%BD%93%E7%B3%BB%E5%AE%8C%E5%96%84%E6%96%B9%E6%A1%88.md`

## 市场品类参考

- IKEA Products  
  `https://www.ikea.com/us/en/cat/products-products/`

- Minotti Product / Catalogue  
  `https://www.minotti.com/en/general-catalogue-the-complete-interior-collection`  
  `https://www.minotti.com/en/the-world-of-materials`

- Molteni&C Products  
  `https://www.molteni.it/en/eu/products`

- B&B Italia Products  
  `https://www.bebitalia.com/en-us/products`

- Architonic Furniture  
  `https://www.architonic.com/en/products/furniture/0/3210002/1`

- Architonic Chairs  
  `https://www.architonic.com/en/products/lapalma-chairs/3100076/3238273/1`

- Architonic Console Tables  
  `https://www.architonic.com/en/products/dreieck-design-console-tables/3101413/3238228/1`

- Steelcase Products  
  `https://www.steelcase.com/products/`

- Google Product Category 说明  
  `https://support.google.com/merchants/answer/6324436`

---

# 34. 可直接交给 Codex 的任务描述

```text
请基于当前 rsdp 仓库最新 main 分支执行《RSDP 产品品类与六维数据扩展方案（不影响现有业务流程版）》。

核心要求：

1. 先审计当前代码，确认 category / six_dim_schema / six_dim generator / VisionService / RSPU 编码 / RSKU 编码实际消费点。
2. 禁止修改任何已有 RSPU / RSKU 业务编码。
3. 禁止迁移现有九大品类数据。
4. 新增 knowledge_product_type，用于二级产品类型，不参与 RSPU 编码。
5. 先补现有 9 类的 product_type。
6. 准备 DK/MT/MR/RG/PD/CW 六套六维数据。
7. E 维继续复用 material/fabric。
8. 扩展 generate_six_dim_dict_seed.js，由生成器统一产出新增枚举，禁止手写多个副本造成漂移。
9. 新增六维 schema。
10. 为扩展 category 增加 feature flag，默认关闭；关闭时旧业务行为必须与当前一致。
11. 增加 shadow 识别能力或至少预留记录结构，使扩展分类可验证而不修改正式 RSPU。
12. 增加完整回归测试：旧九类、seed 幂等、业务编码不变、新 Schema 完整性。
13. 不直接修改旧 style_matching_formula；风格/空间/搭配知识按旁路 Knowledge Layer 方案落地或预留。
14. 所有数据库变更走当前项目既有 migration / schema 同步约定。
15. 完成后输出：
    - 修改文件清单
    - 数据库变更
    - 新增类别/枚举统计
    - 兼容性说明
    - 测试结果
    - 尚未启用的 feature flag
    - 风险与后续建议

不要为了“统一”重构当前已经稳定运行的编码、报价、订单、同款链路。
```
