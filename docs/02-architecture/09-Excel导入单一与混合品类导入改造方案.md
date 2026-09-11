# RSDP Excel 单一 / 混合品类导入改造方案

> 版本：v2.2 · 日期：2026-09-10 · 状态：方案定稿，待实施。
> v2.2：第三轮评审五条修订——导入方式移至 Step 2 顶部（per-sheet 时序）；AI 归一 fallback
> 推迟到候选集确定后；清除 fixedCategoryCode 残留；提交只认 rowCategorySelections；
> 逻辑产品边界复用现有 RSPU 分组规则，不另建 key。
> v2.1：第二轮评审六条修订——SINGLE 统一为「默认品类」语义；DTO 统一命名 categoryHint；
> Sheet 名降级为 AI 上下文；AI 归一受候选集限制；逻辑产品品类唯一性校验；更新模式跨品类保护。
> v2.0：落实与现状机制的对齐结论（第 26 节），含七条评审判断。

## 1. 背景

当前 RSDP Excel 录入流程主要按照"一份 Sheet 对应一个商品品类"的方式处理，通过统一品类提示完成商品录入。

实际供应商资料中存在两类 Excel：

| 场景 | 示例 |
|---|---|
| 单一品类 | 整个 Sheet 全部为餐椅 |
| 混合品类 | 同一 Sheet 同时存在餐桌、餐椅、茶几 |

对于混合品类，如果仍然要求整份 Sheet 指定一个品类，会造成错误分类；如果完全交给 AI 从全部品类中自由判断，又会扩大识别范围和误判概率。

因此增加 **单一品类 SINGLE / 混合品类 MIXED** 两种导入方式。

## 2. 设计目标

本次改造只解决三个核心问题：

- **SINGLE**：用户指定一个**默认品类**，整批商品默认使用该品类（行内类别列归一命中时以行内值为准），不进行品类 AI 分类。
- **MIXED**：用户提前指定该 Sheet 包含的多个候选品类，AI 只在这些候选品类中逐行给出推荐。
- 最终品类由用户在数据清洗阶段检查和修改，确认无误后才执行正式导入。

AI 定位为：**品类录入辅助工具，而不是最终品类决策者。**

## 3. 总体流程

```
              上传 Excel → Preview / 解析 Sheet → 选择当前 Sheet
                                │
              （此时系统才知道有哪些 Sheet，导入方式 per-sheet）
                                ↓
                    Step 2 顶部：选择导入方式
                    /                        \
                SINGLE                       MIXED
                  │                            │
            指定1个默认品类              指定多个候选品类
                  │                            │
            不进行品类AI                限定AI识别范围
                  │                            │
                  │                  候选集约束的 AI 归一/逐行预填
                  │                            │
                  └─────────────┬──────────────┘
                                ↓
                            确认字段映射
                                ↓
                            数据清洗
                                ↓
                        人工检查 / 修改
                                ↓
                        每行最终品类完整
                                ↓
                            正式导入
                                ↓
                              RSPU
```

## 4. 导入方式选择的位置：Step 2 顶部，不在 Step 1

**Step 1 只做上传 + Preview + 选择当前 Sheet，不选导入方式。** 原因是一个时序事实：
上传完成前系统不知道文件里有哪些 Sheet（现有前端上传后调 `runPreview(file, sheetIndex)` 才能拿到
`sheets` / `currentSheetIndex`，切换 Sheet 又会重新 Preview），而导入方式是 per-sheet 的——
"当前 Sheet" 不存在时无法选择它的导入方式。不为此新增"先解析 Sheet、再 Preview"的接口。

因此导入方式放在 **Step 2（确认字段映射）顶部**，当前 Sheet 上下文已确定：

```
当前 Sheet：黑胡桃木高定系列

导入方式 *

● 单一品类导入        ○ 混合品类导入
  当前 Sheet 中的商品    当前 Sheet 中包含多个
  均属于同一个品类        商品品类
```

