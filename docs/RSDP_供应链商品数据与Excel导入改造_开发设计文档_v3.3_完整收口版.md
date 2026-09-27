# RSDP 供应链商品数据与 Excel 导入改造设计文档 v3

> 项目：RSDP
> 仓库：https://github.com/Chen-Minghuan/rsdp.git
> 文档类型：架构评审修订版 / 可实施设计草案
> 版本：v3.3
> 状态：Design Approved（2026-09-22 通过 §85.1 A 类门禁）
> 目标：在不推翻现有 RSPU / Variant / RSKU、Excel AI 导入、营销 Agent、图片与向量能力的前提下，完成供应链商品主数据与导入链路的领域化重构。

> 相关设计：
> - `docs/02-architecture/09-Excel导入单一与混合品类导入改造方案.md`（per-sheet 品类交互与功能规格）
> - `docs/04-decisions/007-RSPU与RSKU业务编码落地实施方案.md`（平台业务编码）
> - `docs/04-decisions/008-供应商源商品身份与Excel导入演进边界.md`（本轮架构决策入口）

## 文档边界与实施顺序（v3.3 最终阻塞项收口版）

09 方案与本文不是两套并行导入方案：

- **09 方案**是 SINGLE / MIXED、候选品类、人工修改与 per-sheet 时序的功能/交互规格；
- **v3** 是 Preview Snapshot、Revision、Issue、Attempt、Lineage 及主数据身份的持久化与一致性规格；
- 发生冲突时，交互语义以 09 方案为准，数据生命周期、并发与安全语义以 v3 为准。

不先在旧 `preview_rows + 前端内存` 模型上独立实施 09，避免随后再迁移一次同一条 Preview / 清洗链路。总体施工顺序固定为：

```text
通过 §85.1 A 类 Design Approval Gate
↓
P0-A：Supplier Identity Expand（此阶段不删 external_code 唯一索引）
↓
P0-B1：按 batch/sheet 建立 Immutable Snapshot + Revision + Optimistic Lock
↓
P0-B2：在 B1 上落实 09 方案的 SINGLE / MIXED / rowCategorySelections
↓
P0-B3：Issue / Confirm / Revalidate 收口
↓
P0-C → P0-D → P0-E → P0-F
↓
通过 §85.2 B 类 Release / Production Migration Gate
↓
生产 Migration / Cutover
```

09 方案因此是 **P0-B 的必交付行为**，不是 v3 之前或 v3 完成后的独立项目。

跨阶段算法前置声明（消除 B → C/D 的隐性前向依赖）：Fingerprint v1 协议（§17.1 Legacy Compatibility Fingerprint）与 Supplier Configuration Key / Resolver 的「算法」随 P0-B 先行实现——Preview Snapshot 固化时必须写入 `variantKey / quoteKey`（二者分别内嵌 `fingerprintVersion` 与 `supplierConfigurationKey`），且禁止导入时重算归一化结果，因此 B1/B2/B3 开发者直接使用这两段算法规格，不得各自重复实现；P0-C / P0-D 负责其数据库持久化、回填、唯一约束切换与升级治理收口（`variant_fingerprint` 表、组合候选键、Supplier Configuration TEXT→CODE 治理等）。

---

# 1. 改造背景

当前项目已经具备：

- RSPU / Variant / RSKU 商品模型；
- `factory_master` 与工厂映射；
- Excel AI 导入；
- Preview / `previewEdits`；
- Excel 行级导入记录；
- 图片资产；
- AI 识别；
- pgvector 图片向量；
- `MarketingProductQueryService`；
- Marketing Agent Graph；
- `RecommendNode` / 推荐理由生成；
- `PricingService`；
- `async_task + afterCommit` 异步任务执行机制。

当前问题不是“缺一个 Excel 导入功能”，而是现有数据模型和导入流程在进入正式供应链、智能选品、自动报价阶段后暴露出以下结构性问题：

1. `rspu_master.external_code` 仍承担全局商品身份；
2. Supplier Source Identity 与平台商品 Identity 没有分离；
3. Variant Identity 仍只覆盖尺寸 / 颜色 / 材质；
4. 模块、材质类型、供应商材质等级没有完整进入领域模型；
5. Excel 当前实际采用逐行事务，无法保证产品聚合一致性；
6. Preview 没有持久化 Immutable Normalized Snapshot；
7. Issue、Revision、Confirm 没有完整版本模型；
8. RSKU 报价语义不足；
9. Lineage 无法做到字段级追溯；
10. Raw / Preview / Lineage 可能重新泄露明文采购成本；
11. Import 请求缺少业务幂等；
12. 推荐排序仍有 LLM 参与；
13. Agent 报价解析已由 `AgentRskuResolver` 统一使用 `PricingService`，但 `MarketingProductQueryService` 的预算 SQL 过滤与搜索结果仍直读 `retail_price`，会漏掉品类/全局加价兜底得到的标准售价；
14. Migration、并发和故障恢复策略不完整。

---

# 2. 核心设计原则

## 2.1 Excel 不是数据库模型

供应商 Excel 只是外部原始数据载体。

正确链路：

```text
Excel
↓
Raw Parse
↓
Normalize
↓
Product Group
↓
Immutable Preview Snapshot
↓
Issue / Review
↓
Confirmed Snapshot
↓
Revalidate
↓
Persist Domain Model
↓
RSPU / Variant / RSKU
↓
Lineage / Price History
↓
AI / Vector
↓
Marketing Agent
```

## 2.2 模型不负责事实

模型只负责：

```text
理解
生成
解析
```

业务服务负责：

```text
商品事实
尺寸事实
价格事实
供应事实
排序事实
权限事实
```

## 2.3 平台身份、供应商身份、供应报价必须分离

```text
平台商品身份
RSPU

平台配置身份
Variant

供应商源商品身份
supplier_product_ref

工厂供应报价身份
RSKU
```

禁止继续使用一个 `external_code` 同时承担以上多个角色。

---

# 3. 项目真实 ID 与数据库规范

本设计所有 DDL 必须遵循当前项目真实约定。

## 3.1 业务 ID

```text
rspu_id      VARCHAR(64)
variant_id   VARCHAR(64)
rsku_id      VARCHAR(64)
image_id     VARCHAR(64)
batch_id     VARCHAR(64)

factory_code VARCHAR(16)
```

## 3.2 自增记录 ID

以下类型适合使用：

```text
BIGSERIAL
```

例如：

```text
excel_import_row.row_id
price_history.history_id
lineage_id
issue_id
mapping_id
```

## 3.3 时间类型

统一：

```sql
TIMESTAMPTZ
```

禁止新设计继续使用无时区的：

```sql
TIMESTAMP
```

除非现有表有明确兼容要求。

---

# 4. 目标总体架构

```text
Supplier Excel
        │
        ▼
┌──────────────────────┐
│ Raw Import           │
│ 原文件 / Sheet / Row │
└─────────┬────────────┘
          ▼
┌──────────────────────┐
│ Parser               │
│ 表头 / 合并单元格     │
│ 图片锚点 / 单元格     │
└─────────┬────────────┘
          ▼
┌──────────────────────┐
│ Normalizer           │
│ Code / Size / Price  │
│ Material / LeadTime  │
└─────────┬────────────┘
          ▼
┌──────────────────────┐
│ Product Grouping     │
└─────────┬────────────┘
          ▼
┌────────────────────────────┐
│ Immutable Preview Snapshot │
│ revision + payload hash    │
└─────────┬──────────────────┘
          ▼
┌──────────────────────┐
│ Issue / Review       │
└─────────┬────────────┘
          ▼
┌──────────────────────┐
│ Confirmed Revision   │
└─────────┬────────────┘
          ▼
┌──────────────────────┐
│ Revalidate           │
└─────────┬────────────┘
          ▼
┌─────────────────────────────┐
│ Product Aggregate Persist   │
└─────┬─────────┬─────────────┘
      ▼         ▼
     RSPU     Supplier Ref
      │
      ▼
   Variant
      │
      ▼
     RSKU
      │
 ┌────┼──────────────┐
 ▼    ▼              ▼
Image Lineage   Price History
      │
      ▼
 async_task
      │
      ▼
AI / Embedding
      │
      ▼
MarketingProductQueryService
      │
      ├─ ProductRecallService
      ├─ VariantSelectionService
      ├─ SupplyQuoteService
      └─ DeterministicRankingService
               │
               ▼
          RecommendNode
               │
               ▼
       LLM 只生成解释
```

## 4.1 09 方案的 per-sheet 状态如何进入 Snapshot

现有约定是每个 Sheet 独立一个 `excel_import_batch`，`sheet_index` 标识物理 Sheet。Snapshot / Revision 因此以 `batch_id`（即当前 Sheet）为边界，不建立文件级共享 Revision。切换 Sheet 只切换到另一个 batch 的独立状态，不会改写当前 Sheet 已确认的 Revision。

每个 immutable snapshot 必须同时固化：

```json
{
  "sheet": { "sheetIndex": 0, "sheetName": "黑胡桃木高定系列" },
  "categoryPolicy": {
    "mode": "MIXED",
    "categoryHint": null,
    "candidateCategoryCodes": ["DINING_TABLE", "DINING_CHAIR"]
  },
  "productGroups": [
    {
      "productGroupKey": "...",
      "rows": [
        { "sourceRowIndex": 27, "finalCategoryCode": "DINING_CHAIR" }
      ]
    }
  ]
}
```

承接规则：

1. Step 2 设置 `mode/categoryHint/candidateCategoryCodes` 是一次 Revision 变更；
2. MIXED 候选集确定后才可执行候选集受限的 AI 归一/分类，结果生成新 Revision；
3. `rowCategorySelections` 是 Preview Edit 命令，而非只在 Confirm 时携带的临时参数；后端校验 `expectedRevision` 后将最终品类物化到新 Snapshot；
4. `suggestedCategoryCode` 仍只是交互提示；导入事实只读 confirmed snapshot 中的 `finalCategoryCode`；
5. 修改模式、候选集、默认品类或任一行最终品类，都必须 `preview_revision + 1`，已确认的旧 Snapshot 不可原地修改；
6. Confirm 校验直接复用 09 方案的「非跳过行品类完整 + 同一逻辑产品品类唯一」规则。

---

# 5. P0：供应商源商品身份重构

## 5.1 当前问题

当前：

```text
rspu_master.external_code
```

仍然具备全局唯一语义。

这意味着：

```text
工厂 A / S001
工厂 B / S001
```

无法正常表示为两个不同源商品。

现有：

```text
rspu_factory_mapping
```

表达的是：

> 一个 RSPU 与一个 Factory 的生产 / 供应关系

不能替代源商品编码映射。

---

# 6. supplier_product_ref

本期明确：

> 当前 Supplier Source 仅支持 Factory。

暂不抽象通用 `source_party`，避免过度设计。

同时明确区分两个概念：

```text
source_namespace
= 供应商编码所属命名空间，参与 Identity

source_system / source_channel
= 数据从哪里进入系统，只用于 provenance，不参与 Identity
```

同一个工厂的同一个商品可能先通过 Excel、后通过 ERP 进入系统；如果二者使用的是同一套工厂商品编码，则必须命中同一条 `supplier_product_ref`，不能仅因为传输渠道不同生成两个 Supplier Ref。

推荐表：

```sql
CREATE TABLE supplier_product_ref (
    ref_id BIGSERIAL PRIMARY KEY,

    rspu_id VARCHAR(64) NOT NULL,
    factory_code VARCHAR(16) NOT NULL,

    source_namespace VARCHAR(64) NOT NULL DEFAULT 'default',
    first_seen_source_system VARCHAR(64),
    last_seen_source_system VARCHAR(64),

    source_product_code VARCHAR(128) NOT NULL,
    normalized_source_product_code VARCHAR(128) NOT NULL,
    code_normalizer_version VARCHAR(64) NOT NULL,
    source_product_name VARCHAR(256),

    first_import_batch_id VARCHAR(64),
    last_import_batch_id VARCHAR(64),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_supplier_product_ref_rspu
        FOREIGN KEY (rspu_id)
        REFERENCES rspu_master(rspu_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_supplier_product_ref_factory
        FOREIGN KEY (factory_code)
        REFERENCES factory_master(factory_code)
        ON DELETE RESTRICT,

    CONSTRAINT fk_supplier_product_ref_first_batch
        FOREIGN KEY (first_import_batch_id)
        REFERENCES excel_import_batch(batch_id)
        ON DELETE SET NULL,

    CONSTRAINT fk_supplier_product_ref_last_batch
        FOREIGN KEY (last_import_batch_id)
        REFERENCES excel_import_batch(batch_id)
        ON DELETE SET NULL
);
```

唯一键：

```sql
CREATE UNIQUE INDEX uk_supplier_product_ref
ON supplier_product_ref (
    factory_code,
    source_namespace,
    normalized_source_product_code
);
```

查询索引：

```sql
CREATE INDEX idx_supplier_product_ref_rspu
ON supplier_product_ref(rspu_id);

CREATE INDEX idx_supplier_product_ref_factory
ON supplier_product_ref(factory_code);
```

### 6.1 source_namespace 规则

本期默认：

```text
source_namespace = default
```

只有真实业务确认同一工厂存在互相独立、允许编码重复的编码空间时，才允许增加：

```text
catalog
erp
legacy
...
```

禁止直接把：

```text
excel
manual
erp-api
```

这类“传输渠道”当作 namespace。

### 6.2 Supplier Code Reuse

同一：

```text
factory_code
+
source_namespace
+
normalized_source_product_code
```

已经绑定到某 RSPU 后，如果新导入内容明显指向另一个商品，不允许自动改绑。

必须生成：

```text
SOURCE_CODE_REUSED
severity = blocker
scope = product_blocking
```

进入人工治理。

原因：

> Supplier Ref 是历史可追溯身份映射，不能因供应商复用旧编码而静默篡改历史商品归属。

---

# 7. Source Product Code Normalization

必须同时保存：

```text
source_product_code
normalized_source_product_code
```

归一规则：

1. trim；
2. Unicode NFKC；
3. 全角转半角；
4. 英文字母 uppercase；
5. 折叠连续空格；
6. `-` 两侧空格移除；
7. 保留 `/ . _` 等可能有业务语义的字符；
8. 不修改原始字段；
9. Normalizer 必须版本化。

示例：

```text
" MJ-S96 "
"mj-s96"
"ＭＪ－Ｓ９６"
"MJ - S96"
```

归一为：

```text
MJ-S96
```

`code_normalizer_version` 是 Supplier Identity 的必备元数据，但不参与唯一键。当前唯一键始终是：

```text
factory_code
+
source_namespace
+
normalized_source_product_code
```

Normalizer 升级不允许在线逐行静默覆盖。必须按以下步骤执行：

1. 以新版本对全量 Supplier Ref 做 Dry Run，输出「旧值 → 新值」及唯一键冲突报告；
2. 任一新归一值冲突都进入人工治理，不允许自动合并或改绑 `rspu_id`；
3. 在受控迁移中原子更新 `normalized_source_product_code + code_normalizer_version`；
4. 切换前应用同时兼容读取旧版本，切换后新写入只使用当前活跃版本；
5. 迁移完成条件是 `zero conflict + zero stale normalizer version`。

---

# 8. external_code 最终定位

三类编码的职责固定如下，不再使用「display」这一个模糊词同时描述 `rspu_code` 和 `external_code`：

| 字段 / 模型 | 定位 | 展示与读写规则 |
|---|---|---|
| `rspu_id` | 平台商品技术主键 | 内部关联与 API 路径使用，不承载业务语义 |
| `rspu_code` | 平台生成的不可变业务编码 | 平台 UI、报表、导出、人工沟通的**主展示编码**；未软删记录保持唯一，语义继承 ADR 007 |
| `supplier_product_ref.source_product_code` | 工厂 / 供应商命名空间内的商品编码 | 导入查重、源数据回溯与供应商界面展示使用；唯一性为 `(factory_code, source_namespace, normalized_source_product_code)`，`source_system` 只记录 provenance |
| `rspu_master.external_code` | 历史兼容投影 | 只读显示为「历史外部编码」并支持受限兼容搜索；不是平台主展示编码 |

`external_code` 禁止继续作为 supplier identity、global product identity 或 import idempotency identity。Phase 4 之后的新导入**默认不写** `external_code`；不得从多个 supplier ref 中任选一个回投影到 RSPU。历史值保留到兼容消费方清零后再决定是否删列。

---

# 9. external_code 迁移方案

不能采用“长期双写 external_code + supplier ref”的方案，因为旧全局唯一约束会继续阻止跨工厂相同编码。

正确迁移：

## Phase 1：Expand

- 新建 `supplier_product_ref`；
- 保留现有 `external_code`；
- 不删除旧索引；
- 新代码先支持读取 supplier ref。

## Phase 2：Backfill

对历史数据回填：

```text
factory_code
source_namespace
source_product_code
normalized_source_product_code
code_normalizer_version
rspu_id
```

`first_seen_source_system / last_seen_source_system` 可作为 provenance 一同回填，但不参与身份唯一性。无法证明存在多套独立编码空间的历史数据统一回填 `source_namespace=default`。

以下数据不自动处理：

- 无法确定工厂；
- 同一 `external_code` 对应多个候选；
- 多厂历史关系不清；
- 旧数据存在冲突。

进入治理队列。

## Phase 3：Switch Read

所有身份解析优先：

```text
supplier_product_ref
```

legacy fallback 只允许：

```text
明确无歧义
+
factory context 能验证
```

禁止裸 fallback：

```text
find by external_code globally
```

平台列表/详情/导出的主展示同时切换为 `rspu_code`；`external_code` 只能放在明确标注的「历史外部编码」字段，不得在 DTO 中别名成 `productCode`。

## Phase 4：Stop New Identity Write

新导入：

```text
不再依赖 external_code 作为 identity
```

供应商源编码只写 `supplier_product_ref`；`external_code` 默认不再新写，`rspu_code` 按 ADR 007 继续作为平台主展示编码。仅已登记的 legacy 消费方可在有时限的兼容开关下继续读历史值，禁止新增写依赖。

## Phase 5：Contract

完成代码切换后：

```text
DROP uk_rspu_external_code
```

随后才真正允许：

```text
Factory A / S001
Factory B / S001
```

同时存在。

Contract 前必须用代码搜索、运行时指标与消费方清单确认：身份解析零次全局 `external_code` 查询、新写零次填充该列、主展示全部使用 `rspu_code`。

---

# 10. P0：Variant Identity 重构

## 10.1 当前问题

当前 Variant 判重只覆盖：

```text
size
color
material
```

无法正确区分：

```text
组合 A / 4000 / 真皮
组合 B / 4000 / 真皮
```

也无法表达：

```text
orientation
seatCount
extensionMode
module composition
```

---

# 11. Variant 新字段