这正是 4.2 的含义：SINGLE 就是升级现有「品类提示」控件的位置和形态，而不是新增一套 UI。

### 4.1 SINGLE 单一品类

Step 2 顶部选择 SINGLE 后显示：

```
商品品类 *

[ 餐椅 ▼ ]
```

要求：必须选择且只能选择一个品类。

例如：

```
mode = SINGLE
categoryHint = DINING_CHAIR
```

后续所有商品**默认** `finalCategoryCode = DINING_CHAIR`（行内类别列归一命中的行除外，见 18）。

**SINGLE 不进行品类 AI 识别**——用户已经明确告诉系统这批商品默认是餐椅，继续让 AI 判断没有业务收益，反而可能产生错误。

### 4.2 SINGLE 在数据层的实现：升级现有「品类提示」，不建新字段、不起新名

现有 Step 2 已有「品类提示」（全文件单一品类兜底，存 `excel_import_batch.category_hint`，VARCHAR(16)）。SINGLE 的默认品类与它是同一东西，实施时：

- **DB 层直接复用 `category_hint` 列**，不新增 `fixed_category_code` 字段；
- **DTO/前后端统一叫 `categoryHint`**，不出现 fixedCategoryCode 等第二个名字——跨层两个名字指同一东西是理解成本的来源；
- UI 上把「品类提示」升级为「导入方式 + 默认品类」的一组控件，**不并存两套差不多的选择**；
- 选择 SINGLE 后原品类提示控件不再单独出现（其值即 categoryHint）。

## 5. MIXED 混合品类

Step 2 顶部选择 MIXED 后：

```
该 Sheet 包含的品类 *

[ 餐桌 × ] [ 餐椅 × ] [ 茶几 × ]
```

要求至少选择两个品类。例如：

```
mode = MIXED
candidateCategoryCodes = [DINING_TABLE, DINING_CHAIR, COFFEE_TABLE]
```

这些品类代表的是 **AI 的候选识别范围**，并不是整份 Excel 的最终品类。

## 6. MIXED 为什么要求先选候选品类

假设系统有几十个品类，而这份供应商 Excel 只有餐桌、餐椅、茶几。用户先限定候选集后，AI 的任务从"这个商品是什么家具？"变成"这个商品在餐桌/餐椅/茶几中属于哪一个？"——有效缩小分类空间，减少相似品类误判。

## 7. MIXED 必须允许 AI 返回"无法识别"

AI 不能被强制必须 N 选一。例如某行实际是休闲椅，但用户第一步漏选了休闲椅，强制选择很可能把休闲椅错误识别为餐椅。

因此 AI 输出必须允许：`候选集中的任一码` 或 `null`。**在候选集合中无法判断时返回 null，禁止强制猜测。**

## 8. Step 2：字段映射

第二步仍然只负责 Excel 字段 → RSDP 标准字段，不承担品类决策逻辑。

## 9. Excel 本身存在品类字段时优先利用

如果混合 Excel 本身存在「类别/品类/产品类型/Category」列，优先利用已有数据：

```
Excel 品类值 → 系统字典/别名匹配 → 命中则直接生成推荐品类 → 未命中再交 AI
```

**实现上复用现有「品类名归一」机制**（`suggestCategoryMappings` / `normalizeCategoryCode`：字典码 → 字典中文名 → 别名库 → AI 批量归一），它是唯一归一入口，不新写一套。这样本来 Excel 已写明"餐椅"的行不浪费 AI 调用。

**与候选集的关系（MIXED 下）**：

- **确定性匹配（字典码 / 字典中文名 / 别名库）不受候选集限制**——这是 Excel 自己的数据，可信；命中候选集外的品类直接采用，人工可在清洗页改；
- **AI 归一与逐行 AI 推荐一样，必须输出 ∈ candidateCategoryCodes**，超出候选集的结果置 null 按未识别处理。