```sql
ALTER TABLE rspu_variant
    ADD COLUMN module_code VARCHAR(64),
    ADD COLUMN module_type VARCHAR(64),

    ADD COLUMN width_mm INTEGER,
    ADD COLUMN depth_mm INTEGER,
    ADD COLUMN height_mm INTEGER,

    ADD COLUMN material_type_code VARCHAR(64),
    ADD COLUMN standard_material_grade_code VARCHAR(64),

    ADD COLUMN orientation_code VARCHAR(32),

    ADD COLUMN identity_attributes JSONB,
    ADD COLUMN descriptive_attributes JSONB;
```

建议 CHECK：

```sql
ALTER TABLE rspu_variant
ADD CONSTRAINT ck_variant_width_positive
CHECK (width_mm IS NULL OR width_mm > 0);

ALTER TABLE rspu_variant
ADD CONSTRAINT ck_variant_depth_positive
CHECK (depth_mm IS NULL OR depth_mm > 0);

ALTER TABLE rspu_variant
ADD CONSTRAINT ck_variant_height_positive
CHECK (height_mm IS NULL OR height_mm > 0);
```

---

# 12. 尺寸为什么必须有结构化列

保留：

```text
dimensions JSONB
```

同时新增：

```text
width_mm
depth_mm
height_mm
```

用途：

```sql
WHERE width_mm BETWEEN 3800 AND 4200
```

解决 Agent：

```text
4 米左右
```

这类高频范围过滤。

设计原则：

```text
SQL 列 = 高频检索
JSONB = 完整表达
```

---

# 13. Identity Attributes 与 Descriptive Attributes

禁止整个 `attributes JSONB` 全量参与 Variant Identity。

必须拆成：

## Identity Attributes

参与 Variant 唯一身份：

```text
orientation
seatCount
extensionMode
armType
moduleComposition
bedMode
reclineMode
```

## Descriptive Attributes

不参与身份：

```text
footStyle
marketingText
supplierRemark
displayNote
craftDescription
```

只有 Identity Attributes 进入 canonical fingerprint。

---

# 14. Variant Fingerprint 多版本设计

v2 中：

```text
UNIQUE(rspu_id, fingerprint, version)
```

会允许同一个业务 Variant 因算法升级产生 v1 / v2 两条 Variant。

v3 改为：

```text
Variant 是业务实体
Fingerprint 是身份解析索引
```

---

# 15. variant_fingerprint

`variant_fingerprint` 必须同时保证：

1. Fingerprint 指向的 Variant 确实属于同一个 RSPU；
2. 一个 Variant 在同一个 fingerprint version 下最多只有一个 Fingerprint；
3. 一个 RSPU + version + fingerprint 最多映射一个 Variant。

为了支持组合外键，先给 Variant 增加数据库级组合候选键：

```sql
ALTER TABLE rspu_variant
ADD CONSTRAINT uk_rspu_variant_rspu_variant
UNIQUE (rspu_id, variant_id);
```

Fingerprint 表：

```sql
CREATE TABLE variant_fingerprint (
    fingerprint_id BIGSERIAL PRIMARY KEY,

    rspu_id VARCHAR(64) NOT NULL,
    variant_id VARCHAR(64) NOT NULL,

    fingerprint_version INTEGER NOT NULL,
    canonical_fingerprint VARCHAR(128) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_variant_fingerprint_rspu_variant
        FOREIGN KEY (rspu_id, variant_id)
        REFERENCES rspu_variant(rspu_id, variant_id)
        ON DELETE CASCADE,

    CONSTRAINT ck_variant_fingerprint_version
        CHECK (fingerprint_version > 0)
);
```

Fingerprint → Variant 唯一约束：

```sql
CREATE UNIQUE INDEX uk_variant_fingerprint_value
ON variant_fingerprint (
    rspu_id,
    fingerprint_version,
    canonical_fingerprint
);
```

Variant → Fingerprint 唯一约束：

```sql
CREATE UNIQUE INDEX uk_variant_fingerprint_variant_version
ON variant_fingerprint (
    variant_id,
    fingerprint_version
);
```

查询索引：

```sql
CREATE INDEX idx_variant_fingerprint_variant
ON variant_fingerprint(variant_id);
```

这样数据库能够直接阻止：

```text
RSPU-A
+
属于 RSPU-B 的 Variant
```

被错误写入 Fingerprint 表。

---

# 16. Fingerprint 升级语义

例如：

```text
Variant V001
```

可拥有：

```text
v1 → ABC
v2 → XYZ
```

但仍然指向：

```text
V001
```

算法升级流程：

```text
计算 v2
↓
检查 v2 是否命中其他 Variant
↓
冲突治理
↓
绑定到既有 Variant
↓
完成迁移
```

不能：

```text
v2 查不到
→ 直接创建新 Variant
```

---

# 17. Fingerprint Canonical Serialization

`fingerprint_version >= 2` 的 Canonical Serialization 必须固定：

- 字段顺序；
- null；
- mm；
- uppercase；
- 标准 code；
- Identity JSON key 排序；
- 数字格式；
- boolean 表达。

示例：

```text
v2|
module=CHAISE|
width=1850|
depth=1050|
height=720|
color=BEIGE|
material=FULL_LEATHER|
grade=PREMIUM|
orientation=RIGHT|
seatCount=2
```

然后：

```text
SHA-256
```

## 17.1 现有「有码用码、无码用原文」兼容协议

Fingerprint 不得把「解析失败」、「只有原文」和「真实缺失」都序列化为 `null`。但存量迁移和新版身份必须使用不同版本的序列化协议：

- `fingerprint_version=1` 是 **Legacy Compatibility Fingerprint**，只镜像旧索引中 `rspu_id + COALESCE(size_code,size_text,'') + COALESCE(color_code,color_text,'') + COALESCE(material_code,material_text,'')` 的等价关系；
- `fingerprint_version>=2` 才使用下表的带类型 discriminator，并可纳入 module、orientation、结构化尺寸等新 Identity Attributes。

v1 的 `canonical_fingerprint` 只对三个 `COALESCE` 结果计算 Hash；`rspu_id` 由 `uk_variant_fingerprint_value` 的外层联合唯一键隔离，不重复进入 Hash Material。

v2 及以后每个 Identity 字段的类型值为：

| 输入状态 | Fingerprint 值 | 规则 |
|---|---|---|
| 有经验证的平台标准码 | `CODE:<code>` | 码按字典 canonical code 序列化 |
| 无标准码、有原文 | `TEXT:<normalized-text>` | 使用对应字段 Normalizer 版本的确定性结果，不丢弃原文 |
| 尺寸成功结构化 | `MM:<w>x<d>x<h>` | 统一 mm 和数字格式 |
| 尺寸解析失败但有原文 | `TEXT:<normalized-size-text>` | 不得降级成 `ABSENT` |
| 源数据真实缺失 | `ABSENT` | 只在 raw 和 normalized 都无值时使用 |

字段优先级固定为：

```text
经验证标准码 / 结构化值
→ 归一原文
→ ABSENT
```

但该优先级不表示字典治理后可以直接改写历史身份。迁移规则：

1. `fingerprint_version=1` 回填时，对 size / color / material 分别先执行与 PostgreSQL `COALESCE(code,text,'')` 相同的取值，再把三个结果按固定顺序做长度前缀 UTF-8 序列化。v1 **不加 `CODE/TEXT/ABSENT` 类型标记**，不新增其他 Identity 字段，也不 trim / uppercase；因此 code=`RED` 与 text=`RED` 仍按旧索引视为同一身份；
2. 新规则使用新 `fingerprint_version`。原文后续治理成标准码或尺寸后，先计算新版 Fingerprint 并尝试绑定原 `variant_id`；
3. 新 Fingerprint 未命中其他 Variant 时，将它追加绑定到原 Variant，然后再更新可查询的标准码/结构化属性；不创建新 Variant；
4. 新 Fingerprint 已指向其他 Variant 时，生成 `VARIANT_FINGERPRINT_CONVERGENCE` blocker，进入 Merge / 身份治理，不自动合并；
5. 旧版 Fingerprint 在其引用和回滚窗口结束前保留，不因标准化成功而删除。

固定样例：

```text
size_code=null, size_text="389*164*72H"
color_code=null, color_text="茶色"
material_code=null, material_text=null
→ v1 canonical bytes（UTF-8）
  legacy-v1|size=11:389*164*72H|color=6:茶色|material=0:
→ SHA-256
  c53a502fcb78f3ed780372b87c9bd18335ed04b20d355a27967e9c39469c1566

size_code=null, size_text="389*164*72H"
→ v1 size=LEGACY_BYTES:11:389*164*72H（长度前缀只用于无歧义拼接，不区分 code/text 来源；`LEGACY_BYTES:` 为注释性写法，仅表示「该段是长度前缀的 legacy 字节」，不是 Hash Material 的字面内容——实际协议字节不含该标记，带标记计算的 Hash 与上文固定样例值不符）
→ 后续解析成功的 v2 size=MM:3890x1640x720
→ v1/v2 都绑定原 variant_id

color_code=null, color_text="茶色"
→ color=TEXT:茶色

color_code=null, color_text=null
→ color=ABSENT

supplierGrade="AA级布" 且无可靠平台标准等级
→ 不进入 Variant Fingerprint
→ 进入§18.2 supplier_configuration_key
```

---

# 18. 材质模型

## 18.1 Variant

保存跨供应商可比较的：

```text
material_type_code
standard_material_grade_code
```

例如：

```text
FABRIC
FULL_LEATHER
HALF_LEATHER
```

平台标准等级仅在确有统一规则时使用。

## 18.2 RSKU

保存供应商原始等级：

```text
supplier_material_grade_code
supplier_material_grade_text
```

例如：

```text
FABRIC_AA
AA级布
```

原因：

```text
Factory A 的 A级布
≠
Factory B 的 A级布
```

供应商等级或配置在无法可靠映射为平台 `standard_material_grade_code` 时，**不得为了判重而伪造标准等级，也不得丢弃等级后覆盖同一 RSKU**。本期将其建模为 Supply-side Identity：

```text
supplier_configuration_key
supplier_configuration_normalizer_version
```

Key 生成优先级：

```text
工厂稳定配置/等级码
→ CODE:<normalized supplier code>

只有原始等级/报价列名
→ TEXT:<normalized supplier text>

确认不存在配置拆分
→ BASE
```

`BASE` 只允许用于该 Factory + Variant 确实没有多个供应配置的情形。如果同一源行展开出 A / AA / S 级布三个报价，必须分别生成三个非 `BASE` Key，即使它们的 Variant 材质都只能标准化为 `FABRIC`。

边界：

- 能被业务确认为跨供应商稳定、会改变平台产品配置的等级，写入 `standard_material_grade_code` 并参与 Variant Identity；
- 仅工厂内有意义、不可跨厂比较的等级，保留在 RSKU 的 Supplier Configuration；
- Supplier Configuration 后续被治理为平台标准等级时，按§17.1 的 Fingerprint 收敛流程处理，禁止在导入中静默改挂 Variant / RSKU。

## 18.3 Supplier Configuration Identity 演进

`CODE` 优先于 `TEXT` 只用于**首次创建尚不存在的供应配置**，不表示后续可以将已分配的 Identity Key 按新输入直接重算。

例如：

```text
首次：TEXT:AA级布
后续：CODE:FABRIC_AA
```

如果两者表示同一配置，必须命中同一 `rsku_id`，不得因 Key 表达升级创建第二条 RSKU。Supplier Configuration Resolver 顺序固定为：

1. 在同一 `variant_id + factory_code` 下查找已存在的精确 `CODE` Key；
2. 未命中时，利用输入中同时携带的 code/text、已治理的 code↔text 映射，以及 RSKU 保留的 `supplier_material_grade_code/text + supplier_configuration_normalizer_version` 匹配旧 `TEXT` Key；
3. 精确命中一条时绑定原 `rsku_id`；零条且同一 `variant_id + factory_code` 下不存在无法排除的 `TEXT / BASE` 候选时，才可创建新 `CODE` Key；输入只有 code 但存在未治理的旧候选、命中多条，或新 `CODE` Key 已属于另一 RSKU 时，以候选 `quoteKey` 生成 `SUPPLIER_CONFIGURATION_IDENTITY_CONFLICT` / `quote_blocking`；
4. 旧 Revision / Attempt Recovery 依然按 Snapshot 保存的原始 code/text 和 Normalizer Version 解析到同一 RSKU，不以当前 Key 文本是否相等作为唯一判断。

P0 采用受控迁移，不强制新增别名表。如果需要把存量 Key 从 `TEXT` 升级为 `CODE`，必须在单一事务内：

```text
SELECT 原 RSKU FOR UPDATE
→ 检查目标 (variant, factory, CODE key) 未被其他有效 RSKU 占用
→ 保留 rsku_id，更新 supplier_configuration_key / code / normalizer_version
→ 记录审计和 Identity Migration Result
```

Normalizer 升级也不允许在在线导入中逐条静默重写 Key。切换前必须 Dry Run 全部有效 Supplier Configuration，至少分类：

```text
stable      新旧结果不变
one-to-one  唯一安全更名
converged   多个旧 Key 收敛到同一新 Key
ambiguous   无法唯一绑定
```

只有 `stable / one-to-one` 可自动处理；`converged / ambiguous` 必须进入治理队列，在冲突清零前不得切换 Normalizer Version。

---

# 19. 模块与模块组合

短期：

```text
module_code
module_type
```

满足 Excel 与 Agent 查询。

长期 P2：

```sql
CREATE TABLE variant_component (
    component_id BIGSERIAL PRIMARY KEY,

    parent_variant_id VARCHAR(64) NOT NULL,
    component_variant_id VARCHAR(64) NOT NULL,

    quantity NUMERIC(10,2) NOT NULL DEFAULT 1,
    sort_order INTEGER,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_variant_component_parent
        FOREIGN KEY(parent_variant_id)
        REFERENCES rspu_variant(variant_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_variant_component_child
        FOREIGN KEY(component_variant_id)
        REFERENCES rspu_variant(variant_id)
        ON DELETE RESTRICT,

    CONSTRAINT ck_variant_component_quantity
        CHECK (quantity > 0)
);
```

---

# 20. P0：Normalized Preview Snapshot

当前最大问题之一：

```text
用户确认的 Preview
和
最终 Import 真正重新 Normalize 的结果
```

可能不一致。

因此正式导入不能从 Raw Excel 再完整重算。

---

## 20.1 excel_import_preview_revision

per-sheet 策略是整个 Revision 的元数据，不应重复塞入每个 product group，也不应只放在可变的 `excel_import_batch` 当前值中。因此增加 Snapshot Header：

```sql
CREATE TABLE excel_import_preview_revision (
    batch_id VARCHAR(64) NOT NULL,
    preview_revision BIGINT NOT NULL,

    source_sheet_index INTEGER NOT NULL,
    source_sheet_name VARCHAR(256),

    category_mode VARCHAR(16),
    category_hint VARCHAR(16),
    candidate_category_codes JSONB NOT NULL DEFAULT '[]'::jsonb,

    mapping_hash VARCHAR(64) NOT NULL,
    normalizer_version VARCHAR(64) NOT NULL,

    hash_version SMALLINT NOT NULL DEFAULT 1,
    snapshot_hash VARCHAR(64) NOT NULL,

    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_import_preview_revision
        PRIMARY KEY(batch_id, preview_revision),

    CONSTRAINT fk_import_preview_revision_batch
        FOREIGN KEY(batch_id)
        REFERENCES excel_import_batch(batch_id)
        ON DELETE CASCADE,

    CONSTRAINT ck_import_preview_category_mode
        CHECK (category_mode IS NULL OR category_mode IN ('SINGLE', 'MIXED')),

    CONSTRAINT ck_import_preview_category_candidates
        CHECK (jsonb_typeof(candidate_category_codes) = 'array'),

    CONSTRAINT ck_import_preview_hash_version
        CHECK (hash_version > 0),

    CONSTRAINT ck_import_preview_category_policy
        CHECK (
            category_mode IS NULL
            OR (
                category_mode = 'SINGLE'
                AND category_hint IS NOT NULL
                AND jsonb_array_length(candidate_category_codes) = 0
            )
            OR (
                category_mode = 'MIXED'
                AND category_hint IS NULL
                AND jsonb_array_length(candidate_category_codes) >= 2
            )
        )
);
```

`excel_import_batch.preview_revision` 只是当前 Revision 指针，`confirmed_revision` 只是已确认 Revision 指针；真正不可变的 per-sheet 策略、mapping / normalizer 版本与快照哈希保存在本表。行级 `finalCategoryCode` 保存在下方 product group 的 `normalized_payload`。

`snapshot_hash` 不直接对数据库整行计算，而是对 §47.1 定义的 `SnapshotHashMaterial` 计算 SHA-256。Hash Material 明确排除 `snapshot_hash` 自身、`created_at`、`created_by` 等非业务字段，并包含按 `product_group_key` 排序的全部 `payload_hash`，因此品类策略或任一行最终品类变化都会改变整体快照哈希。品类码的字典有效性另由业务校验保证。

应用只允许 INSERT 新 Revision，禁止 UPDATE / DELETE 单个 Revision；不可变性由 Repository API 和数据库权限共同保证。到期 GC 只能按本文§44策略清理整个未 hold batch 的过期快照，不提供业务修改接口。

数据库防护的实施方式固定为：

1. Migration Owner 与 Runtime App Role 必须分离；Runtime App Role 不是表 Owner，对 `excel_import_preview_revision` 和 `excel_import_product_group` 只授予 `SELECT / INSERT`，显式 `REVOKE UPDATE, DELETE`；
2. 不使用应用可自行设置的 Session Flag / Custom GUC 绕过不可变性；
3. 过期删除只能调用由独立 `NOLOGIN` Owner 持有的 `SECURITY DEFINER` GC 函数；函数必须 Schema-qualify 所有对象并固定安全 `search_path`，同时 `REVOKE EXECUTE FROM PUBLIC`；应用账号只拥有该函数的 `EXECUTE`，不拥有底表 `DELETE`；
4. GC 函数内部必须重新校验 retention、hold、Attempt 状态并锁定 Batch，不接受「强制跳过校验」参数；
5. 开发环境即使复用高权限账号，Repository 也不暴露修改/单 Revision 删除方法，并通过集成测试验证生产权限模型。

---

# 21. excel_import_product_group

`excel_import_product_group` 只表示某个 Preview Revision 下的**不可变产品聚合输入**。

它回答的是：

> Revision N 中，这个逻辑产品被确认成什么标准化数据？

它不回答：

> 某一次 Import Attempt 执行到了哪里、成功多少 Variant、失败多少 Quote？

所有执行期状态、计数、Checkpoint、结果摘要统一放入 §49.2 `excel_import_attempt_item`。

其中：

```text
normalized_payload
```

只允许保存非敏感 / 普通级别的标准化数据。

采购成本等敏感值不能直接进入 JSONB，必须使用 §24.2 的 `excel_import_sensitive_value`，并在 Payload 中保存 Sensitive Ref。

推荐表：

```sql
CREATE TABLE excel_import_product_group (
    group_id VARCHAR(64) PRIMARY KEY,

    batch_id VARCHAR(64) NOT NULL,
    preview_revision BIGINT NOT NULL,

    product_group_key VARCHAR(256) NOT NULL,

    normalized_payload JSONB NOT NULL,

    payload_hash_version SMALLINT NOT NULL DEFAULT 1,
    payload_hash VARCHAR(64) NOT NULL,

    normalizer_version VARCHAR(64) NOT NULL,
    mapping_hash VARCHAR(64) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_import_product_group_revision
        FOREIGN KEY(batch_id, preview_revision)
        REFERENCES excel_import_preview_revision(batch_id, preview_revision)
        ON DELETE CASCADE,

    CONSTRAINT uk_import_product_group_revision_key
        UNIQUE(batch_id, preview_revision, product_group_key),

    CONSTRAINT uk_import_product_group_revision_id
        UNIQUE(batch_id, preview_revision, group_id),

    CONSTRAINT ck_import_product_group_payload_hash_version
        CHECK(payload_hash_version > 0)
);
```

建议索引：

```sql
CREATE INDEX idx_import_product_group_batch_revision
ON excel_import_product_group(batch_id, preview_revision);
```

## 21.1 Immutable 输入与 Runtime 结果分离

以下字段**禁止**再出现在 `excel_import_product_group`：

```text
status
checkpoint
variant_success_count
variant_failed_count
quote_success_count
quote_failed_count
result_summary
started_at
completed_at
```

这些字段全部属于：

```text
Import Attempt Runtime
```

由：

```text
excel_import_attempt_item
```

按：

```text
attempt_id + group_id
```

独立记录。

因此同一个：

```text
batch + revision
```

即使因不同 `policy_hash` 产生多个 Attempt，也不会共同改写同一份 Immutable Snapshot。

最终边界：

```text
excel_import_product_group
= immutable input

excel_import_attempt
= one execution

excel_import_attempt_item
= one group result inside one execution
```

---

# 22. Immutable Snapshot 原则

用户确认：

```text
Revision 7
```

之后：

```text
normalized_payload
payload_hash
normalizer_version
mapping_hash
```

必须冻结。

正式 Import：

```text
LOAD confirmed snapshot
↓
REVALIDATE
↓
PERSIST
```

禁止：

```text
重新完整 normalize raw Excel
```

---

# 23. Revalidate 与 Renormalize 区分

正式导入允许重新验证：

```text
Supplier Ref 是否冲突
RSPU 是否存在
Variant 是否冲突
Factory 是否有效
权限
报价日期
状态
数据库约束
```

但不重新解释：

```text
"A级布" 到底映射哪个 code
"389*164*72H" 怎么解析
```

这类 Normalize 结果已经被 Preview Revision 冻结。

---

# 24. P0：Import Issue

Preview 阶段还没有正式的：

```text
excel_import_row.row_id
```

因此 Preview Issue 的源定位必须以 Excel 物理坐标为主：

```text
batch_id
source_sheet_index
source_row_index
source_column_index
```

`source_row_id` 只作为正式导入后可选回填的数据库行引用，不能作为 Preview Identity。

同时，任何 Issue——包括 `group_id IS NULL` 的 batch-level Issue——都必须引用一个真实存在的 Preview Revision。

```sql
CREATE TABLE import_data_issue (
    issue_id BIGSERIAL PRIMARY KEY,

    batch_id VARCHAR(64) NOT NULL,
    group_id VARCHAR(64),

    preview_revision BIGINT NOT NULL,

    source_sheet_index INTEGER,
    source_row_index INTEGER,
    source_column_index INTEGER,
    source_header VARCHAR(256),

    source_row_id BIGINT,

    target_type VARCHAR(16) NOT NULL DEFAULT 'group',
    target_key VARCHAR(512),

    issue_code VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    issue_scope VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'open',

    message TEXT,

    resolution_action VARCHAR(32),
    resolution_value JSONB,
    resolution_reason TEXT,

    resolved_by VARCHAR(64),
    resolved_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_import_issue_revision
        FOREIGN KEY(batch_id, preview_revision)
        REFERENCES excel_import_preview_revision(batch_id, preview_revision)
        ON DELETE CASCADE,

    CONSTRAINT fk_import_issue_group_revision
        FOREIGN KEY(batch_id, preview_revision, group_id)
        REFERENCES excel_import_product_group(
            batch_id,
            preview_revision,
            group_id
        )
        ON DELETE CASCADE,

    CONSTRAINT uk_import_issue_revision_id
        UNIQUE(batch_id, preview_revision, issue_id),

    CONSTRAINT ck_import_issue_severity
        CHECK (severity IN ('info', 'warning', 'error', 'blocker')),

    CONSTRAINT ck_import_issue_scope
        CHECK (
            issue_scope IN (
                'product_blocking',
                'variant_blocking',
                'quote_blocking',
                'non_blocking'
            )
        ),

    CONSTRAINT ck_import_issue_target_type
        CHECK (target_type IN ('batch', 'group', 'variant', 'quote', 'field')),

    CONSTRAINT ck_import_issue_target_key
        CHECK (
            (target_type = 'batch' AND group_id IS NULL AND target_key IS NULL)
            OR (target_type = 'group' AND group_id IS NOT NULL AND target_key IS NULL)
            OR (target_type = 'variant'
                AND group_id IS NOT NULL
                AND target_key IS NOT NULL
                AND target_key LIKE 'VAR:%')
            OR (target_type = 'quote'
                AND group_id IS NOT NULL
                AND target_key IS NOT NULL
                AND target_key LIKE 'QUOTE:%')
            OR (target_type = 'field'
                AND group_id IS NOT NULL
                AND target_key IS NOT NULL
                AND (
                    target_key LIKE 'GROUP:%#/%'
                    OR target_key LIKE 'VAR:%#/%'
                    OR target_key LIKE 'QUOTE:%#/%'
                ))
        ),

    CONSTRAINT ck_import_issue_scope_target
        CHECK (
            (issue_scope = 'product_blocking'
                AND (
                    target_type IN ('batch', 'group')
                    OR (target_type = 'field' AND target_key LIKE 'GROUP:%#/%')
                ))
            OR (issue_scope = 'variant_blocking'
                AND (
                    target_type = 'variant'
                    OR (target_type = 'field' AND target_key LIKE 'VAR:%#/%')
                ))
            OR (issue_scope = 'quote_blocking'
                AND (
                    target_type = 'quote'
                    OR (target_type = 'field' AND target_key LIKE 'QUOTE:%#/%')
                ))
            OR issue_scope = 'non_blocking'
        ),

    CONSTRAINT ck_import_issue_status
        CHECK (status IN ('open', 'resolved', 'ignored', 'waived', 'stale')),

    CONSTRAINT ck_import_issue_resolution_action
        CHECK (
            resolution_action IS NULL
            OR resolution_action IN ('replace', 'ignore', 'waive', 'acknowledge')
        )
);
```

说明：

- `(batch_id, preview_revision)` FK 保证 batch-level Issue 也不能引用不存在的 Revision；
- `(batch_id, preview_revision, group_id)` FK 保证 group-level Issue 与 ProductGroup 属于同一 Revision；
- `group_id IS NULL` 时只跳过 Group FK，不会跳过 Revision FK。
- **双 CHECK 成对约束**：`ck_import_issue_target_key` 与 `ck_import_issue_scope_target` 必须成对存在、成对演进。Issue Target 对 NULL 的「显式拒绝」是两者联合达成的：`ck_import_issue_scope_target` 单独存在时，`(issue_scope='product_blocking', target_type='field', target_key=NULL)` 这类组合会经 PostgreSQL CHECK 的 UNKNOWN 语义放行（实测确认），实际拦截依赖 `ck_import_issue_target_key` 对 field 目标强制 `target_key IS NOT NULL` 的联合作用。任何一侧单独下线或拆分维护即开洞。

索引：

```sql
CREATE INDEX idx_import_issue_batch_revision
ON import_data_issue(batch_id, preview_revision);

CREATE INDEX idx_import_issue_group
ON import_data_issue(group_id);

CREATE INDEX idx_import_issue_status
ON import_data_issue(status, severity);

CREATE INDEX idx_import_issue_source_position
ON import_data_issue(
    batch_id,
    preview_revision,
    source_sheet_index,
    source_row_index,
    source_column_index
);

CREATE INDEX idx_import_issue_target
ON import_data_issue(
    batch_id,
    preview_revision,
    group_id,
    target_type,
    target_key
);
```

### 24.1 Issue Target Protocol

源单元格坐标只回答「问题从哪里来」，`target_type + target_key` 回答「哪个标准化对象被阻断」。一行展开多 Variant 或一列展开多 Quote 时，必须使用目标键，不得只靠 row / column 推断。

Snapshot 中每个标准化对象必须包含：

```text
variantKey = "VAR:" + SHA-256(canonical({
  "fingerprintVersion": fingerprintVersion,
  "identity": canonicalVariantIdentity
}))

quoteKey = "QUOTE:" + SHA-256(canonical({
  "variantKey": variantKey,
  "factoryCode": factoryCode,
  "supplierConfigurationKey": supplierConfigurationKey,
  "currencyCode": currencyCode,
  "priceUnit": priceUnit,
  "effectiveFrom": effectiveFrom
}))

targetRskuKey = "RSKU_TARGET:" + SHA-256(canonical({
  "variantKey": variantKey,
  "factoryCode": factoryCode,
  "supplierConfigurationKey": supplierConfigurationKey
}))
```

这里的 `canonical` 复用§ 47.1 的 UTF-8 Canonical JSON 规则，包括固定 key 排序、显式 `null` 和数字/日期格式；禁止用无分隔符的字符串直接拼接。`quoteKey` 材料中的 `effectiveFrom` 按显式 null 保留处理：`null` 与有值产生不同 `quoteKey`（与 §47.1.5 quote 日期 NULL 分支表语义兼容），实现不得把 null 省略成缺省字段。

规则：

- `variantKey` 在一个 ProductGroup 内唯一，是 Preview 阶段的逻辑目标，不是数据库 `variant_id`；
- `quoteKey` 在一个 ProductGroup 内唯一，必须引用同一 Snapshot 内存在的 `variantKey`；
- 可持久化 Quote 的 `supplierConfigurationKey` 必须先经§18.3 Resolver 解析；`targetRskuKey` 表示 Quote 最终竞争的 RSKU 身份，不是新的数据库主键；
- Supplier Configuration 解析冲突时，可以用候选 Key 生成确定性 `quoteKey` 作为 `quote_blocking` Issue 目标，但不生成 `targetRskuKey`、不进入 Persist 集合；
- 同一逻辑 Quote 重复出现时复用同一 `quoteKey`，不通过加源坐标制造两个业务目标；是否合并以§38.1 的完整业务比较材料为准，不能仅因 Key 相同就覆盖或拒绝。完全等价时合并为一个 Quote；可选的 `DUPLICATE_QUOTE_TARGET` 提示固定为 `info / non_blocking`，不要求人工确认、不阻断 Confirm / Import；业务值不同时按§38.1 生成阻断 Issue；
- 源 row / column、header 及 Lineage 仍独立保留，允许多个源单元格指向同一目标键；
- `field` 目标键使用 `<ownerTargetKey>#<json-pointer>`，owner 只能是 `GROUP:<groupId>`、`VAR:<hash>` 或 `QUOTE:<hash>`，例如 `VAR:...#/dimensions/widthMm`；
- `field` 的阻断粒度由 owner 决定：`GROUP` 只能是 `product_blocking`，`VAR` 只能是 `variant_blocking`，`QUOTE` 只能是 `quote_blocking`；`non_blocking` 可引用任何合法目标。服务层还必须验证 owner 在同一 Snapshot / Group 中存在，不能只依赖 CHECK 的前缀校验。

对可持久化 Quote，`normalized_payload` 必须持久化 `variantKey / quoteKey / targetRskuKey / quote.variantKey / supplierConfigurationKey`，Payload Hash 也覆盖这些字段。解析冲突的 Quote 只保存候选 Key、`quoteKey` 和 Issue，不伪造 `targetRskuKey`。

## 24.2 敏感 Preview / Issue 值

采购成本等敏感值不能直接写入：

```text
excel_import_product_group.normalized_payload
import_data_issue.resolution_value
```

新增：

```sql
CREATE TABLE excel_import_sensitive_value (
    sensitive_value_id VARCHAR(64) PRIMARY KEY,

    batch_id VARCHAR(64) NOT NULL,
    preview_revision BIGINT NOT NULL,

    group_id VARCHAR(64),
    issue_id BIGINT,

    value_path VARCHAR(512) NOT NULL,

    ciphertext TEXT NOT NULL,
    encryption_key_version INTEGER NOT NULL,

    value_hmac VARCHAR(64) NOT NULL,
    hmac_key_version INTEGER NOT NULL,

    data_classification VARCHAR(32) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    retention_until TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_import_sensitive_revision
        FOREIGN KEY(batch_id, preview_revision)
        REFERENCES excel_import_preview_revision(batch_id, preview_revision)
        ON DELETE CASCADE,

    CONSTRAINT fk_import_sensitive_group_revision
        FOREIGN KEY(batch_id, preview_revision, group_id)
        REFERENCES excel_import_product_group(
            batch_id,
            preview_revision,
            group_id
        )
        ON DELETE CASCADE,

    CONSTRAINT fk_import_sensitive_issue_revision
        FOREIGN KEY(batch_id, preview_revision, issue_id)
        REFERENCES import_data_issue(
            batch_id,
            preview_revision,
            issue_id
        )
        ON DELETE CASCADE,

    CONSTRAINT ck_import_sensitive_owner
        CHECK (
            (CASE WHEN group_id IS NULL THEN 0 ELSE 1 END)
            +
            (CASE WHEN issue_id IS NULL THEN 0 ELSE 1 END)
            = 1
        ),

    CONSTRAINT ck_import_sensitive_key_version
        CHECK (
            encryption_key_version > 0
            AND hmac_key_version > 0
        )
);
```

Partial Unique：

```sql
CREATE UNIQUE INDEX uk_import_sensitive_group_path
ON excel_import_sensitive_value(
    batch_id,
    preview_revision,
    group_id,
    value_path
)
WHERE group_id IS NOT NULL;

CREATE UNIQUE INDEX uk_import_sensitive_issue_path
ON excel_import_sensitive_value(
    batch_id,
    preview_revision,
    issue_id,
    value_path
)
WHERE issue_id IS NOT NULL;
```

Snapshot 中只允许出现：

```json
{
  "factoryPrice": {
    "__sensitiveRef": "SENS-..."
  }
}
```

Issue 的敏感修正值同理：

```json
{
  "__sensitiveRef": "SENS-..."
}
```

禁止把：

```json
{"factoryPrice": 9500}
```

再次明文写入普通 JSONB。

加密和等值摘要职责分离：

```text
ciphertext
= 可恢复原值
= 使用项目统一加密体系

value_hmac
= 幂等 / 变更检测 / 安全等值比较
= HMAC-SHA-256(secret, canonicalSensitiveValue)
```

禁止对成本金额使用普通公开 SHA-256 作为 `value_hash`，因为价格属于低熵数据，可被离线枚举。

`Encryption Key` 与 `HMAC Key` 必须逻辑分离，并保存各自版本以支持轮换。

---

# 25. Issue Severity

统一小写：

```text
info
warning
error
blocker
```

---

# 26. Issue Scope

```text
product_blocking
variant_blocking
quote_blocking
non_blocking
```

示例：

```text
DUPLICATE_SOURCE_CODE
→ product_blocking

INVALID_VARIANT_IDENTITY
→ variant_blocking

INVALID_FACTORY_PRICE
→ quote_blocking

SECONDARY_IMAGE_FAILED
→ non_blocking
```

---

# 27. Issue 生命周期

```text
open
resolved
ignored
waived
stale
```

规则：

```text
源数据 / 映射发生修改
↓
旧 issue → stale
↓
重新校验
↓
生成新 issue
```

`blocker`：

```text
普通用户不可 ignore
```

管理员：

```text
waived
```

必须记录：

```text
reason
operator
timestamp
```

Resolution 与 Revision 规则：

| Action | 语义 | 是否新 Revision | 对 Confirm 的影响 |
|---|---|---:|---|
| `replace` | 修改目标字段/对象的 normalized fact | 是 | 清空活跃 `confirmed_revision`，旧 Issue 置 `stale` |
| `ignore` | 显式跳过该 Variant / Quote，不是绕过约束后强行写入 | 是 | 清空活跃 Confirm，新 Snapshot 物化 skipped target |
| `waive` | 经授权接受可豁免规则的风险，仍按正常持久化 | 是 | 清空活跃 Confirm，新 Snapshot 物化 waiver code / reason |
| `acknowledge` | 仅确认 `info/non_blocking`，不改变导入行为 | 否 | 不影响 Confirm |

所有会改变 Import 输入或放行结果的决定都必须进入新 Snapshot Hash Material；不允许在已 Confirmed Revision 上原地把 blocker 改成 `resolved/ignored/waived`。

绝不可 `waive` 的类别：

- 身份唯一性、RSPU/Variant 组合 FK 和其他数据库完整性冲突；
- Supplier Code Reuse / 既有 Supplier Ref 改绑；
- Snapshot / Payload / Policy Hash 校验失败；
- 鉴权、数据范围、成本权限或解密失败；
- 缺失必备 Product / Variant Identity；
- 价格非正数、币种/单位不可解释。

最后一类 Quote 问题可以选择 `ignore` 以跳过整个 `quoteKey`，但不得 `waive` 后写入非法价格。可豁免 Issue Code 必须维护服务端 allowlist，默认为空；每次 waiver 记录 operator、reason 和审计日志。

---

# 28. 产品事务语义

v3 不再简单规定：

> 任一行失败 → 整个产品失败

而改成：

```text
Issue Scope 决定阻断级别
```

---

# 29. ProductAggregate 写入规则

## product_blocking

整个 ProductAggregate 不允许持久化。

## variant_blocking

对应 Variant 及其依赖 Quote 不写。

其他合法 Variant 可以写。

## quote_blocking

只拒绝该 Quote。

Variant / RSPU 可以正常写。

## non_blocking

记录 Issue，但不阻塞核心实体。

---

# 30. NormalizedProductAggregate