**AI 调用时序（关键约束）**：现有 `suggestCategoryMappings` 在 Preview 阶段执行且包含 AI 批量归一，但 Preview 时用户尚未选择 MIXED 候选集，无约束的 AI 不能提前跑完再事后筛掉。因此把归一拆成两个时机：

```
上传 Preview
    ↓
确定性归一（字典码 / 中文名 / 别名库）          ← Preview 阶段照常执行
    ↓
用户在 Step 2 顶部选择 MIXED + 候选品类
    ↓
对仍未归一的值执行【候选集约束的 AI 归一】      ← 推迟到候选集确定后
    ↓
逻辑产品级文本分类（同样受候选集约束）
```

即：`normalizeCategoryCode` 复用不变，但 MIXED 下的 AI fallback 部分调整调用时机，推迟到
`candidateCategoryCodes` 确定之后。不新建一套 Category Service，只调整调用时机。
（旧路径：用户不选导入方式时，Preview 阶段的归一行为维持现状。）

## 10. Step 3：数据清洗

数据清洗是混合品类最终人工检查的位置。列名用「**商品品类**」（不是"品类（AI）"），因为 AI 并不是最终决策者：

```
行号  图片  型号       商品品类      提示
27    图    WG-25551   餐椅 ▼       AI 建议
28    图    WG-25552   餐椅 ▼       AI 建议
29    图    WG-H23-51  茶几 ▼       AI 建议
30    图    WG-22331   请选择 ▼     未识别
```

## 11. MIXED 的人工确认方式

不要求逐行点确认。正确交互：

```
AI 预填商品品类 → 用户浏览检查 → 发现错误才修改 → 点击「执行导入」= 接受当前清洗结果
```

"人工确认"是用户检查并提交整个数据清洗结果，不是每行额外点击确认按钮。

## 12. 用户可以选择候选范围之外的品类

AI 可选范围 ≠ 人工可选范围：

- **AI** → 仅允许 `candidateCategoryCodes`
- **人工修改** → 可以选择系统全部有效商品品类

例如清洗时发现第 31 行其实是休闲椅，人工下拉直接选休闲椅，不用退回第一步重配候选集。这同时兼顾 AI 准确率和人工纠错能力。

## 13. 批量修改

数据清洗页支持基础批量修改：

```
☑ WG-25551  ☑ WG-25552  ☑ WG-25553  ☑ WG-25555
已选择 4 行
批量设置商品品类：[ 餐椅 ▼ ]
```

AI 大量识别错误或某一组产品需要调整时，不需要逐行修改。**第一版只做普通批量设置，不做自动分组和复杂聚类。**

## 14. AI 品类识别输入

MIXED 模式把当前行已存在的文本信息传给模型：

```
产品名称 / 型号 / Excel 原始品类 / 尺寸 / 配置 / 描述 / 备注 / Sheet 名称 / candidateCategoryCodes
```

**V1 文本 only：图片继续给人看（清洗页缩略图），不进入 AI 品类分类。** 图片辅助分类列入第 24 节"本期不做"。文本信息不足的行（如只有型号编码）返回 null，由第 13 节批量设置兜底。

例如：

```
Sheet：黑胡桃木高定系列
型号：WG-25551
尺寸：680*700*1030 坐高450
配置：北美胡桃木扶手 软包
包装：1张/箱
候选：餐桌 / 餐椅 / 茶几
→ AI 返回：DINING_CHAIR
```

**去重按逻辑产品而不是机械的行**：同一逻辑产品的多价格列变体行只分类一次、共享结果，不重复消耗 LLM 调用。

去重与品类一致性校验**复用现有 Excel 导入确定 RSPU / 逻辑产品边界的规则**（含 forward-fill、模块行继承语义），不另建一套与正式导入不同的分组规则——规格/模块若进入 key，会把一个 RSPU 的 1800/2000/2200 规格拆成三个"逻辑产品"，分类重复且一致性校验失效。实现上若现有分组逻辑没有独立可复用的方法，把它抽成 shared helper 供 AI 分类和正式导入共同调用，而不是各跑一套。

## 15. AI 输出约束

要求结构化返回：

```json
{ "categoryCode": "DINING_CHAIR" }
```

后端校验 `categoryCode ∈ candidateCategoryCodes`（且候选集本身在第一步只能来自字典选项，天然合法），否则 `categoryCode = null`。**不能让 AI 自己创建 RSDP 不存在的品类。**

## 16. 配置模型（请求级，不落新表字段）

```java
public class ExcelImportCategoryConfig {
    /** SINGLE / MIXED */
    private CategoryMode mode;
    /** SINGLE 的默认品类；与现有「品类提示」同一字段，DB 复用 excel_import_batch.category_hint */
    private String categoryHint;
    /** MIXED 使用（≥2 个），随导入请求提交 */
    private List<String> candidateCategoryCodes;
}

public enum CategoryMode { SINGLE, MIXED }
```

导入方式按 **per-sheet（批次）** 生效：多 Sheet 文件每个 Sheet 独立批次、独立选择导入方式，与现有批次模型一致。

## 17. 行级品类数据的载体

清洗阶段（步骤 3）**还没有行记录**——现有实现中行记录（`excel_import_row`）在「执行导入」时才创建，清洗阶段的行数据只存在于 `excel_import_batch.preview_rows`（JSONB）+ 前端内存，编辑通过 `previewEdits` / `skipRows` 随导入请求提交。

因此：

- `suggestedCategoryCode`（系统/AI 初始推荐）只是**前端展示状态**（用于渲染「AI 建议」标记），**不提交给后端**；
- 确认导入请求只携带一个行级集合——用户点击执行导入时，表格里的当前值就是最终输入：

```java
/** 数据清洗后的行级最终品类：Excel 物理行号（1-based）→ 品类字典码 */
private Map<Integer, String> rowCategorySelections;
```

- 后端只认 `rowCategorySelections`，不需要理解 suggested / final / confirmed / manual 一堆状态概念；与现有 `previewEdits` / `skipRows` 同模式；
- **不为此提前给 `excel_import_row` 加字段**；导入时最终值写入行记录的 `mapped_fields` 快照即可。

## 18. SINGLE 数据处理

用户在 Step 2 顶部选择餐椅作为**默认品类**：

```
suggestedCategoryCode = DINING_CHAIR（即 categoryHint，前端直接预填）
finalCategoryCode     = DINING_CHAIR
```

**SINGLE 的语义统一为「默认品类/兜底品类」，不是「固定品类」**：行内类别列归一成功的值优先于 categoryHint，categoryHint 只补空值行。理由：原始数据优先原则，且 `category_hint` 列的语义本来就是"兜底提示"而非"强制覆盖"。用户发现某行实际是休闲椅，直接在清洗页改 `finalCategoryCode = LOUNGE_CHAIR`。

## 19. MIXED 数据处理

```
candidateCategoryCodes = 餐桌 / 餐椅 / 茶几

行内类别列归一命中      → suggested = 命中码（可不在候选集内，人工可改）
AI 判断 DINING_CHAIR   → suggested = DINING_CHAIR, final = DINING_CHAIR
AI 无法识别            → suggested = null, final = null，等待手工选择
```

用户认为正确则不操作；认为错误则改 finalCategoryCode。

## 20. 正式导入校验

**只拦"品类前置校验"这一道，导入本身保持逐行容错**（现有失败清单机制不变）：