```java
public record NormalizedProductAggregate(
    ProductIdentity identity,
    NormalizedRspu rspu,
    List<NormalizedVariant> variants,
    List<NormalizedSupplyQuote> quotes,
    List<NormalizedImage> images,
    List<ImportIssueRef> issues,
    String payloadHash,
    long previewRevision
) {}

public record NormalizedVariant(
    String variantKey,
    int fingerprintVersion,
    VariantIdentity identity
) {}

public record NormalizedSupplyQuote(
    String quoteKey,
    String targetRskuKey,
    String variantKey,
    String factoryCode,
    String supplierConfigurationKey,
    SensitiveValueRef factoryPrice
) {}
```

Persist 层不再理解 Excel 原始行，只通过 `variantKey / quoteKey` 应用 Issue Scope 过滤，然后将通过的逻辑键解析为数据库 ID。

---

# 31. 产品聚合数据库事务

每个经过筛选后的 ProductAggregate：

```text
BEGIN

SELECT attempt_item FOR UPDATE

apply Issue target filter by variantKey / quoteKey

resolve/create RSPU
resolve/create supplier_product_ref

persist valid Variants
persist valid RSKUs

persist image metadata
persist price history
persist lineage
persist async_task

UPDATE attempt_item
SET status = imported / partial / rejected / skipped,
    checkpoint = 'committed',
    counts = ...,
    result_summary = ...

COMMIT
```

数据库事务失败：

```text
整个本次 ProductAggregate DB 写入回滚
```

只有事务回滚后的技术失败才使用独立事务将 AttemptItem 记为 `failed`；确定性 product blocker 在预校验阶段就将 AttemptItem 记为终态 `rejected`。ProductAggregate 领域写入和 AttemptItem 终态绝不允许分属两个事务。

---

# 32. P0：RSKU 领域模型

## 32.1 本期正式确认 RSKU Identity

沿用当前项目业务方向，并在本轮正式收口为：

> 同一 Factory + 同一 Variant + 同一 Supplier Configuration 只有一个当前有效 RSKU。

目标业务 Identity：

```text
variant_id + factory_code + supplier_configuration_key
```

`supplier_configuration_key` 遵循§18.2，表示工厂内稳定的材质等级/报价包/供应配置身份；无配置拆分时为 `BASE`。`rspu_id` 继续保留在 RSKU 中用于查询和领域可读性，但它不再参与 RSKU Identity。

ADR 007 中 `rsku_code` 的材质/版本展示段由 Supplier Configuration 派生，但 `rsku_code` 仍只是业务展示编码，数据库判重以上述三元组为准。

未来如果真实业务证明：

```text
一个工厂同一 Variant + Supplier Configuration 仍确实有多个独立 factory_sku
```

再通过 ADR 升级 Identity，不在本轮预留模糊语义。

## 32.2 RSPU / Variant 一致性

由于 RSKU 同时保存：

```text
rspu_id
variant_id
```

数据库必须保证：

```text
rsku_supply.rspu_id
=
rspu_variant.rspu_id
```

前置条件：§15 已为：

```text
rspu_variant(rspu_id, variant_id)
```

建立唯一候选键。

`variant_id NOT NULL` 回填完成后，新增组合 FK：

```sql
ALTER TABLE rsku_supply
ADD CONSTRAINT fk_rsku_rspu_variant
FOREIGN KEY (rspu_id, variant_id)
REFERENCES rspu_variant(rspu_id, variant_id)
ON DELETE RESTRICT;
```

随后移除仅依赖 `variant_id` 的旧孤立语义，所有新增写路径必须同时通过组合 FK。

## 32.3 RSKU 唯一约束切换

存量冲突治理和 `variant_id NOT NULL` 完成后：

```sql
DROP INDEX IF EXISTS idx_rsku_unique;

CREATE UNIQUE INDEX uk_rsku_variant_factory_config_alive
ON rsku_supply(variant_id, factory_code, supplier_configuration_key)
WHERE deleted_at IS NULL;
```

这样数据库约束与领域语义一致：

```text
Variant + Factory + Supplier Configuration
= 当前唯一供应单元
```

而不是继续保留：

```text
RSPU + Variant + Factory
```

作为另一套隐式 Identity。

---

# 33. variant_id 最终必须 NOT NULL

目标模型：

```text
RSPU
↓
Variant
↓
RSKU
```

因此最终：

```text
rsku_supply.variant_id
```

必须：

```text
NOT NULL
```

迁移：

```text
查找 NULL
↓
创建 Legacy Default Variant
↓
回填
↓
验证无 NULL
↓
SET NOT NULL
```

---

# 34. RSKU 字段增强

```sql
ALTER TABLE rsku_supply
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'active',

    ADD COLUMN currency_code VARCHAR(8) NOT NULL DEFAULT 'CNY',
    ADD COLUMN tax_included BOOLEAN,
    ADD COLUMN price_unit VARCHAR(32),

    ADD COLUMN moq_unit VARCHAR(32),

    ADD COLUMN quote_date DATE,
    ADD COLUMN effective_from DATE,
    ADD COLUMN effective_to DATE,

    ADD COLUMN supplier_material_grade_code VARCHAR(64),
    ADD COLUMN supplier_material_grade_text VARCHAR(128),
    ADD COLUMN supplier_configuration_key VARCHAR(192),
    ADD COLUMN supplier_configuration_normalizer_version VARCHAR(64),

    ADD COLUMN source_batch_id VARCHAR(64),
    ADD COLUMN source_row_id BIGINT,

    ADD COLUMN quote_version INTEGER NOT NULL DEFAULT 1;
```

Expand 阶段两个 Supplier Configuration 字段先允许 NULL，新代码必须显式写入，禁止依赖数据库 `DEFAULT 'BASE'`。按§76 完成回填和歧义治理后再 `SET NOT NULL`；这样存量多等级数据不会在发布时被静默压成 `BASE`。

约束：

```sql
ALTER TABLE rsku_supply
ADD CONSTRAINT ck_rsku_moq_positive
CHECK (moq IS NULL OR moq > 0);

ALTER TABLE rsku_supply
ADD CONSTRAINT ck_rsku_effective_date
CHECK (
    effective_from IS NULL
    OR effective_to IS NULL
    OR effective_to >= effective_from
);

ALTER TABLE rsku_supply
ADD CONSTRAINT ck_rsku_quote_version_positive
CHECK (quote_version > 0);

-- 状态机五值枚举以§36 为准；采用「显式拒绝 NULL」形态，不依赖 PostgreSQL CHECK 的 UNKNOWN 语义
ALTER TABLE rsku_supply
ADD CONSTRAINT ck_rsku_status
CHECK (
    status IS NOT NULL
    AND status IN ('active', 'inactive', 'expired', 'suspended', 'discontinued')
);
```

## 34.1 字段级人工修正标记

`manualCorrectionPolicy` 不能通过查询会到期的 Lineage 或重放 Audit Log 判断。RSPU / Variant / RSKU 当前实体各自保存权威的字段级标记：

```sql
ALTER TABLE rspu_master
    ADD COLUMN manual_override_fields JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE rspu_variant
    ADD COLUMN manual_override_fields JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE rsku_supply
    ADD COLUMN manual_override_fields JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE rspu_master
    ADD CONSTRAINT ck_rspu_manual_override_fields_object
    CHECK (jsonb_typeof(manual_override_fields) = 'object');

ALTER TABLE rspu_variant
    ADD CONSTRAINT ck_variant_manual_override_fields_object
    CHECK (jsonb_typeof(manual_override_fields) = 'object');

ALTER TABLE rsku_supply
    ADD CONSTRAINT ck_rsku_manual_override_fields_object
    CHECK (jsonb_typeof(manual_override_fields) = 'object');
```

Key 使用稳定 JSON Pointer，Value 只保存治理元数据，不复制敏感业务值：

```json
{
  "/factoryPrice": {
    "active": true,
    "correctedBy": "USER-001",
    "correctedAt": "2026-09-22T10:00:00Z",
    "source": "product_ui"
  }
}
```

生命周期固定为：

- 人工编辑业务值时，必须在同一数据库事务设置对应字段标记并写 Audit Log；
- ProductAggregate Persist 在写入前必须锁定或乐观校验目标实体，并在同一事务重读 `manual_override_fields`；不得使用 Preview 时缓存的过期标记覆盖并发人工修正；
- Excel / AI / 后台重试不能自行清除标记；`price_history.change_type=manual_override` 只是历史证据，不是当前权威判断源；
- `replace_explicit` 成功时更新标记的 operator / timestamp / source；恢复自动管理必须是独立的授权人工操作，将 `active=false` 并写审计；
- 标记随当前实体保留，不受 Lineage / Raw File GC 影响；软删除不清理，恢复后继续生效；
- Merge / Identity Migration 必须合并标记；同一字段的活跃标记冲突时进入人工治理，不得静默丢弃。

---

# 35. factory_price 校验

当前：

```text
factory_price = AES encrypted TEXT
```

所以数据库不能：

```sql
CHECK(factory_price > 0)
```

正确做法：

```java
factoryPrice != null
&& factoryPrice.compareTo(BigDecimal.ZERO) > 0
```

在加密前进行应用层校验。

---

# 36. RSKU 状态

统一小写：

```text
active
inactive
expired
suspended
discontinued
```

---

# 37. quote_version 并发语义

报价更新：

```text
读取 current quote
↓
optimistic compare version
↓
version + 1
↓
update
```

推荐：

```sql
UPDATE rsku_supply
SET quote_version = quote_version + 1
WHERE rsku_id = ?
  AND quote_version = ?
```

失败：

```text
409 quote_version_conflict
```

---

# 38. RSKU 并发 Upsert

并发导入相同：

```text
variant + factory + supplier configuration
```

必须依赖数据库唯一约束。

推荐：

```text
INSERT ... ON CONFLICT ...
```

或：

```text
SELECT ... FOR UPDATE
```

不能只靠：

```text
先 SELECT
再 INSERT
```

## 38.1 Quote 到目标 RSKU 的聚合校验

`quoteKey` 区分报价事实，`targetRskuKey` 区分当前报价的落库目标。P0 每条 RSKU 只保存一份 current quote，因此 Persist 前必须先对同一 ProductGroup 内的 Quote 按 `targetRskuKey` 聚合，不得边遍历边覆盖。

标准化 Quote 的业务比较材料至少包含：

```text
factory_price value_hmac
currency_code
tax_included
price_unit
quote_date
effective_from / effective_to
moq / moq_unit
```

聚合规则：

- 比较材料完全相同：合并为一个 Quote，只写入一次，所有源坐标保留为多份 Lineage；即使多个来源具有相同 `quoteKey`，也不得将重复本身作为阻断原因；
- 比较材料不同：对该 `targetRskuKey` 下的每个不同 `quoteKey` 生成 `MULTIPLE_CURRENT_QUOTES_FOR_RSKU` / `blocker / quote_blocking`；即使 `quoteKey` 相同但价格等未参与 Key 的业务值不同，也必须阻断，并保留所有冲突来源及其值（敏感值使用 Sensitive Ref），不得先按 Key 去重丢弃差异；
- P0 不自动选最新日期、不做币种换算、不在单位之间猜测转换；用户必须通过 Preview `replace / ignore` 生成新 Revision，直至每个 `targetRskuKey` 只剩一份有效 Quote；
- 上述是「同一 Snapshot 内多 Quote 竞争」校验；收敛为单份后，它与数据库已有 current quote 的比较再按§47.1.5 `quotePolicy` 执行。

---

# 39. P0：Price History

当前价：

```text
rsku_supply
```

历史：

```text
price_history
```

历史记录必须不可变，同时必须支持 Import Recovery 幂等。

建议新增：

```text
source_batch_id VARCHAR(64)
source_row_id BIGINT
source_column_index INTEGER
source_header VARCHAR(256)

currency_code VARCHAR(8)
tax_included BOOLEAN
price_unit VARCHAR(32)

effective_from DATE
effective_to DATE

change_type VARCHAR(32)
quote_version INTEGER

import_attempt_id VARCHAR(64)
import_group_id VARCHAR(64)
idempotency_key VARCHAR(128)
```

Change Type：

```text
initial
price_update
validity_update
tax_update
manual_override
import_correction
```

数据库幂等约束：

```sql
CREATE UNIQUE INDEX uk_price_history_import_idempotency
ON price_history(idempotency_key)
WHERE idempotency_key IS NOT NULL;
```

Import 产生的 History Key 必须由**确定性输入事实**生成，而不是由当前 `quote_version` 或时间戳生成。

推荐材料：

```text
attempt_id
+
group_id
+
rsku_id
+
change_type
+
source position
+
target sensitive HMAC / normalized target value hash
```

示例：

```text
IMP-A01
|GROUP-G12
|RSKU-001
|price_update
|sheet0:r27:c8
|HMAC-v2:ABC...
```

这样 Worker 在数据库事务提交后崩溃并重试时，不会重复追加同一条价格历史。

---

# 40. P0：Field-Level Lineage

目标：

```text
任何关键事实
↓
都能反查
batch
sheet
row
column
raw source
normalized source
transform version
```

但采购成本必须按敏感数据处理。

---

# 41. import_entity_lineage

P0 首版不强制引入分区。

原因：

1. 当前导入规模仍有明确行数限制；
2. 正确 B-Tree 索引已经可以覆盖早期 Lineage 规模；
3. 按 `batch_id` Hash 分区并不能拆散“单个超大 batch”的 100 万条 Lineage；
4. 分区会增加 Migration、GC、索引和运维复杂度。

先建立普通表并完成容量基线：

```sql
CREATE TABLE import_entity_lineage (
    lineage_id BIGSERIAL PRIMARY KEY,

    batch_id VARCHAR(64) NOT NULL,
    source_row_id BIGINT,

    source_sheet_index INTEGER,
    source_row_index INTEGER,
    source_column_index INTEGER,
    source_header VARCHAR(256),

    product_group_key VARCHAR(256),

    entity_type VARCHAR(32) NOT NULL,
    entity_id VARCHAR(64) NOT NULL,
    target_field VARCHAR(128) NOT NULL,

    raw_value TEXT,
    normalized_value TEXT,

    sensitive_value TEXT,
    encryption_key_version INTEGER,

    value_hmac VARCHAR(64),
    hmac_key_version INTEGER,

    data_classification VARCHAR(32) NOT NULL DEFAULT 'normal',

    mapping_version VARCHAR(64),
    normalizer_version VARCHAR(64),
    transform_type VARCHAR(64),

    idempotency_key VARCHAR(128) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    retention_until TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_import_lineage_batch
        FOREIGN KEY(batch_id)
        REFERENCES excel_import_batch(batch_id)
        ON DELETE CASCADE,

    CONSTRAINT uk_import_lineage_idempotency
        UNIQUE(batch_id, idempotency_key)
);
```

索引：

```sql
CREATE INDEX idx_lineage_entity_field
ON import_entity_lineage(entity_type, entity_id, target_field);

CREATE INDEX idx_lineage_batch_row
ON import_entity_lineage(batch_id, source_row_id);

CREATE INDEX idx_lineage_source_position
ON import_entity_lineage(
    batch_id,
    source_sheet_index,
    source_row_index,
    source_column_index
);

CREATE INDEX idx_lineage_retention
ON import_entity_lineage(retention_until, batch_id);
```

### 41.1 分区启用条件

只有性能基线证明普通表不能满足目标时，才进入新的 ADR 决定：

```text
是否分区
分区键
分区数
迁移策略
```

触发信号包括：

- Lineage 总量进入千万级以上并持续增长；
- VACUUM / GC 无法在目标窗口完成；
- Entity / Batch 查询 p95 明显超过目标；
- 单表索引尺寸导致可观测的写放大或缓存压力。

不在 P0 文档中预先写死 `16 HASH PARTITION`。

---

# 42. Lineage 与成本数据分级

## 42.1 normal

允许：

```text
raw_value
normalized_value
```

例如：

```text
product_name
dimensions
module_code
material_type
lead_time
```

普通非敏感内容完整性可以继续使用普通 SHA-256 / Canonical Hash。

## 42.2 cost_price_exact

适用于精确采购成本：

```text
rsku_supply.factory_price
Excel 单元格中的具体工厂报价
price_history.old_price/new_price
```

必须：

```text
raw_value = NULL
normalized_value = NULL

sensitive_value = AES(...)
encryption_key_version = N

value_hmac = HMAC-SHA-256(...)
hmac_key_version = M

data_classification = 'cost_price_exact'
```

必须经过：

```text
authorization
+
data scope
+
cost permission
```

后才允许解密。

原因：

> 采购价格属于低熵数据。普通 `SHA-256("9500")` 可以通过枚举金额反查，因此敏感值的等值摘要必须是带秘密密钥的 HMAC，而不是公开 Hash。

## 42.3 cost_price_summary

当前系统已经存在聚合成本类数据，例如：

```text
rspu_price_summary.min_factory_price
rspu_price_summary.max_factory_price
```

这类是聚合采购成本投影，可以保留明文数值以支持内部查询和筛选，但必须标记为：

```text
cost_price_summary
```

并遵守：

- 仅内部授权角色可见；
- 客户销售 Agent 不暴露；
- 不允许从汇总值推导单 RSKU 精确成本；
- API / DTO 必须显式区分 `salePrice`、`factoryPriceSummary`、`factoryPriceExact`。

「不允许推导精确成本」必须落成确定性输出规则，不能只依赖前端隐藏：

```text
active_rsku_count < 2
OR min_factory_price = max_factory_price
→ 该 Summary 可反推精确成本
→ 按 cost_price_exact 权限处理
```

具体规则：

- 具有 `cost_price_exact` 权限且数据范围命中时，可通过明确的 `factoryPriceExact` 输出等价精确值，不得把它伪装成普通 Summary 绕过 exact 审计；
- 只有 `cost_price_summary` 权限时，上述两种情况的 `min/max` 均返回 `null`，只可返回 `hasFactoryQuote=true`，不得返回单点或可逆推区间；
- `active_rsku_count >= 2 AND min_factory_price <> max_factory_price` 时，才可按 `cost_price_summary` 权限返回明文区间；
- Controller、Export、Agent、搜索投影统一调用领域服务执行此规则，禁止直接序列化 `rspu_price_summary.min/max_factory_price`。

因此安全原则不是“所有成本字段一律数据库 AES”，而是：

```text
精确 RSKU 成本
→ 加密 + HMAC

允许业务使用的聚合成本投影
→ 可明文，但仍是受限内部数据
```

## 42.4 Snapshot 敏感值

Preview Snapshot 和 Issue Resolution 的敏感报价统一使用：

```text
excel_import_sensitive_value
```

普通 JSON Payload 只能保存 Sensitive Ref。

## 42.5 Key Rotation

敏感值必须保存：

```text
encryption_key_version
hmac_key_version
```

Key Rotation 时：

- Ciphertext 可由受限后台任务就地重新加密，只更新 `ciphertext + encryption_key_version`；Ciphertext 不进入 Payload Hash，因此随机 IV 变化不会导致历史 Hash 漂移；
- `value_hmac + hmac_key_version` 是创建该 Revision 时的固定 Hash Material，在 Snapshot 保留期内禁止就地重算或覆盖；
- 新 Sensitive Value 使用当前 Active HMAC Key；旧 HMAC Key 保留「仅验证」能力，直到所有引用它的 Snapshot / Lineage 到期或解除 legal hold；
- 如果密钥泄漏等紧急事件要求在保留期内更换 HMAC，必须生成新 `sensitive_value_id` 和新 Preview Revision，重算 Payload / Snapshot Hash；不允许修改旧 Revision；
- 历史 Revision 的可重算性依赖其原 `hmac_key_version`，任何 Key 销毁操作都必须先证明引用计数为零。

本期不引入同一 `value_path` 的多版本 HMAC 并存表；Partial Unique 约束仍保证每个 Snapshot Owner / Path 只有一个固定摘要。

---

# 43. 成本数据安全规则

以下位置不能无控制保存 `cost_price_exact` 明文：

```text
excel_import_row.raw_data
excel_import_batch.preview_rows
excel_import_product_group.normalized_payload
import_data_issue.resolution_value
import_entity_lineage
audit log
application log
```

实施规则：

1. **Legacy Preview 数据清理**
   P0-B 切换到 Immutable Snapshot 后，`preview_rows` 不再作为事实源；敏感列写入前必须脱敏 / 抽离，最终停止新增明文成本。

2. **Normalized Snapshot**
   敏感值进入 `excel_import_sensitive_value`；普通 `normalized_payload` 只保存 Sensitive Ref。

3. **Issue Resolution**
   若修正值属于成本价，不允许直接写 `resolution_value`；必须先写 Sensitive Value，再保存引用。

4. **Lineage**
   精确成本只存密文 + Hash，不存 `raw_value/normalized_value` 明文。

5. **Batch / Factory Scope**
   访问 Raw / Preview / Lineage 时先校验 Batch 数据范围，再校验成本权限。

6. **解密顺序**
   必须：
   ```text
   authorization
   ↓
   data scope
   ↓
   decrypt
   ```
   禁止先解密后鉴权。

7. **日志**
   Audit Log 和 Application Log 禁止打印：
   ```text
   factory_price
   decrypted sensitive value
   raw price cell
   ```

8. **聚合成本**
   `rspu_price_summary` 属于 `cost_price_summary`，允许内部明文投影，但不能进入客户侧销售 Agent 输出。

9. **保留期限**
   Raw Excel、Snapshot、Sensitive Value、Lineage 必须进入 §44 的统一 Retention / Hold / GC 机制。

10. **API 输出模型**
    明确区分：
    ```text
    salePrice
    factoryPriceSummary
    factoryPriceExact
    ```
    禁止使用语义模糊的单一 `price` 字段跨越不同权限级别。

11. **公开哈希与可枚举派生信息禁令**
    除明文外，禁止将敏感值的公开哈希（无密钥 SHA-256 等）、明文长度或其他可枚举派生信息写入日志、导出或任何可枚举侧信道。低熵成本金额的公开哈希可被离线枚举反查，等值摘要只能是带密钥的 HMAC（§42.2）；日志只允许记录字段路径与错误类型，解密/脱敏失败一律 fail-closed（剔除敏感字段或置空快照 + 告警），不得 fail-open 落原值。

12. **筛选/排序预言机规则**
    §42.3 的防反推降级同时约束筛选与排序，不只约束输出：仅 `cost_price_summary` 权限的主体不得按真实成本值（如 `min_factory_price`）做区间筛选或排序；降级生效（`active_rsku_count < 2` 或 `min = max`）的 RSPU 对该主体退出成本口径筛选/排序，或筛选/排序统一改用与输出相同的降精度投影。否则响应置 null 而 SQL 仍按真实值执行，攻击者可通过二分区间查询反推被隐藏的精确值。筛选/排序实现统一走领域服务，与输出规则同一判定口径。

## 43.1 成本权限主体矩阵

「cost permission」是独立于 role / data scope 的第三层判定，其主体模型收口如下（映射现有 `DataScopeHelper.canViewFactoryPrice` 语义，不改变现有授权决策，只补齐声明）：

| 主体 | cost_price_exact | cost_price_summary | 数据范围约束 |
|---|---|---|---|
| 平台员工（平台管理员、采购/供应链运营，data scope = ALL） | 是 | 是 | 全量，仍受 data scope 先行过滤 |
| 工厂管理员（FACTORY_ADMIN，data scope = FACTORY_LIST） | 仅本厂 RSKU 的精确成本 | 仅本厂参与的 RSPU 聚合投影 | 仅本厂（现有 `canViewFactoryPrice` 本厂语义） |
| 客户侧角色 / 客户销售 Agent / 公共 AI 匹配 | 否 | 否；仅按 §42.3 规则返回 `hasFactoryQuote` 布尔位，降级场景 min/max 一律 null | 不接触成本口径 |
| 导出 / 后台任务 / 审计写入主体 | 继承发起者权限，无独立提权 | 同左 | 同左 |

任何新增角色默认两级均无；授予 exact 必须同时显式登记审计要求。

## 43.2 Key Custody

AES / HMAC Key 的生成、存储与分发责任模型（「项目统一加密体系」的出处收口）：

- **来源**：密钥由 KMS / Vault 或等价密钥管理服务下发，经环境变量 / 启动注入进入应用；密钥明文不进入代码库、配置仓库、镜像与日志；
- **生成**：使用 CSPRNG，密钥长度 ≥ 256 bit；AES Key 与 HMAC Key 逻辑分离、各自独立生成与轮换；
- **版本化 keyring**：运行时按 `encryption_key_version` / `hmac_key_version` 从 keyring 取 Key 解密/验证，仅 Active 版本用于加密/签发（与 §42.5 旋转语义一致）。现有 `AesEncryptionUtil` 的单把静态配置 Key 无版本概念，P0-E 前必须升级为 versioned keyring，演进路径以此为准；明文解密兜底属历史迁移产物，迁移验收完成后移除并纳入 §85.2 B 类门禁验证；
- **审计**：密钥的读取、轮换、销毁全部写审计；紧急泄漏场景的更换必须经双人审批控制，且按 §42.5 生成新 `sensitive_value_id` + 新 Preview Revision 重算，不改写历史。

---

# 44. 原始文件保留策略

默认保留值（可通过配置缩短，延长必须经业务/合规确认）：

```text
raw Excel                  90 天
unconfirmed preview snapshot 180 天
confirmed/imported snapshot  至少 365 天，且不早于 Attempt / Lineage 保留期
attempt detail             365 天
lineage                    365 天
tmp object                 24~72 小时
failed import artifact      30 天
```

若存在调查、审计或争议，在 `excel_import_batch` 设置 `retention_hold=true`，GC 必须跳过整个 batch；解除 hold 必须记审计日志。

## 44.1 Lineage 体量与容量基线

Lineage 不按「Excel 行数」估算，而按「持久化的字段事实数」估算：

```text
lineage_rows ≈ 有效源行数 × 每行追踪字段数 × 展开后实体系数
```

1 万行导入，按每行 30~100 个字段事实计算，单批约为 **30 万~100 万行 lineage**；横向报价 Unpivot、模块行和多 RSKU 可继续放大。P0-E 性能测试必须覆盖单批 100 万 lineage，并记录表体、索引体、批量写入耗时与 GC 耗时；容量预估不得只看主表数据大小。

## 44.2 GC 机制

- 新增 `async_task.task_type=import_lineage_gc`，每日低峰运行，也可由管理员手动触发；
- 只选择无 running/pending import attempt、`retention_hold=false` 且目标数据已达保留期的 batch；Lineage / Sensitive Value 使用各自 `retention_until`，Snapshot 按 `created_at + retention policy` 并结合 Confirm / Attempt 引用判定；
- 以 `batch_id` 为单位清理，但单次 DELETE 最多 5,000 行，分页提交，避免长事务、WAL 峰值和长时间锁；
- 未 Confirm 且未被 Attempt 引用的旧 Revision 可在 180 天后清理；已 Confirm / Import 的 Revision 只有在关联 Attempt Detail 和 Lineage 也同时到期后才可清理，禁止为满足 180 天而提前删除可追溯输入；
- 清理顺序为 lineage 明细 → 将确认/导入证据写入 `excel_import_batch.snapshot_archive_summary` → 到期 AttemptItem / Attempt 明细 → 清空指向目标 Revision 的 `preview_revision / confirmed_revision` 活跃指针 → preview snapshot → raw row / raw file；
- `snapshot_archive_summary` 只保留 revision number、hash/version、confirm actor/time、Attempt 终态及成功/失败计数，不得包含 Raw、Normalized Payload、成本或可解密 Sensitive Value；此摘要与 Batch 结果元数据长期保留；
- 上述指针归档与 Snapshot 删除必须在同一个 Batch 锁定事务内由§20.1 的受限 GC 函数完成；任一 FK / retention 检查失败则整个 Revision 保留；
- 任务记录 scanned/deleted/failed batch 数、删除行数、WAL 估算与耗时，失败可幂等续跑；
- 每批清理后依赖 autovacuum 回收空间，不在业务任务中执行 `VACUUM FULL`；表膨胀率、索引膨胀率与 autovacuum lag 纳入运维监控；
- 若普通表在基准测试后触发 §41.1 的分区门槛，再单独通过 ADR 调整 GC 与分区清理策略。

原始文件、Snapshot 和 Lineage 的保留时间均由上述策略自动执行，不再只停留在文档天数约定。

---

# 45. P0：Preview Revision

`excel_import_batch` 增加：

```text
preview_revision BIGINT
mapping_hash VARCHAR(64)
normalizer_version VARCHAR(64)

confirmed_revision BIGINT
confirmed_by VARCHAR(64)
confirmed_at TIMESTAMPTZ

import_policy JSONB
retention_hold BOOLEAN NOT NULL DEFAULT FALSE
snapshot_archive_summary JSONB NOT NULL DEFAULT '{}'::jsonb
```

其中：

```text
preview_revision
```

是当前最新 Revision 指针；

```text
confirmed_revision
```

是已确认 Revision 指针。

二者在有值时都必须引用同一个 Batch 下真实存在的 Snapshot Revision。

数据库约束：

```sql
ALTER TABLE excel_import_batch
ADD CONSTRAINT fk_excel_import_batch_current_revision
FOREIGN KEY(batch_id, preview_revision)
REFERENCES excel_import_preview_revision(batch_id, preview_revision);

ALTER TABLE excel_import_batch
ADD CONSTRAINT fk_excel_import_batch_confirmed_revision
FOREIGN KEY(batch_id, confirmed_revision)
REFERENCES excel_import_preview_revision(batch_id, preview_revision);
```

由于 `preview_revision / confirmed_revision` 可以为空，尚未生成 Preview、尚未 Confirm，或已通过受限 GC 归档快照的 Batch 可以保持 NULL；一旦有值，数据库必须保证对应 Revision 存在。

`snapshot_archive_summary` 不是可继续导入的 Snapshot，只是详细数据超过保留期后的非敏感审计摘要。该字段只能由§20.1 的受限 GC 函数写入，普通 Batch Update Repository 不得暴露修改入口。Confirm / Import API 在活跃指针为 NULL 且已存在归档摘要时必须返回 `410 snapshot_expired`，不得根据摘要重建或重放历史 Snapshot。

---

# 46. Revision 变化规则

以下任何操作：

```text
修改 column mapping
修改 source value
修改 product group
修改 factory
修改 category
修改 category mode / category hint / candidate categories
修改 rowCategorySelections 中任一行最终品类
修改 material mapping
```

必须：

```text
preview_revision + 1
```

并生成新的 immutable normalized snapshot。

---

# 47. Optimistic Lock

编辑：

```json
{
  "expectedRevision": 7,
  "categoryConfig": {
    "mode": "MIXED",
    "categoryHint": null,
    "candidateCategoryCodes": ["DINING_TABLE", "DINING_CHAIR"]
  },
  "rowCategorySelections": {
    "27": "DINING_CHAIR",
    "28": "DINING_TABLE"
  },
  "edits": []
}
```

字段可按操作省略，省略表示继承 `expectedRevision` 的值；显式传空按 09 方案和 DTO 校验语义处理，不以「空=不改」模糊化。

在 MIXED 下，修改候选集先立即产生一个新 Revision，并使旧 AI 推荐失效；候选集受限的 AI 归一/分类任务记住该基础 Revision，完成时只能在它仍是当前 Revision 时原子插入下一个 Snapshot；若期间有人工编辑，任务结果标记 stale，不得覆盖新 Revision。

如果当前：

```text
revision = 8
```

返回：

```text
409 preview_revision_conflict
```

---

## 47.1 Canonical Hash Protocol

文档中的：

```text
payload_hash
snapshot_hash
mapping_hash
policy_hash
```

属于普通完整性 / 幂等 Hash，统一使用：

```text
CanonicalJsonHasher
+
SHA-256
```

敏感低熵值不使用普通 Hash，统一使用：

```text
SensitiveValueHmacService
+
HMAC-SHA-256
```

两类能力必须分开。

### 47.1.1 Canonical JSON 规则

统一规则：

1. 编码：UTF-8；
2. Object Key：固定字典序——按 Key 的 UTF-8 字节序列排序（对 ASCII 与 Unicode code point 序一致，对 CJK 键以字节序为准；当前各 Hash Material 的键均为 ASCII，两种解释不产生分歧，此规定用于消除未来扩展歧义）；
3. 数字：规范十进制文本，不使用科学计数法，去除无意义尾随 0；数字一律以十进制文本 / 定点数（如 BigDecimal）进入 Canonical 层，禁止 IEEE-754 float / double 直传（二进制表示误差会导致同一业务值产生不同字节）；
4. Boolean：仅 `true / false`；
5. Null：是否保留由 Schema 定义，调用方不可自行省略；本文各 Hash Material 契约统一采用「显式 null 保留、序列化为 `null`」，不允许「缺省」与「null」产生两种合法字节；
6. String：使用业务 Normalize 后的值；
7. Array：默认顺序有意义；如果业务语义是集合，调用前先排序；
8. Timestamp：统一 ISO-8601 UTC；
9. 不允许把随机 IV 导致变化的 Ciphertext 作为业务 Hash Material；
10. 所有协议必须显式版本化。

### 47.1.2 PayloadHashMaterial

```text
payload_hash_version
+
canonical(public normalized payload)
+
按 value_path 排序的：
    sensitiveRef
    value_hmac
    hmac_key_version
```

因此敏感值变化会改变 Payload Hash，但同一业务值重新加密得到不同 Ciphertext 时不会造成 Hash 漂移。

### 47.1.3 SnapshotHashMaterial

禁止直接把整行 `excel_import_preview_revision` 序列化后计算，因为其中包含 `snapshot_hash` 本身，会形成自引用。

明确 Material：

```json
{
  "hashVersion": 1,
  "batchId": "BATCH-001",
  "previewRevision": 7,
  "sourceSheetIndex": 0,
  "sourceSheetName": "沙发",
  "categoryPolicy": {
    "mode": "MIXED",
    "categoryHint": null,
    "candidateCategoryCodes": ["SOFA", "CHAIR"]
  },
  "mappingHash": "...",
  "normalizerVersion": "...",
  "groups": [
    {
      "productGroupKey": "...",
      "payloadHashVersion": 1,
      "payloadHash": "..."
    }
  ]
}
```

明确排除：

```text
snapshot_hash
created_at
created_by
DB generated IDs
runtime status
attempt result
lease / heartbeat
```

然后：

```text
snapshot_hash =
SHA-256(
    canonical(SnapshotHashMaterial)
)
```

`groups` 是集合语义：进入 Material 前必须按 `productGroupKey` 的 UTF-8 字节序排序，保证多节点实现对同一逻辑快照产出相同字节。

### 47.1.4 mapping_hash

材料：

```text
hash_version
+
column mapping
+
category policy
+
alias / dict version references
```

### 47.1.5 policy_hash

材料是「默认值已完整物化」的不可变 Policy Snapshot，不是前端传入的稀疏 JSON：

```text
policy_hash_version
+
policy_schema_version
+
canonical(policy_payload)
```

`policy_schema_version=1` 的完整契约：

```json
{
  "updateExisting": false,
  "allowWarnings": false,
  "conflictPolicy": "fail_group",
  "quotePolicy": "replace_if_newer",
  "nullValuePolicy": "preserve_existing",
  "manualCorrectionPolicy": "preserve",
  "overrideReason": null
}
```

枚举和覆盖规则：

- `updateExisting=false`（默认）：命中已有 RSPU / RSKU 时不改写，生成冲突 Issue；`true` 才允许进入字段更新规则；
- `allowWarnings=false`（默认）：存在未 acknowledge / resolve 的 warning 时禁止新执行；`true` 也不能放行 blocker；
- `conflictPolicy=fail_group`（默认）：任一未解决目标冲突使 Group 进入终态 `rejected`；`skip_invalid_target` 只可按 Issue Target 跳过明确的 Variant / Quote，不得跳过 product blocker 或数据库完整性约束；
- `quotePolicy=replace_if_newer`（默认）：只按下方条件表执行，不得用 SQL null ordering 或遍历顺序猜测；
- `quotePolicy=keep_existing`：命中已有报价时不改写，记录 skipped result；
- `quotePolicy=force_replace`：仅具有报价覆盖权限的人工导入可用，`overrideReason` 必须非空并记审计；其他 quotePolicy 下 `overrideReason` 必须为 `null`；不允许 AI / 后台重试自动选择；
- `nullValuePolicy=preserve_existing`（默认）：空单元格永不清空已有值；`clear_explicit_only` 只处理 Snapshot 中具有 `explicitClear=true` 且字段在允许清空白名单的人工修正；
- `manualCorrectionPolicy=preserve`（默认）：以§34.1 `manual_override_fields` 为唯一当前事实源，既有活跃人工修正值不被 Excel / AI 覆盖；`replace_explicit` 只允许 Snapshot 中同一 target/field 存在新的显式人工修正时替换。

Quote 基础口径为 `currency_code + price_unit + tax_included`。`replace_if_newer` 不跨基础口径自动覆盖；基础口径不同时直接生成 `QUOTE_BASIS_CONFLICT`，只能在具备权限和 reason 的 `force_replace` 下处理。

`quoteValueSame` 比较标准化后的价格 HMAC、基础口径、MOQ 和有效期等业务字段，排除 `quote_date`、来源坐标、密文随机性和 Runtime 字段。价格 HMAC Key Version 相同时可直接安全比较；Version 不同时必须在通过成本权限后解密并按同一临时 Key/Canonical Value 重算等值，不得比较密文或直接将不同 Key Version 的 HMAC 判为不等。在基础口径相同时，处理表为：