- 前端在第 3 → 4 步拦截：仍有未确定品类的非跳过数据行时不允许进入执行导入，提示"仍有 5 行商品品类未确定，请完成数据清洗后再执行导入 [查看未确定商品]"；
- 后端 `confirmAndImport` 兜底再校验一次（防绕过）：所有非跳过、非系统过滤行（说明行/重复表头行/组合汇总行，复用现有 `isNoteOrEmptyRow` 等判定）必须有 finalCategoryCode，否则整批拒绝并返回行号清单；
- **同一逻辑产品的品类唯一性校验**：同一逻辑产品（边界判定复用现有 RSPU 分组规则，见 14）下的所有非跳过行必须共享同一个最终品类；若人工把同一逻辑产品的多行改成了不同品类，前置校验一并拦截并给出冲突行号清单——一个 RSPU 只能有一个品类；
- 校验通过后的导入流程与现状一致：单行失败进失败清单，不影响其他行。

## 21. 正式产品表不增加 AI 临时字段

不在 `rspu_master` 增加 `suggested_category_code / ai_category / category_confidence` 等字段。最终进入主数据的只有：

```
finalCategoryCode → rspu_master.category_code
```

AI 过程信息存在导入批次/预览阶段即可。

### 21.1 更新模式（updateIfExists=true）的跨品类保护

导入命中已有 RSPU（externalCode 相同）时，**品类字段不更新**：若 finalCategoryCode 与已有 `rspu_master.category_code` 不一致，保留原品类，并在该行结果的 issues 里记一条用户可见提示「品类与已有商品不一致（已有：X，本次：Y），已保留原品类」。

理由：已有商品的品类可能经过人工治理，导入链路的 AI/批量操作不应静默把 RSPU 改到其他品类；真正要改品类走产品管理。其余字段的更新语义不变。

## 22. 前端校验规则

| Mode | 校验 |
|---|---|
| SINGLE | categoryHint 必填 |
| MIXED | candidateCategoryCodes ≥ 2 |

```js
if (categoryMode === 'SINGLE' && !categoryHint) {
  return '请选择默认商品品类'
}
if (categoryMode === 'MIXED' && candidateCategoryCodes.length < 2) {
  return '混合品类导入请至少选择两个商品品类'
}
```

## 23. 页面最终效果

Step 2 顶部（SINGLE）：

```
当前 Sheet：餐椅明细

导入方式
● 单一品类  ○ 混合品类

默认商品品类 *
[ 餐椅 ▼ ]

──── 以下为字段映射表格 ────
```

Step 2 顶部（MIXED）：

```
当前 Sheet：黑胡桃木高定系列

导入方式
○ 单一品类  ● 混合品类

该 Sheet 包含的商品品类 *
[ 餐桌 × ] [ 餐椅 × ] [ 茶几 × ]

──── 以下为字段映射表格 ────
```

数据清洗：

```
共 100 行   3 行商品品类未识别   [只看未确定商品]

□ | 图片 | 型号       | 商品品类       | 提示
------------------------------------------------
□ | 图   | WG-H24-70  | 餐桌 ▼         | AI建议
□ | 图   | WG-25551   | 餐椅 ▼         | AI建议
□ | 图   | WG-H24-89  | 茶几 ▼         | AI建议
□ | 图   | WG-22331   | 请选择品类 ▼   | 未识别

[批量设置品类]                 [执行导入]
```

## 24. 本期明确不做

为控制范围，本期排除：

- AI 置信度百分比、高/中/低置信状态
- Top3 品类推荐
- 图片辅助品类分类（图片只给人看）
- 自动品类聚类
- 用户历史选择学习
- 自动新增品类、自动修改系统品类体系
- 全品类 AI 自由识别模式
- 复杂 Category Resolution 状态机
- Agent 编排、LangChain4j / Spring AI 工作流
- 每行单独确认按钮

这些等真实导入数据证明有需要以后再做。

## 25. 最终结论

```
SINGLE：用户选一个默认品类，整批默认使用（行内类别列归一命中时以行内值为准）。
MIXED：用户先选多个候选品类，AI 在限定范围内逐行预填，用户在数据清洗阶段检查、修改，最终提交。
```

职责划分：