| Incoming `quote_date` | Existing `quote_date` | `quoteValueSame` | `replace_if_newer` 结果 |
|---|---|---:|---|
| 严格晚于 | 有值 | 任意 | 更新；值未变时只记 `validity_update`，不记伪价格变更 |
| 严格早于 | 有值 | 任意 | 保留已有值，记 `STALE_QUOTE_IGNORED` |
| 相同 | 有值 | true | no-op，不增长 `quote_version` / History |
| 相同 | 有值 | false | `QUOTE_SAME_DATE_CONFLICT` |
| 有值 | `NULL` | 任意 | 更新；有日期事实优先于无日期事实 |
| `NULL` | 有值 | true | no-op |
| `NULL` | 有值 | false | `QUOTE_DATE_MISSING_CONFLICT` |
| `NULL` | `NULL` | true | no-op |
| `NULL` | `NULL` | false | `QUOTE_UNDATED_CONFLICT` |

Policy 执行优先级固定为：

```text
权限 / 数据库完整性
→ manualCorrectionPolicy
→ nullValuePolicy
→ quotePolicy
```

`force_replace` 不会隐式绕过 `manualCorrectionPolicy=preserve`；覆盖已标记的人工值时，请求必须同时使用 `manualCorrectionPolicy=replace_explicit`，且 Snapshot 对该 field 必须有 `manualInput=true + operator + editedAt`。

每个枚举都必须拒绝未知值；服务端在创建 Attempt 前先补齐所有默认值，再计算 Hash 并把完整正文与 Hash 在同一事务写入。

不得包含：

```text
requestId
timestamp
前端展示字段
worker id
```

### 47.1.6 Hash Version

数据库必须保存实际版本：

```text
excel_import_preview_revision.hash_version
excel_import_product_group.payload_hash_version
excel_import_attempt.policy_hash_version
```

未来修改 Canonical 规则时必须升级版本，不能静默改变历史 Revision / Attempt 的 Hash 结果。

### 47.1.7 Sensitive Value HMAC

精确采购成本等低熵敏感值：

```text
value_hmac =
HMAC-SHA-256(
    secret_key_version_N,
    canonicalSensitiveValue
)
```

同时保存：

```text
hmac_key_version
```

HMAC Key 与 Encryption Key 逻辑分离。

普通：

```text
Fingerprint
Snapshot Hash
Payload Hash
Policy Hash
```

继续使用公开 SHA-256，因为它们的目标是内容完整性 / 确定性，而不是隐藏秘密。

---

# 48. P0：Import Attempt Idempotency

仅有 Revision 不够。

同一个 Revision 的 Import 请求也必须有幂等记录。

---

# 49. excel_import_attempt

`excel_import_attempt` 表示：

> 对某一个 immutable Preview Revision，使用某一 Import Policy 发起的一次逻辑执行。

```sql
CREATE TABLE excel_import_attempt (
    attempt_id VARCHAR(64) PRIMARY KEY,

    batch_id VARCHAR(64) NOT NULL,
    preview_revision BIGINT NOT NULL,

    policy_schema_version SMALLINT NOT NULL DEFAULT 1,
    policy_payload JSONB NOT NULL,
    policy_hash_version SMALLINT NOT NULL DEFAULT 1,
    policy_hash VARCHAR(64) NOT NULL,

    status VARCHAR(16) NOT NULL,
    outcome VARCHAR(32),
    result_summary JSONB NOT NULL DEFAULT '{}'::jsonb,

    task_id VARCHAR(64),

    retry_count INTEGER NOT NULL DEFAULT 0,

    lease_owner VARCHAR(128),
    last_heartbeat_at TIMESTAMPTZ,
    lease_expires_at TIMESTAMPTZ,

    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,

    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    error_code VARCHAR(64),
    error_message TEXT,

    CONSTRAINT fk_import_attempt_revision
        FOREIGN KEY(batch_id, preview_revision)
        REFERENCES excel_import_preview_revision(batch_id, preview_revision)
        ON DELETE RESTRICT,

    CONSTRAINT uk_import_attempt
        UNIQUE(batch_id, preview_revision, policy_hash_version, policy_hash),

    CONSTRAINT uk_import_attempt_revision_id
        UNIQUE(attempt_id, batch_id, preview_revision),

    CONSTRAINT ck_import_attempt_status
        CHECK(status IN ('pending', 'running', 'done', 'failed')),

    CONSTRAINT ck_import_attempt_retry_count
        CHECK(retry_count >= 0),

    CONSTRAINT ck_import_attempt_policy_schema_version
        CHECK(policy_schema_version > 0),

    CONSTRAINT ck_import_attempt_policy_payload
        CHECK(jsonb_typeof(policy_payload) = 'object'),

    CONSTRAINT ck_import_attempt_policy_hash_version
        CHECK(policy_hash_version > 0),

    CONSTRAINT ck_import_attempt_outcome
        CHECK(
            (status = 'done'
                AND outcome IS NOT NULL
                AND outcome IN ('success', 'partial_success', 'business_rejected'))
            OR (status <> 'done' AND outcome IS NULL)
        ),

    CONSTRAINT ck_import_attempt_result_summary
        CHECK(jsonb_typeof(result_summary) = 'object')
);
```

`policy_payload / policy_schema_version / policy_hash_version / policy_hash` 在 Attempt INSERT 后不可更改。`excel_import_batch.import_policy` 只是 UI 的当前草稿/默认值，不是执行或恢复事实源；Worker 和 Recovery 只读 Attempt 上的 Policy Snapshot。

索引：

```sql
CREATE INDEX idx_import_attempt_running_lease
ON excel_import_attempt(status, lease_expires_at)
WHERE status = 'running';
```

## 49.1 Lease / Heartbeat

Worker 执行 Attempt 时必须原子获取：

```text
lease_owner
lease_expires_at
```

执行期间周期更新：

```text
last_heartbeat_at
lease_expires_at
```

如果：

```text
status = running
AND lease_expires_at < now()
```

Recovery Worker 可以重新抢占。

这样可以处理：

```text
JVM crash
进程被杀
节点失联
```

导致 Attempt 永久停留 `running` 的问题。

## 49.2 excel_import_attempt_item

`excel_import_attempt_item` 是 Runtime 结果和恢复 Checkpoint 的唯一载体。

```sql
CREATE TABLE excel_import_attempt_item (
    attempt_id VARCHAR(64) NOT NULL,

    batch_id VARCHAR(64) NOT NULL,
    preview_revision BIGINT NOT NULL,
    group_id VARCHAR(64) NOT NULL,

    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    checkpoint VARCHAR(32),

    variant_success_count INTEGER NOT NULL DEFAULT 0,
    variant_failed_count INTEGER NOT NULL DEFAULT 0,
    quote_success_count INTEGER NOT NULL DEFAULT 0,
    quote_failed_count INTEGER NOT NULL DEFAULT 0,

    result_summary JSONB,

    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY(attempt_id, group_id),

    CONSTRAINT fk_import_attempt_item_attempt
        FOREIGN KEY(attempt_id, batch_id, preview_revision)
        REFERENCES excel_import_attempt(
            attempt_id,
            batch_id,
            preview_revision
        )
        ON DELETE CASCADE,

    CONSTRAINT fk_import_attempt_item_group
        FOREIGN KEY(batch_id, preview_revision, group_id)
        REFERENCES excel_import_product_group(
            batch_id,
            preview_revision,
            group_id
        )
        ON DELETE RESTRICT,

    CONSTRAINT ck_import_attempt_item_status
        CHECK(
            status IN (
                'pending',
                'running',
                'imported',
                'partial',
                'rejected',
                'failed',
                'skipped'
            )
        ),

    CONSTRAINT ck_import_attempt_item_counts
        CHECK(
            variant_success_count >= 0
            AND variant_failed_count >= 0
            AND quote_success_count >= 0
            AND quote_failed_count >= 0
        )
);
```

索引：

```sql
CREATE INDEX idx_import_attempt_item_status
ON excel_import_attempt_item(attempt_id, status);

CREATE INDEX idx_import_attempt_item_group
ON excel_import_attempt_item(batch_id, preview_revision, group_id);
```

### 49.2.1 AttemptItem 状态语义

```text
pending
= 尚未处理

running
= 当前 Worker 正在处理

imported
= 该 Group 所有允许写入内容成功

partial
= Product 成功，但存在被拒绝的 Variant / Quote

rejected
= 确定性业务规则拒绝整个 ProductGroup / product_blocking，是不会因原样重试而改变的终态

failed
= 可重试的技术失败，例如数据库短暂错误、Worker 异常或副作用任务写入失败

skipped
= 策略明确跳过
```

### 49.2.2 Checkpoint 原则

Checkpoint 只表示：

```text
当前 Group 的可恢复执行阶段
```

例如：

```text
validated
identity_resolved
persisting
committed
```

但真正的“已完成”判断必须依赖：

```text
attempt_item.status in (imported, partial, rejected, skipped)
```

而不能只依赖内存状态。

### 49.2.3 Attempt 汇总终态

Attempt 状态和业务结果分开：

```text
全部 AttemptItem ∈ imported / partial / rejected / skipped
→ attempt.status = done

不存在 partial / rejected，且全部为 imported / skipped（允许全部 skipped）
→ outcome = success

存在 partial，或同时存在 imported 与 rejected
→ outcome = partial_success

至少一个 rejected，其余只能是 rejected / skipped，且无任何实体写入
→ outcome = business_rejected
```

`attempt.status=failed` 只表示尚未完成逻辑执行的技术失败，可重试同一 Attempt；不得因某个 Group 被业务规则拒绝而把整个 Attempt 标记为 `failed`。

Attempt 进入 `done` 时必须在同一事务物化 `outcome + result_summary`；`result_summary` 至少包含各终态 Group 计数、Variant/Quote 成功失败计数和结果定位信息，供 done Replay 原样返回。

---

# 50. Import 重试与恢复语义

相同：

```text
batch
revision
policy_hash_version
policy_hash
```

再次 Import：

```text
返回 / 恢复已有 Attempt
```

不能创建第二个逻辑 Attempt。

## 50.0 Replay-first 分支顺序

「当前 Preview 是否仍等于 expectedRevision」只决定能否创建**新 Attempt**，不能阻断已完成 Attempt 的历史结果重放。Import API 顺序固定为：

```text
1. 校验请求结构 / policy_schema_version
2. 鉴权 + Batch 数据范围校验
3. 对请求 Policy 补齐该 Schema Version 的默认值，计算 policy_hash
4. 按 batch + expectedRevision + policy_hash_version + policy_hash 查找 Attempt

IF existing.status = done
    校验存储的 Policy Hash / Result Summary 完整性
    直接返回存储的 outcome + result_summary
    不检查 Batch 当前 Revision / Confirm / 新 Issue

IF existing.status in (pending, running, failed)
    进入同一 Attempt 的状态返回或 Recovery
    Recovery 只读 existing.policy_payload 和 existing.preview_revision
    校验被引用 Snapshot/Hash 仍存在，但不要求它仍是 Batch 当前 Revision

IF no existing attempt
    才检查 expectedRevision = currentRevision = confirmedRevision
    加载 Snapshot / Hash
    revalidate 当前确定性业务条件
    检查 blocker / warning policy
    INSERT immutable Attempt + AttemptItems
```

因此，导入成功后响应丢失、之后用户又编辑了 Preview 的情形，原请求重试仍返回原 `done` 结果；但不允许对旧 Revision + 新 Policy 创建新 Attempt。

## 50.1 Attempt 状态

### done

```text
直接返回已有结果
```

禁止重新执行。

### running + lease 未过期

```text
返回当前 Attempt 状态
```

不创建新执行。

### running + lease 已过期

Recovery Worker：

```text
CAS / atomic update 抢 lease
↓
扫描未完成 AttemptItem
↓
继续执行
```

### failed

只允许：

```text
retry same attempt
```

并：

```text
retry_count + 1
```

不能把同一逻辑请求当成全新 Attempt。

## 50.2 Group Exactly-Once-Like 边界

核心要求：

> Product Domain 写入与 AttemptItem 最终状态更新必须处于同一个数据库事务。

推荐：

```text
BEGIN

SELECT attempt_item
FOR UPDATE

IF status IN ('imported','partial','rejected','skipped')
    RETURN

status → running

resolve/create RSPU
resolve/create supplier_product_ref

persist valid Variant
persist valid RSKU
persist price_history
persist lineage
persist async_task DB record

UPDATE attempt_item
SET
  status = imported/partial/skipped,
  counts = ...,
  checkpoint = 'committed',
  completed_at = now()

COMMIT
```

结果：

### Commit 前崩溃

```text
领域数据
AttemptItem
```

全部回滚。

Recovery 可以安全重做。

### Commit 后崩溃

AttemptItem 已经：

```text
imported / partial / rejected / skipped
```

Recovery 扫描时直接跳过，不会再次增加：

```text
quote_version
price_history
lineage
async_task
```

这才是恢复正确性的主要落点。

## 50.3 副作用幂等键

即使有 AttemptItem，同一事务中的可重复写入对象仍需数据库幂等保护。

### price_history

使用 §39：

```text
idempotency_key
```

并有数据库 Unique。

### lineage

继续使用：

```text
UNIQUE(batch_id, idempotency_key)
```

### async_task

必须增加：

```text
idempotency_key
```

并建立：

```sql
CREATE UNIQUE INDEX uk_async_task_type_idempotency
ON async_task(task_type, idempotency_key)
WHERE idempotency_key IS NOT NULL;
```

推荐 Key：

```text
embedding:imageId:sourceRevision
ai-recognition:imageId:sourceRevision
```

### storage finalize

Finalize Task Key：

```text
finalize:imageId:sourceRevision
```

重复 enqueue 必须：

```text
ON CONFLICT DO NOTHING
```

或返回既有任务。

## 50.4 Quote Version

禁止 Recovery 每次无条件：

```text
quote_version + 1
```

因为 Group Commit 与 AttemptItem Finalize 同事务，所以：

```text
已完成 Group
→ Recovery 不再次进入 RSKU Update
```

同时 RSKU Update 保持 optimistic version check。

这样无需把 Import Attempt 字段污染到所有领域主表。

---

# 51. P1：Object Storage 状态机

对象存储不属于数据库事务。

不能简单：

```text
DB commit
↓
同步 promote
```

而没有状态。

---

# 52. Storage State

图片 / object metadata 增加逻辑状态：

```text
temporary
finalizing
ready
failed
```

读取端：

```text
只展示 ready
```

---

# 53. Finalize 机制

推荐：

```text
Upload tmp
↓
DB transaction writes image metadata(state=temporary)
↓
COMMIT
↓
enqueue durable finalize task
↓
copy/move/finalize
↓
state=ready
```

如果 JVM 崩溃：

```text
task 仍可恢复
```

后台：

```text
扫描 temporary/finalizing 超时记录
↓
retry
```

孤儿 tmp：

```text
TTL GC
```

---

# 54. AI / Vector Task

不新增新的通用 Outbox。

继续复用：

```text
async_task
+
事务内创建
+
afterCommit dispatch
```

但 v3.3 要求把“幂等”真正落到数据库。

建议 `async_task` 增加：

```text
idempotency_key VARCHAR(192)
batch_id VARCHAR(64)
attempt_id VARCHAR(64)
group_id VARCHAR(64)
entity_type VARCHAR(32)
entity_id VARCHAR(64)
```

数据库约束：

```sql
CREATE UNIQUE INDEX uk_async_task_type_idempotency
ON async_task(task_type, idempotency_key)
WHERE idempotency_key IS NOT NULL;
```

推荐 Key：

```text
image_embedding:{imageId}:{sourceRevision}
ai_recognition:{imageId}:{sourceRevision}
storage_finalize:{imageId}:{sourceRevision}
```

要求：

- retry 不生成新逻辑任务；
- duplicate dispatch 安全；
- timeout recovery；
- dead / failed 状态；
- 可以从 Batch / Attempt / Group 反查；
- `afterCommit` 只负责调度，不承担唯一性保证；
- 唯一性最终依赖数据库约束。

---

# 55. Excel Normalizer

建议拆成独立组件：

```text
ProductCodeNormalizer
DimensionNormalizer
MaterialNormalizer
PriceNormalizer
LeadTimeNormalizer
MoqNormalizer
TextNormalizer
ImageAnchorNormalizer
```

每个：

```text
version
input
output
warnings
```

禁止 normalize 规则散落在大型 Service。

---

# 56. Excel 横向价格 Unpivot

Excel：

| 模块 | A级布 | AA级布 | S级布 |
|---|---:|---:|---:|
| 组合A | 8720 | 9500 | 11350 |

Normalized：

```text
Quote 1
sourceColumn = A级布
supplierGrade = A级布
supplierConfigurationKey = TEXT:A级布
price = 8720

Quote 2
sourceColumn = AA级布
supplierGrade = AA级布
supplierConfigurationKey = TEXT:AA级布
price = 9500

Quote 3
sourceColumn = S级布
supplierGrade = S级布
supplierConfigurationKey = TEXT:S级布
price = 11350
```

每个 Quote 均拥有独立：

```text
source row
source column
supplier configuration key
quoteKey
lineage
```

落库规则：

- 三个等级无可靠平台标准等级时，共享同一 `FABRIC` Variant，但按三个 `supplierConfigurationKey` 创建/命中三条 RSKU；
- 业务已确认 A / AA / S 映射为三个稳定平台 `standard_material_grade_code` 时，三个标准等级参与 Variant Identity，分别命中对应 Variant；Supplier Configuration 仍保留原厂等级身份；
- 两种路径都不允许把三个 Quote 依次 UPDATE 到同一 `(variant_id,factory_code,BASE)` RSKU。

---

# 57. Agent 改造原则

不新增平行 Agent API 体系。

继续扩展：

```text
MarketingProductQueryService
```

内部演进：

```text
MarketingProductQueryService
├─ ProductRecallService
├─ VariantSelectionService
├─ SupplyQuoteService
└─ DeterministicRankingService
```

---

# 58. Agent 查询链路

```text
用户输入
↓
LLM 解析结构化需求
↓
ProductRecallService
↓
RSPU recall
↓
VariantSelectionService
↓
尺寸 / 模块 / 材质
↓
PricingService
↓
销售价
↓
SupplyQuoteService
↓
供应状态 / 交期
↓
DeterministicRankingService
↓
Top N
↓
RecommendNode
↓
LLM 只生成 explanation
```

---

# 59. PricingService 必须统一

已完成的部分：`AgentRskuResolver` 已通过 `PricingService.resolveSalePriceDetail` 选取可定价 RSKU，不得回退为出厂价或直读 RSPU 建议价。

剩余收口点在 `MarketingProductQueryService`：当前结构化通道和向量融合通道的预算硬过滤直接生成 `retail_price <= budgetMax`，搜索结果也直接回填 `rspu.getRetailPrice()`。这只覆盖 `PricingService` 三级链的 MANUAL 来源，会错过 CATEGORY_RULE / GLOBAL 定价商品。

收口后的销售 Agent 价格唯一入口：

```text
PricingService
```

不能长期直接：

```text
rspu_master.retail_price
```

预算过滤目标也是：

```text
sale price
```

不是：

```text
factory_price
```

实施时不得把 Java `PricingService` 逐行调用硬塞进无界候选集。先用权限/品类/风格等可下推条件限定候选窗口，再批量取 RSKU 并通过 PricingService 计算标准售价完成预算过滤与结果回填。若后续增加数据库售价投影，其口径必须由 PricingService 规则驱动并带规则版本，不得形成第二套定价逻辑。

---

# 60. 成本权限

任何 factory price：

```text
先做 data scope / role check
↓
再解密
```

禁止：

```text
先批量解密
↓
再判断用户有没有权限
```

---

# 61. Deterministic Ranking Contract

```java
RankingResult rank(
    RequirementSnapshot requirement,
    List<ProductCandidate> candidates,
    RankingContext context
);
```

输出：

```text
rspuId
variantId
rank
score
scoreBreakdown
rankingVersion
evidence
```

---

# 62. 排序公式

示例：

```text
score =
styleWeight * styleScore
+ sizeWeight * sizeScore
+ materialWeight * materialScore
+ budgetWeight * budgetScore
+ availabilityWeight * availabilityScore
```

权重必须版本化：

```text
rankingVersion = 2026-09-v1
```

---

# 63. Missing Value 策略

禁止随机处理。

例如：

```text
尺寸缺失
→ 不参与 size hard match

价格缺失
→ budget score = unavailable

材质缺失
→ material score = unknown
```

---

# 64. Stable Tie-Break

分数相同时固定：

```text
1. availability
2. price completeness
3. product updated time
4. rspu_id
```

保证同输入稳定输出。

---

# 65. RecommendNode 改造

LLM 不再：

```text
选择 3~6 款
改变排序
删除业务候选
新增不存在商品
```

只允许：

```text
生成推荐理由
```

---

# 66. evidenceRefs

保留现有白名单校验。

未来扩展：

```text
productName
categoryPath
primaryImageUrl
material
sizeText
variant
salePrice
leadTime
module
```

所有 explanation 引用必须来自 Snapshot 白名单。

---

# 67. 推荐 Snapshot

客户侧 Snapshot 必须保存：

```text
rspuId
variantId
rskuId(optional)

salePrice
currencyCode
priceSource
pricingVersion
priceTimestamp
effectiveTo

rank
score
rankingVersion

evidence
```

不得保存 / 暴露：

```text
factory_price
```

除非是内部采购专用 Snapshot。

---

# 68. API 兼容策略

保留当前：

```http
POST /api/v1/products/excel-ai-import/import
```

不强制把 `batchId` 移到 URL。

请求增强：

```json
{
  "batchId": "BATCH-001",
  "expectedRevision": 7,
  "policySchemaVersion": 1,
  "policy": {
    "updateExisting": true,
    "allowWarnings": true,
    "conflictPolicy": "skip_invalid_target",
    "quotePolicy": "replace_if_newer",
    "nullValuePolicy": "preserve_existing",
    "manualCorrectionPolicy": "preserve",
    "overrideReason": null
  }
}
```

后端内部生成：

```text
policy_hash
```

用于 Import Attempt 幂等。

前端必须显式发送 `policySchemaVersion`；后端根据该版本拒绝未知字段/枚举并物化所有缺省值，完整 Policy Snapshot 与 Hash 一起写入 `excel_import_attempt`。客户端重试必须重用原 `expectedRevision + policySchemaVersion + policy`，不得改用当前 Batch 上的默认策略。

---

# 69. Preview API 分页

当数据规模扩展后，禁止一次返回全部 normalized product + issue。

建议：

```http
GET /api/v1/products/excel-ai-import/{batchId}/summary

GET /api/v1/products/excel-ai-import/{batchId}/normalized-products
?page=1
&size=50

GET /api/v1/products/excel-ai-import/{batchId}/issues
?page=1
&size=50
&severity=blocker

GET /api/v1/products/excel-ai-import/{batchId}/normalized-products/{groupId}
```

图片继续 lazy load。

---

# 70. Preview Edit API

```http
PATCH /api/v1/products/excel-ai-import/{batchId}/preview
```

```json
{
  "expectedRevision": 7,
  "edits": []
}
```

成功：

```json
{
  "previewRevision": 8
}
```

---

# 71. Issue Resolve API

```http
POST /api/v1/products/excel-ai-import/{batchId}/issues/{issueId}/resolve
```

```json
{
  "expectedRevision": 8,
  "action": "replace",
  "value": "MJ-S101-A"
}
```

---

# 72. Import 前校验

Import 必须：

```text
校验请求结构
↓
鉴权 + Batch 数据范围
↓
canonicalize 完整 Policy + 计算 policy_hash
↓
查找已有 Attempt
↓
done → 直接返回历史 result_summary
↓
pending/running/failed → 返回或恢复同一 Attempt
↓
无 Attempt → 检查 expected = current = confirmed revision
↓
加载 immutable snapshot + 检查 payload hash
↓
revalidate deterministic rules + 检查 blocker/warning policy
↓
同一事务 INSERT immutable attempt + attempt items
↓
persist by ProductAggregate transaction
```

详细分支以§50.0 为准。新执行的 Revision 冲突返回 `409 preview_revision_conflict`；已完成 Attempt 的重放不因当前 Preview 已演进而返回 409。

---

# 73. 数据库迁移治理

每次 schema 变化必须同步：

```text
database/schema/
database/ops/reset_db.sql
Flyway V migration
cross_domain_fk.sql
Entity
Mapper
API docs
architecture docs
当前进度.md
```

并运行：

```bash
make check-migration-sync
node scripts/check_entity_db_fields.js
```

---

# 74. Variant Unique Migration

本项目继续选择：

> **短维护窗口 + 普通 Flyway Migration**

不选择在本轮为 `CREATE INDEX CONCURRENTLY` 单独建立事务外 DDL 通道。

但 v3.3 修正一个关键点：

> `variant_fingerprint` 是新表，因此两个目标唯一索引在 Expand 阶段就创建；Cutover 阶段不再重复创建第三个 `uk_variant_fingerprint`。

最终索引名固定为：

```text
uk_variant_fingerprint_value
uk_variant_fingerprint_variant_version
```

## 74.1 Expand / Backfill（不中断写入）

在空的新表上直接创建：

```sql
CREATE UNIQUE INDEX uk_variant_fingerprint_value
ON variant_fingerprint(
    rspu_id,
    fingerprint_version,
    canonical_fingerprint
);

CREATE UNIQUE INDEX uk_variant_fingerprint_variant_version
ON variant_fingerprint(
    variant_id,
    fingerprint_version
);
```

然后：

```text
新增 Variant identity 字段
↓
上线兼容代码
↓
新建/命中 Variant 时双路维护 fingerprint
↓
回填存量 fingerprint
↓
反复执行冲突/缺失检查
↓
治理完成
```

此阶段继续保留：

```text
uk_variant_attrs
```

业务仍按旧 Identity Gate 放行写入；不得提前允许只有新 Identity 才能区分的 Variant。

## 74.2 Fingerprint 软删除语义

本轮明确选择：

> **Variant Identity 跨软删除保持连续。**

因此 Fingerprint 唯一索引**不增加**：

```sql
WHERE deleted_at IS NULL
```

语义：

```text
Fingerprint 命中已软删 Variant
↓
restore / reactivate 原 Variant
```

而不是：

```text
创建新的 variant_id
```

原因：

- 保持报价历史连续；
- 保持 Lineage 连续；
- 避免订单 / 推荐快照指向多个“实际上同一配置”的 Variant；
- Variant 是业务身份，不因软删除获得新身份。

如果未来产品明确要求“软删释放身份”，必须单独 ADR 修改该语义，不能只改索引条件。

## 74.3 Contract Cutover（短维护窗口）

维护窗口内：

1. 停止 Variant 和 Excel Import 相关写入；
2. 重跑：
   ```text
   fingerprint zero-missing
   fingerprint zero-conflict
   one-variant-one-fingerprint-per-version
   RSPU/Variant composite integrity
   ```
3. 确认两个 Fingerprint Unique 已经存在且有效；
4. **只删除旧**：
   ```text
   uk_variant_attrs
   ```
5. 提交 Migration；
6. 打开 fingerprint identity feature flag；
7. 恢复写入。

Cutover 不再：

```text
CREATE UNIQUE INDEX uk_variant_fingerprint
```

避免和 §15 的最终索引定义冲突。

## 74.4 回滚

Flyway 提交前失败：

```text
事务整体回滚
旧 uk_variant_attrs 仍在
```

提交后若应用需要回滚：

```text
先关闭 fingerprint identity feature flag
↓
确认尚未写入旧规则无法表达的新 Variant
↓
执行预演过的反向 Migration
```

不允许直接回退旧应用。

---

# 75. Supplier Identity Migration

实施：

```text
1. CREATE supplier_product_ref
2. BACKFILL safe rows
3. migrate all reads
4. remove global external_code identity dependency
5. stop new identity writes
6. drop global unique index
7. allow cross-factory duplicate source codes
```

每一步必须提供 rollback script / rollback strategy。

---

# 76. RSKU variant_id NOT NULL Migration

```text
scan NULL rsku
↓
generate legacy default variant
↓
backfill variant_id
↓
dry-run supplier configuration key
↓
有稳定 supplier grade/code 的按 CODE/TEXT 回填
无任何配置拆分证据的才回填 BASE
↓
dry-run TEXT→CODE 绑定与 configuration normalizer 升级
↓
只自动处理 stable / one-to-one，治理 converged / ambiguous
↓
govern duplicate variant + factory + configuration
↓
validate zero NULL
↓
set variant_id / supplier_configuration_key /
supplier_configuration_normalizer_version NOT NULL
```

回填不得将同一 Factory + Variant 下已存在的多个供应等级都压成 `BASE`，也不得把同一配置的 `TEXT` / `CODE` 表达回填成两条 RSKU。无法确定等级/配置对应关系的数据进入治理队列，在 zero ambiguous configuration 前不创建新 RSKU 唯一索引。

---

# 77. 测试方案

## 77.1 Supplier Identity

- Factory A / S001；
- Factory B / S001；
- 并发导入相同 Factory / S001；
- normalized code 等价输入；
- 不同 `source_system` 但相同 `source_namespace` 命中同一 Supplier Ref；
- Product Code Normalizer 升级 Dry Run 能发现新归一值冲突，冲突未治理前不得切换；
- 迁移后 `code_normalizer_version` 无空值、无旧版本残留；
- legacy fallback 歧义。

## 77.2 Variant Identity

- 同尺寸不同 module；
- 同模块不同 orientation；
- v1 → v2 fingerprint；
- v1 精确镜像 `COALESCE(code,text,'')`，原文空格/大小写不在回填时意外合并；
- Legacy 固定样例的 canonical bytes 必须得到 `c53a502fcb78f3ed780372b87c9bd18335ed04b20d355a27967e9c39469c1566`，且 code=`RED` / text=`RED` 的 v1 结果相同；
- `TEXT:389*164*72H` 治理为 `MM:3890x1640x720` 后新旧 Fingerprint 绑定同一 Variant；
- 原文治理后新 Fingerprint 与其他 Variant 冲突时进入 convergence 治理，不自动合并；
- 两批并发创建同 fingerprint；
- descriptive attribute 改动不产生新 Variant；
- identity attribute 改动产生新 Variant。

## 77.3 Preview Revision

- 同 revision 并发 edit；
- stale revision resolve；
- normalizer 升级后重放旧 revision；
- confirmed snapshot hash 校验；
- dictionary 修改后旧 snapshot 结果不改变。
- 一行展开多 Variant / Quote 时 `variantKey / quoteKey` 稳定且唯一；
- field target 的 `GROUP / VAR / QUOTE` owner 与 `product / variant / quote_blocking` 粒度一致，跨 Group 或不存在的 owner 被拒绝；
- `replace / ignore / waive` 生成新 Revision 并使活跃 Confirm 失效，`acknowledge` 不改 Revision；
- 不可 waiver 的完整性/鉴权/Hash/非法价格 Issue 被服务端拒绝。

## 77.4 Product Transaction

模拟：

```text
第 N 个 Variant 抛异常
第 N 个 Quote 抛异常
图片 metadata 失败
lineage 失败
async_task 创建失败
```

验证 Issue Scope 语义。

同时验证：

- A / AA / S 级布在无平台标准等级时共享 Variant 但生成三个 Supplier Configuration RSKU；
- `TEXT:AA级布` 后续获得 `CODE:FABRIC_AA` 时命中原 `rsku_id`，目标 CODE Key 被其他 RSKU 占用时产生 blocker；
- Supplier Configuration Normalizer Dry Run 能识别 stable / one-to-one / converged / ambiguous，冲突未治理前不切换；
- 同一 `targetRskuKey / quoteKey` 的完全等价重复 Quote 合并后只写一次但保留多 Lineage；`DUPLICATE_QUOTE_TARGET` 若返回只能是 `info / non_blocking`，即使 `allowWarnings=false` 也不因该提示阻断导入；
- 同一 `targetRskuKey` 下币种、单位、日期或价格不同时产生 `MULTIPLE_CURRENT_QUOTES_FOR_RSKU / blocker / quote_blocking`；特别覆盖相同 `quoteKey` 但价格不同的输入，确保 Key 去重不会吞掉冲突；
- 调换重复/冲突报价的源行顺序，合并结果或阻断结论不变，所有来源均可追溯；
- 某个 `quoteKey` 被 Issue Scope 跳过不影响同 Group 其他合法 Quote，但技术性数据库事务失败会回滚整个 Group 和 AttemptItem 终态。

## 77.5 Import Attempt

- 同 revision 连续双击 Import；
- 网络超时客户端重试；
- running attempt 再次提交；
- failed attempt retry；
- done attempt replay；
- done 后 Preview 已生成新 Revision，原请求重试仍返回原 `result_summary`；
- 相同 Revision + 不同 Policy 生成独立 Attempt，旧 Attempt Recovery 只读自身 `policy_payload`；
- Policy 稀疏请求的默认值物化后 Hash 稳定，未知枚举被拒绝；
- Quote Policy 覆盖日期更新/更旧/相同/单边 NULL/双边 NULL 与基础口径冲突，同日同值为 no-op 且不增长 Version / History；
- 空值不清空旧值；§34.1 活跃人工修正标记默认保留，Lineage GC / 软删除不使其失效，未带显式人工修正事实的 Import 不能覆盖；
- 数据库负向约束：`variant / quote / field` 的 `target_key=NULL` 必须失败；`status=done + outcome=NULL` 和非 done 携带 outcome 必须失败；
- 存在 business rejected Group 但全部 Item 已终态时 Attempt 为 `done + partial_success/business_rejected`；
- 全部 skipped 为 `done + success`，至少一个 rejected 且零实体写入才是 `business_rejected`，三种 outcome 汇总条件互斥；
- price history 不重复；
- lineage 不重复；
- async task 不重复。

## 77.6 Object Storage

- upload 后 DB rollback；
- DB commit 后 finalize worker crash；
- finalize 重复执行；
- orphan GC；
- temporary image 不被客户端读取。

## 77.7 Cost Security

- 普通销售角色读取 batch；
- Factory A 读取 Factory B batch；
- 无 cost permission 请求 lineage；
- log 中不得出现明文 factory price；
- raw JSON 不暴露价格；
- `active_rsku_count=1` 和 `min_factory_price=max_factory_price` 在无 exact 权限时不返回数值；
- HMAC Key 正常旋转不修改历史 Snapshot Hash Material，紧急旋转生成新 Revision；
- backup 数据抽查。

## 77.8 Agent

- 4 米左右；
- 真皮；
- 带贵妃；
- 预算；
- PricingService 销售价；
- deterministic rank；
- stable tie-break；
- LLM 不可新增候选；
- evidenceRefs 越权字段拒绝。

---

# 78. 性能测试

当前项目仍有行数限制时：

```text
先验证当前支持规模
```

解除限制之后再增加：

```text
1 万行
多 Sheet
大量图片
高并发 Preview
大批量 Lineage
```

Lineage 压测最低档仍为：

```text
单批 100 万行明细
```

但 v3.3 不预设 16 个 Hash Partition。

性能测试先回答：

```text
普通表 + B-Tree 索引
是否满足目标？
```

必须记录：

```text
heap baseline
parse latency
preview latency
DB insert throughput
lineage bulk insert throughput
lineage batch lookup p95
lineage entity lookup p95
GC batch latency
WAL growth
table/index size
autovacuum lag
storage throughput
```

若普通表达不到 SLO，再依据 §41.1 进入单独的 Partition ADR。

只测试 Excel 原始行数而不测试：

```text
展开后的 Variant / Quote / Lineage 数量
```

不算通过。

---

# 79. 推荐后端拆分

现有大型 Excel Service 建议演进：

```text
ExcelImportFacade
├─ RawExcelParser
├─ HeaderResolver
├─ EmbeddedImageExtractor
├─ ImportNormalizer
├─ ProductGroupingService
├─ SupplierProductIdentityResolver
├─ VariantIdentityResolver
├─ PreviewSnapshotService
├─ ImportIssueService
├─ ImportAttemptService
├─ ProductAggregatePersister
├─ ImportLineageService
├─ ImportSecurityService
└─ ImportStorageCoordinator
```

---

# 80. 推荐目录

```text
server/src/main/java/com/rsdp/importing
├── application
│   ├── ExcelImportFacade.java
│   ├── PreviewApplicationService.java
│   └── ImportApplicationService.java
├── domain
│   ├── NormalizedProductAggregate.java
│   ├── ProductIdentity.java
│   ├── NormalizedRspu.java
│   ├── NormalizedVariant.java
│   ├── NormalizedSupplyQuote.java
│   └── ImportIssue.java
├── normalize
│   ├── ProductCodeNormalizer.java
│   ├── DimensionNormalizer.java
│   ├── MaterialNormalizer.java
│   ├── PriceNormalizer.java
│   └── LeadTimeNormalizer.java
├── identity
│   ├── SupplierProductIdentityResolver.java
│   └── VariantIdentityResolver.java
├── preview
│   ├── PreviewSnapshotService.java
│   └── ImportIssueService.java
├── persistence
│   ├── ProductAggregatePersister.java
│   ├── ImportLineageRepository.java
│   └── ImportAttemptRepository.java
└── storage
    └── ImportStorageCoordinator.java
```

---

# 81. 实施优先级

## P0-A：ID 与 Supplier Identity