- **用户** → 决定这批数据属于哪些业务品类范围
- **AI** → 帮助用户完成行级品类分配
- **数据清洗** → 用户最终纠错
- **系统** → 校验完整性并安全写入 RSPU

## 26. 与现状机制的对齐（评审结论，实施按此执行）

| # | 结论 | 落地方式 |
|---|---|---|
| 1 | SINGLE 直接升级现有「品类提示」 | DB 复用 `category_hint`，不建 `fixed_category_code`；DTO 统一命名 `categoryHint`；UI 不并存两套选择（4.2、16） |
| 2 | 显式收敛兜底链 | 新链见 26.1；`categoryGuess` 在新模式下退出；SINGLE 语义统一为「默认品类」：行内类别列归一命中优先，默认品类只补空值行（2、18、25） |
| 3 | 复用品类名归一 | `suggestCategoryMappings` / `normalizeCategoryCode` 为唯一归一入口；确定性匹配不受候选集限制，AI 归一与逐行推荐一样必须 ∈ 候选集（9、15） |
| 4 | 清洗阶段无行记录 | suggested/final 走前端状态 + 请求提交（同 `previewEdits`），行表不加字段（17） |
| 5 | 未确定品类整批拦截 | 只拦品类前置校验（前端 3→4 步 + 后端 confirmAndImport 兜底），导入保持逐行容错（20） |
| 6 | V1 文本 only | 图片不进入 AI 品类分类，列入不做清单（14、24） |
| 7 | AI 去重 + per-sheet | 去重键按逻辑产品（forward-fill 后的品名 → 规格 → 编码）（14）；导入方式挂批次（16） |
| 8 | Sheet 名不作直接品类来源 | Sheet 名只作为 AI 分类的上下文线索（prompt 注入），不再作为每行直接品类来源；旧路径（未选模式）维持现状不动（26.1） |
| 9 | 逻辑产品品类唯一 | 正式导入前校验同一逻辑产品只保留一个 finalCategoryCode，冲突整批拦截并给出行号（20） |
| 10 | 更新模式跨品类保护 | updateIfExists 命中已有 RSPU 时品类不更新，不一致记 issues 提示，改品类走产品管理（21.1） |
| 11 | 导入方式在 Step 2 顶部 | Step 1 只上传 + Preview + 选 Sheet；per-sheet 时序决定模式选择必须在 Sheet 上下文确定后（4） |
| 12 | AI 调用时序 | 确定性归一 Preview 照常；候选集约束的 AI（归一 fallback + 逐行分类）推迟到候选集确定后，不提前跑无约束 AI 再事后筛（9） |
| 13 | 提交只认 rowCategorySelections | suggested 仅前端展示用，请求只带最终行级集合（17） |
| 14 | 逻辑产品边界复用现有规则 | 去重/一致性校验复用 RSPU 分组规则（forward-fill/模块继承），需要时抽 shared helper，不另建 key（14、20） |

### 26.1 收敛后的每行品类来源链

| 模式 | 每行品类来源（优先级从高到低） |
|---|---|
| SINGLE | 行内类别列归一命中（确定性） → 默认品类（categoryHint） |
| MIXED | 行内类别列确定性归一命中（可超候选集） → 清洗页 finalCategoryCode（= AI 候选集内推荐 + 人工修改） → 无则整批拦截 |
| 未选模式（兼容旧路径） | 维持现状：行内类别列 → Sheet 名归一 → 品类提示 |

- **Sheet 名在新模式下不再是每行直接品类来源**——一份 Sheet 叫「黑胡桃木高定系列」不代表每行都是同一品类，Sheet 级结论直接定行级品类正是混合品类错标的来源。Sheet 名仅作为 MIXED 逐行 AI 分类的上下文线索注入 prompt。旧路径（未选导入方式）维持现状不动。
- `categoryGuess`（previewMapping 的整文件猜测）：新模式下不再参与兜底；旧路径保留不动。