- 所有新 DDL 对齐 VARCHAR ID；
- supplier_product_ref；
- source code normalizer + `code_normalizer_version` + 升级 Dry Run / 冲突治理；
- external_code 安全迁移；
- legacy governance queue。

## P0-B：Immutable Preview

- product group 仅保存 immutable input；
- normalized payload；
- revision + 全链路组合 FK；
- payload / snapshot hash version；
- Sensitive Value 加密 + HMAC；
- issue；
- confirm；
- 先完成 B1 Snapshot / Revision 底座，再在 B2 落实 09 方案 per-sheet SINGLE / MIXED；
- `rowCategorySelections` 作为带 `expectedRevision` 的编辑命令物化进 Snapshot。

## P0-C：Variant Identity

- structured dimensions；
- identity/descriptive attributes；
- variant_fingerprint；
- Java 判重迁移；
- 旧唯一键替换。

## P0-D：RSKU

- variant_id not null；
- status；
- quote semantics；
- supplier material grade + `supplier_configuration_key`；
- Supplier Configuration Resolver，TEXT→CODE 绑定原 RSKU，Normalizer 升级 Dry Run / 受控 Key Migration；
- `targetRskuKey` 聚合与多 current quote 冲突校验；
- RSPU / Variant / RSKU `manual_override_fields` 权威人工修正标记；
- version；
- `(variant_id,factory_code,supplier_configuration_key)` concurrency upsert。

## P0-E：Lineage & Security

- field-level lineage；
- sensitive field encryption；
- retention；
- access control；
- idempotency key；
- 单批 100 万行容量基线与 `import_lineage_gc`；
- 先验证普通表 + B-Tree，达到阈值后再通过 ADR 决定是否分区。

## P0-F：Product Aggregate / Import Attempt

- 前置（显式依赖）：P0-B Issue Target Protocol、P0-C Fingerprint、P0-D Supplier Configuration Resolver 与 `targetRskuKey` 聚合；本阶段只在其已完成产物上收口执行链路；
- Issue Target Protocol 与 Scope 过滤；
- `ProductAggregatePersister` 及 Group 级数据库事务；
- attempt；
- attempt item；
- immutable policy payload / schema version / hash version；
- lease / heartbeat；
- ProductAggregate 写入 + AttemptItem 终态同事务；
- price_history / lineage / async_task / finalize DB 幂等键；
- replay protection；
- retry / recovery semantics。

## P1-A：Storage Recovery Hardening

- storage state；
- finalize task；
- orphan GC。

## P1-B：Agent

- PricingService；
- VariantSelectionService；
- SupplyQuoteService；
- DeterministicRankingService；
- RecommendNode reason-only。

## P1-C：跨供应商商品实体解析（独立方案）

本文件只保留扩展点，不在本方案中实现完整匹配算法。

后续独立设计：

```text
Supplier Product
↓
SupplierProductMatchingService
↓
RSPU Candidate Recall
↓
Structured / Image / Model Signals
↓
STRONG_MATCH / REVIEW_REQUIRED / NO_MATCH
↓
人工确认或强确定性自动绑定
↓
supplier_product_ref → rspu_id
```

边界原则：

- 图片向量只用于候选召回，不得仅凭相似度自动 Merge RSPU；
- 名称相似、尺寸相近也不能单独作为自动归并依据；
- 只有品牌 + 原厂型号等强确定性身份信号才允许进入自动绑定规则；
- 已存在的 `supplier_product_ref` 不允许静默改绑到另一个 RSPU；
- 多个 Supplier Ref 可以合法指向同一个 RSPU；
- 详细匹配特征、评分、人工复核和 Merge 治理另建《供应商商品实体解析与跨源归并方案.md》。

## P2

- variant_component；
- quote snapshot；
- dynamic configuration；
- advanced multi-supplier comparison。

---

# 82. 验收标准

### Supplier Identity

```text
Factory A / S001
Factory B / S001
```

可以同时存在。

同一 Factory / Namespace / Source Code 从 Excel 与 ERP 两个 `source_system` 进入时仍命中同一 Supplier Ref；Normalizer 升级后能通过 `code_normalizer_version` 追溯历史归一规则，且切换前不存在未解决的新归一冲突。

### Preview

用户确认 Revision 7 后，即使 Normalizer / Dict 后续变化：

```text
最终 Import 结果
=
Revision 7 Snapshot
```

对任一 Sheet，Snapshot 同时包含当时确认的 SINGLE / MIXED 策略、候选/默认品类和每行 `finalCategoryCode`；切换其他 Sheet、后续改候选集或人工改品类都不会原地篡改 Revision 7。

### Variant

```text
组合A / 4000 / 真皮
组合B / 4000 / 真皮
```

绝不误判为同一 Variant。

### Fingerprint Upgrade

v1 / v2 指向：

```text
同一个 variant_id
```

而不是复制业务实体。

存量只有 `size/color/material_text` 的 Variant 可生成与旧唯一索引等价的 v1 Fingerprint；原文后续标准化时追加新版 Fingerprint 到原 `variant_id`，不因码/原文表达变化创建重复 Variant。

### RSKU

每个有效 RSKU：

```text
必须有 variant_id
factory_code
supplier_configuration_key
current quote
version
```

同一 Factory + Variant 下 A / AA / S 级布在无可靠平台等级时可以作为三个 Supplier Configuration RSKU 同时存在，不覆盖价格，也不伪造三个 Variant。

同一配置从 `TEXT` 获得稳定 `CODE` 或 Normalizer 升级后仍指向原 `rsku_id`；任何收敛/歧义在写入前可见并阻断。同一 `targetRskuKey` 在一个 Snapshot 内最多只有一份非等价 current quote，结果不受遍历顺序影响。

### Issue Target

一个 ProductGroup 内的每个 Variant / Quote 都有稳定唯一键；`variant_blocking / quote_blocking` 仅影响指定 `variantKey / quoteKey`。任何改变导入行为的 resolve 都生成新 Revision，不可豁免约束始终无法通过 API 放行。

### Lineage

可以：

```text
Entity.Field
→ Batch
→ Row
→ Column
```

成本字段不能明文泄露。

单批 100 万 lineage 压测通过；到期且无 hold 的批次能由 GC 分批清理，清理过程不产生长事务，且可幂等续跑。

### Import Idempotency

同一个：

```text
batch + revision + policy
```

重复请求：

```text
不会重复产生 history / lineage / tasks
```

并且：

```text
Immutable ProductGroup
≠
Attempt Runtime
```

不同 `policy_hash` 的 Attempt 不会共同改写 Snapshot。

每个 Attempt 都能仅依赖自身 `policy_schema_version + policy_payload` 还原 update/conflict/quote/null/manual-correction 行为，不读取 Batch 当前 Policy。已 `done` Attempt 在当前 Preview 变更后重放仍返回存储的 `outcome + result_summary`。

`replace_if_newer` 对 quote date 的有值/空值组合和同日同值 no-op 有唯一结果；`manualCorrectionPolicy` 仅读当前实体的 `manual_override_fields`，不依赖已到期 Lineage 或 Audit Log 重放。

模拟：

```text
ProductGroup 事务 COMMIT
↓
JVM 立即崩溃
```

Recovery 后必须满足：

```text
AttemptItem 识别已完成 Group
quote_version 不重复增长
price_history 不重复
lineage 不重复
async_task 不重复
storage finalize 不重复
```

### Hash / Sensitive Integrity

必须验证：

```text
SnapshotHashMaterial 不包含 snapshot_hash 自身
历史 Hash 可按 hash_version 重算
相同敏感价格重新加密后 payload_hash 稳定
正常 HMAC Key 旋转不覆盖历史 value_hmac / hmac_key_version
紧急 HMAC 旋转生成新 Revision，旧 Revision 仍可重算
数据库泄漏场景下无法通过普通金额 SHA 字典直接反查 value_hmac
```

Snapshot 保护还必须验证：应用数据库账号无法直接 UPDATE / DELETE Revision 或 ProductGroup；受限 GC 能在满足 retention / hold / Attempt 条件时原子归档指针并删除详细快照，过期快照的重放请求返回 `410 snapshot_expired`。

### Agent

模型：

```text
不能决定商品事实
不能决定最终 rank
不能暴露 factory price
```

只生成解释。

---

# 83. 明确暂不实施

本轮不建议同时：

- 重写整个 Agent Graph；
- 新增另一套 Agent REST API；
- 重写 pgvector；
- 引入大型通用 Event Bus；
- 引入复杂可搜索加密；
- 一次性实现完整动态家具配置器；
- 一次性取消全部 legacy 字段；
- 立刻支持贸易商 / 经销商统一 SourceParty。

---

# 84. 最终目标

最终供应链数据底座应达到：

```text
Raw Supplier Data
↓
Versioned Normalization
↓
Immutable Preview
↓
Traceable Review
↓
Idempotent Import
↓
Supplier Source Identity
↓
RSPU
↓
Variant Identity
↓
RSKU Supply Quote
↓
Secure Lineage
↓
AI / Vector
↓
Pricing / Query / Ranking
↓
Marketing Agent
```

核心原则：

> **平台商品身份与供应商编码分离。**

> **用户确认的是不可变 Snapshot，而不是一份未来会重新解释的 Excel。**

> **Variant 是领域实体，Fingerprint 是身份解析索引。**

> **采购成本是敏感数据，不能在 Raw、Preview、Lineage 中重新明文落地。**

> **导入必须具备 Revision、Attempt 和字段级 Lineage。**

> **Agent 只能消费业务服务给出的确定性事实与排序结果。**

---

# 85. 文档状态升级条件

v3.3 完成最后一轮阻塞项的设计收口：

1. Immutable ProductGroup 与 Attempt Runtime 完全拆分，引入 `excel_import_attempt_item`；
2. Batch-level Issue、Sensitive Value、Import Attempt、Batch Revision Pointer 全部补齐 `(batch_id, preview_revision)` 数据库级完整性；
3. Fingerprint 两个目标唯一索引统一在 Expand 阶段创建，Cutover 只删除旧 `uk_variant_attrs`；
4. Variant Fingerprint 明确采用“软删除不释放身份、重新出现恢复原 Variant”的领域语义；
5. Attempt Recovery 以“ProductGroup 领域事务 + AttemptItem Finalize 同事务”为核心 Checkpoint；
6. `price_history`、`async_task`、Lineage、Storage Finalize 都有明确数据库幂等落点；
7. `SnapshotHashMaterial` 明确排除 Hash 自身和 Runtime 字段，消除自引用；
8. Hash 版本真正持久化到 Preview / ProductGroup / Attempt；
9. 低熵敏感成本从普通 SHA-256 改为 HMAC-SHA-256，并保存 HMAC / Encryption Key Version；
10. Snapshot 与 Runtime、普通 Hash 与敏感 HMAC 的职责边界已经明确；
11. Supplier Identity 唯一键统一使用 `source_namespace`，并持久化 `code_normalizer_version` 及升级冲突治理流程；
12. HMAC 旋转改为「历史 Snapshot 摘要固定、旧 Key 仅验证保留、紧急更换生成新 Revision」，不再就地改写 Hash Material；
13. `cost_price_summary` 对单 RSKU 或 `min=max` 按 `cost_price_exact` 权限降级，防止汇总投影反推精确成本；
14. Immutable Snapshot 以数据库 DML 权限 + 受限 `SECURITY DEFINER` GC 函数保护，并明确指针归档和已 Confirm / Import Revision 的延长保留规则；
15. RSKU Identity 扩展为 `Variant + Factory + Supplier Configuration`，横向供应商等级在无平台标准等级时仍可稳定生成多个供应单元；
16. Attempt 持久化完整不可变 Policy Snapshot，明确枚举、默认值、空值、报价覆盖和人工修正规则；
17. Snapshot 引入稳定 `variantKey / quoteKey`，Issue Scope 使用目标键而不是源单元格坐标决定阻断对象；
18. Import API 改为 Replay-first，done 结果不受当前 Preview 演进影响，Attempt 技术状态与业务 outcome 分离；
19. Fingerprint v1 无类型标记地镜像现有 `COALESCE(code,text,'')` 语义，v2+ 使用 `CODE / TEXT / MM / ABSENT` 协议，字典治理通过新版 Fingerprint 绑定原 Variant；
20. ProductAggregate 事务、Issue Scope 和 AttemptItem 终态同事务纳入 P0-F，门禁拆为设计批准和上线验收两阶段。
21. Supplier Configuration Key 是稳定身份，TEXT→CODE 和 Normalizer 升级必须绑定原 RSKU 或进入冲突治理；
22. Quote 在写入前按 `targetRskuKey` 聚合，P0 对非等价的多 current quote 明确阻断；
23. Issue Target 和 Attempt Outcome CHECK 对 NULL 显式拒绝，不依赖 PostgreSQL CHECK 的 UNKNOWN 语义；
24. Quote Policy 空日期/同日语义和人工修正权威元数据已固定，不依赖 Lineage 保留期。

当前状态为（2026-09-22 升级）：

```text
Design Approved
```

A 类设计批准门禁六项已于 2026-09-22 全部通过，证据与关闭条件执行记录见 `docs/05-status/A类门禁/`（01-跨文档契约评审、02-DDL与状态机评审、03-只读数据画像、04-规范化协议PoC、05-安全设计评审、06-P0依赖评审、00-关闭条件执行记录、07-全量回归报告）。门禁严格分为两阶段：

## 85.1 A 类：Design Approval Gate

目标是证明设计自洽、能够进入 P0 实现。允许使用只读数据扫描、临时 Schema 和隔离 PoC，但不允许修改生产 Schema / 数据。

必须关闭：

1. **Cross-document Contract Review**
   v3.3、09方案、ADR 007/008、API 契约和待办对 Identity、Revision、Issue、Policy、Replay 和分期无矛盾。

2. **DDL / State Machine Review**
   在真实 Schema 上静态评审组合 FK、候选键、软删、索引、Supplier Configuration、`targetRskuKey`、Issue Target、Attempt Policy/Outcome 和迁移顺序；实测 CHECK 的 NULL 负例。

3. **Read-only Data Profiling**
   对 Supplier Ref、Normalizer 升级、Fingerprint v1/v2、`variant_id IS NULL`、RSPU/Variant 错配、Supplier Configuration TEXT/CODE 收敛与回填歧义、重复 RSKU 及多 Quote 竞争数量输出治理分类；此阶段要求「问题可枚举且有治理方案」，不要求已写回生产数据。

4. **Canonical Protocol PoC**
   隔离验证 Snapshot/Payload/Policy Hash、Sensitive HMAC、`variantKey / quoteKey`、Legacy Fingerprint 固定样例和不可变 Policy 序列化，相同输入在重启/节点间输出一致。

5. **Security Design Review**
   `cost_price_exact / cost_price_summary` 权限矩阵、AES/HMAC Key 生命周期、Snapshot DML 权限、`SECURITY DEFINER` 安全、日志脱敏和数据库泄漏威胁模型通过。

6. **P0 Dependency Review**
   P0-F 已显式依赖 P0-B Issue/Target、P0-C Fingerprint 和 P0-D RSKU Configuration；ProductAggregate + AttemptItem 同事务不再留到 P1。09 方案已改为 ProductGroup 容错语义。

A 类全部通过后：

```text
Architecture Review Draft
↓
Design Approved
↓
开始 P0 实现、Flyway 编写和测试
```

## 85.2 B 类：Release / Production Migration Gate

目标是证明已实现系统可上线。这些条件依赖代码、Migration 和故障注入，不再作为「开始开发前」的逻辑前置。

1. **Migration Governance / Rehearsal**
   `database/schema/`、`reset_db.sql`、Flyway、cross-domain FK、Entity、Mapper、API 文档和当前进度同步；在生产克隆数据上完成前向/回滚预演并通过仓库检查。

2. **Backfill Acceptance**
   Supplier Identity、Fingerprint、RSKU Variant/Configuration 达到 `zero missing + zero unresolved conflict + zero ambiguous configuration`，Supplier Configuration TEXT/CODE 和 Normalizer 切换无未治理收敛，并验证软删恢复与新旧 Fingerprint 共存语义。

3. **Recovery / Idempotency Fault Injection**
   至少覆盖：
   ```text
   commit 前 crash
   commit 后 crash
   attempt_item 更新边界
   lease expiry takeover
   duplicate HTTP import
   duplicate async dispatch
   duplicate finalize
   ```
   另覆盖 done Replay 时当前 Preview 已变更、业务 rejected Group 汇总为 done outcome、Policy 默认值及枚举升级，并证明无重复业务副作用。

4. **Storage / Async Recovery**
   MinIO finalize、orphan GC、async_task timeout/retry/dead 场景通过。

5. **Performance / GC Acceptance**
   Preview、Import、单批 100 万 Lineage 写入/回溯/分批 GC、WAL 和 autovacuum 指标达标；只有证据触发§41.1 时才新建分区 ADR。

6. **Security / Permission Acceptance**
   数据库 Runtime Role 无法更改 Immutable Snapshot，Sensitive Value、HMAC Rotation、cost summary 防反推、日志/导出/Agent 脱敏和跨 Factory 数据范围测试通过。

7. **09 / Import E2E Acceptance**
   SINGLE / MIXED、ProductGroup 容错、`variantKey / quoteKey` 精准阻断、Supplier Grade 横向 Unpivot、`targetRskuKey` 多 Quote 冲突、TEXT→CODE 绑定原 RSKU、Policy 日期/NULL/人工修正覆盖和响应丢失重放通过。

B 类全部通过后：

```text
Design Approved
↓
P0 Implemented
↓
Release Ready
↓
允许生产 Migration / Cutover
```

进入 `Design Approved` 后停止继续扩大本文件架构范围。

跨供应商同款识别继续保持：

```text
P1-C 独立设计
```

任何新的领域能力必须另开 ADR / 设计文档，而不是继续向本文件堆叠。

---

# 修订记录

- 2026-09-22（v3.3 增补，不改设计语义）：落实 A 类门禁关闭条件——§81 P0-F 补显式前置依赖声明（06-G1）；§文档边界补「Fingerprint v1 协议与 Supplier Configuration Resolver 算法随 P0-B 先行实现」声明（06-G2）；§34 补 `ck_rsku_status` 显式拒绝 NULL 的 CHECK（02-D1）；§24 补 Issue Target 双 CHECK 成对存在注释（02-D2）；§43 补公开哈希/可枚举派生信息禁令、筛选/排序预言机规则、§43.1 成本权限主体矩阵、§43.2 Key Custody（05-R06 条文/R-01/R-02/R-03）；§17.1 澄清 `LEGACY_BYTES:` 为注释性写法，§47.1.1 明确字典序排序键 / 禁止 float 直传 / Null 保留策略，§47.1.3 明确 groups 按 productGroupKey 排序，§24.1 明确 quoteKey `effectiveFrom` null 语义（04 报告 6 项设计歧义澄清）。

---
