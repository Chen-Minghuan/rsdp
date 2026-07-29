# ADR 007：RSPU 与 RSKU 业务编码落地实施方案

> 状态：草案（待评审）  
> 影响范围：数据库、后端 Service、前端展示、Excel 导入、存量数据迁移  
> 相关文档：
> - `docs/06-reference/02-双层编码体系.md`
> - `docs/04-decisions/004-为什么RSPU和RSKU使用UUID主键而非业务编码作为主键.md`

---

## 一、背景与目标

### 1.1 当前问题

当前 RSDP 系统中，RSPU 与 RSKU 的主键已经按 ADR 004 的决策使用 UUID（如 `RSPU-XXXX`、`RSKU-XXXX`），但**业务编码体系仅停留在设计文档层面**，代码实现存在以下问题：

1. **RSPU 业务编码生成器已写但未被调用**：`RspuCodeService.generateNextCode()` 已经实现，但所有创建 RSPU 的入口（图片录入、工厂录入、PDF 导入、Excel 导入、Excel AI 导入）都没有调用它。
2. **数据库和实体中没有业务编码字段**：`rspu_master` / `rsku_supply` 表以及 `RspuMaster` / `RskuSupply` 实体均缺少 `rspu_code` / `rsku_code` 字段，导致业务编码无处存储。
3. **RSKU 业务编码完全没有实现**：没有生成服务、没有计数器表、没有字段。
4. **各创建入口字段填充不统一**：AI 识别入口创建的是 `status=processing` 的草稿；工厂录入和 Excel 导入创建的是 `status=active` 的完整记录；部分字段是否必填在不同入口不一致。
5. **存量数据没有业务编码**：已有的 RSPU/RSKU 全部没有业务编码，未来补码需要谨慎处理。

### 1.2 目标

通过本次改造，实现：

1. **业务编码作为独立展示字段正式落地**：`rspu_code` / `rsku_code` 与 UUID 主键并存，业务编码用于展示、报表、导出、人工沟通，UUID 主键继续作为系统内部关联主键。
2. **统一所有 RSPU/RSKU 创建入口的编码生成规则**：无论通过哪个入口创建，业务编码都按同一规则生成。
3. **明确业务编码的不可变性**：业务编码一旦生成，不因后续字段修改而改变；若业务属性确实需要变更编码，应通过“停用旧款 + 新建新款”处理。
4. **存量数据平滑迁移**：为已有 RSPU/RSKU 补全业务编码，并初始化计数器，确保新旧数据编码不冲突。
5. **前端与下游系统同步**：产品列表、详情、订单、方案、报价、Excel 模板等位置统一展示业务编码。

---

## 二、核心设计原则

### 2.1 主键与业务编码分离（继承 ADR 004）

| 字段 | 作用 | 是否可变 | 示例 |
|------|------|----------|------|
| `rspu_id` / `rsku_id` | 系统内部主键、外键关联 | 不可变 | `RSPU-550E-...` |
| `rspu_code` / `rsku_code` | 业务展示编码、人工可读 | **生成后不可变** | `FS-MC-001-M` |

### 2.2 业务编码生成时机

- **RSPU**：在 RSPU 记录首次持久化之后、关联数据（图片、变体、RSKU）创建之前生成并写入。
- **RSKU**：在 RSKU 记录持久化之前生成并写入；RSKU 编码依赖所属 RSPU 的 `rspu_code`，因此 RSPU 必须先有编码。

### 2.3 业务编码不可变更

- 数据库层：`rspu_code`、`rsku_code` 字段一旦写入，禁止 UPDATE。
- 应用层：所有更新 RSPU/RSKU 的 Service 方法不得修改 `rspu_code` / `rsku_code`。
- 业务层：如果产品品类、风格、尺寸、工厂、材质发生本质变化，应新建 RSPU/RSKU，而不是修改已有编码。

### 2.4 并发安全

- RSPU 流水号通过 PostgreSQL `INSERT ... ON CONFLICT DO UPDATE` 原子递增（已有 `rspu_code_counter`）。
- RSKU 流水号新增 `rsku_code_counter`，同样使用 `ON CONFLICT DO UPDATE`。
- 编码生成与写入应在同一事务内完成，或通过数据库唯一约束兜底。

---

## 三、编码规则详细定义

### 3.1 RSPU 业务编码

格式：

```text
{category_code}-{style_code}-{sequence}-{size_code}
```

示例：

```text
FS-MC-001-M
```

| 段位 | 含义 | 长度 | 说明 |
|------|------|------|------|
| 1 | 品类码 | 2 位 | 如 `FS`=座椅，`FT`=桌几，`FC`=柜类，`HD`=软装，`OF`=办公，`BS`=吧椅 |
| 2 | 风格/职级码 | 2 位 | 家装用风格码（`MC`=中古风），办公用职级码（`EX`=总裁级） |
| 3 | 流水号 | 3 位 | 同一 `category_code + style_code` 组合下从 001 递增 |
| 4 | 尺寸档 | 1 位 | `S`=小，`M`=中，`L`=大，`X`=特大；未识别时用 `X` |

生成约束：

- `category_code` 必须存在于 `category_dict`（dict_type='category'）。
- `style_code` 必须存在于 `category_dict`（dict_type='style' 或 'grade'）。
- `size_code` 必须存在于 `category_dict`（dict_type='size'），未提供时默认 `X`。
- 流水号最大为 999，超过时抛出异常，提示扩容。

### 3.2 RSKU 业务编码

本方案推荐采用**带变体流水号**的规则，以解决“同一 RSPU + 同一工厂 + 同一材质可能存在多个变体报价”的问题：

格式：

```text
{rspu_code}-{factory_code}-{material_code}-{variant_seq}
```

示例：

```text
FS-MC-001-M-A004-PE-001
```

| 段位 | 含义 | 长度 | 说明 |
|------|------|------|------|
| 1-4 | RSPU 业务编码 | 变长 | 继承 RSPU 编码 |
| 5 | 工厂代码 | 3~4 位 | 如 `A004`、`S001` |
| 6 | 材质码 | 2 位 | 如 `PE`=PE 仿藤，`LE`=皮革 |
| 7 | 材质版本流水号 | 3 位 | 同一 `rspu_code + factory_code + material_code` 下从 001 递增 |

> **替代方案说明**：如果业务上确定“同一 RSPU + 同一工厂 + 同一材质”只会对应一个 RSKU，可以去掉第 7 段，采用 `{rspu_code}-{factory_code}-{material_code}`。但建议保留第 7 段，以兼容未来变体扩展。

生成约束：

- `factory_code` 必须存在于 `factory_master`。
- `material_code` 必须存在于 `category_dict`（dict_type='material'）。
- 同一 RSPU 下，同一工厂、同一材质的 RSKU 通过 `variant_seq` 区分。

### 3.3 变体编码（已存在，保持现状）

变体编码继续使用当前规则，无需改造：

```text
{rspu_id}-V{sequence}
```

示例：`RSPU-550E-...-V001`。

---

## 四、数据库改造

### 4.1 新增 RSPU/RSKU 业务编码字段

修改 `database/V1__init_db.sql` 和 `database/reset_db.sql`：

```sql
-- RSPU 主表增加业务编码
ALTER TABLE rspu_master
    ADD COLUMN IF NOT EXISTS rspu_code VARCHAR(32) UNIQUE;

CREATE INDEX IF NOT EXISTS idx_rspu_code ON rspu_master(rspu_code);

-- RSKU 供应表增加业务编码
ALTER TABLE rsku_supply
    ADD COLUMN IF NOT EXISTS rsku_code VARCHAR(64) UNIQUE;

CREATE INDEX IF NOT EXISTS idx_rsku_code ON rsku_supply(rsku_code);
```

### 4.2 新增 RSKU 编码计数器表

```sql
CREATE TABLE IF NOT EXISTS rsku_code_counter (
    rspu_code VARCHAR(32) NOT NULL,
    factory_code VARCHAR(16) NOT NULL,
    material_code VARCHAR(16) NOT NULL,
    sequence_value BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (rspu_code, factory_code, material_code)
);
```

### 4.3 新增数据库迁移脚本

新增 `database/V19__business_code.sql`（版本号根据当前最新迁移递增）：

```sql
-- V19: RSPU/RSKU 业务编码字段与计数器
ALTER TABLE rspu_master ADD COLUMN IF NOT EXISTS rspu_code VARCHAR(32) UNIQUE;
ALTER TABLE rsku_supply ADD COLUMN IF NOT EXISTS rsku_code VARCHAR(64) UNIQUE;

CREATE INDEX IF NOT EXISTS idx_rspu_code ON rspu_master(rspu_code);
CREATE INDEX IF NOT EXISTS idx_rsku_code ON rsku_supply(rsku_code);

CREATE TABLE IF NOT EXISTS rsku_code_counter (
    rspu_code VARCHAR(32) NOT NULL,
    factory_code VARCHAR(16) NOT NULL,
    material_code VARCHAR(16) NOT NULL,
    sequence_value BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (rspu_code, factory_code, material_code)
);
```

---

## 五、后端实体与 Mapper 改造

### 5.1 实体加字段

`server/src/main/java/com/rsdp/entity/RspuMaster.java`：

```java
private String rspuCode;
```

`server/src/main/java/com/rsdp/entity/RskuSupply.java`：

```java
private String rskuCode;
```

### 5.2 新增 RSKU 编码 Mapper

`server/src/main/java/com/rsdp/mapper/RskuCodeMapper.java`：

```java
@Mapper
public interface RskuCodeMapper {
    Long allocateSequence(@Param("rspuCode") String rspuCode,
                          @Param("factoryCode") String factoryCode,
                          @Param("materialCode") String materialCode);
}
```

`server/src/main/resources/mapper/RskuCodeMapper.xml`：

```xml
<select id="allocateSequence" resultType="java.lang.Long">
    INSERT INTO rsku_code_counter (rspu_code, factory_code, material_code, sequence_value, updated_at)
    VALUES (#{rspuCode}, #{factoryCode}, #{materialCode}, 1, CURRENT_TIMESTAMP)
    ON CONFLICT (rspu_code, factory_code, material_code)
    DO UPDATE SET sequence_value = rsku_code_counter.sequence_value + 1,
                  updated_at = CURRENT_TIMESTAMP
    RETURNING sequence_value
</select>
```

### 5.3 完善 RSPU 编码服务

`RspuCodeService` 需要增加“写入 rspu_code”的封装方法：

```java
/**
 * 为指定 RSPU 生成并写入业务编码。
 *
 * @param rspuId       RSPU ID
 * @param categoryCode 品类码
 * @param styleCode    风格/职级码
 * @param sizeCode     尺寸码（为空时默认 X）
 * @return 生成的业务编码
 */
@Transactional
public String assignCode(String rspuId, String categoryCode, String styleCode, String sizeCode) {
    // 1. 查询是否已有编码，有则直接返回
    // 2. 生成新编码
    // 3. UPDATE rspu_master SET rspu_code = ? WHERE rspu_id = ?
    // 4. 返回编码
}
```

### 5.4 新增 RSKU 编码服务

`server/src/main/java/com/rsdp/service/RskuCodeService.java`：

```java
@Service
@RequiredArgsConstructor
public class RskuCodeService {

    private final RskuCodeMapper rskuCodeMapper;

    @Transactional
    public String assignCode(String rskuId, String rspuCode, String factoryCode, String materialCode) {
        // 1. 查询是否已有编码，有则直接返回
        // 2. 从 rsku_code_counter 取流水号
        // 3. 拼接编码
        // 4. UPDATE rsku_supply SET rsku_code = ? WHERE rsku_id = ?
        // 5. 返回编码
    }
}
```

---

## 六、改造所有 RSPU 创建入口

### 6.1 入口清单与改造点

| 入口 | Service 方法 | 编码生成时机 | 特殊说明 |
|------|--------------|--------------|----------|
| 图片新品录入 | `ProductService.createEntry()` | AI 识别成功后 | 尺寸档可能需等 AI 识别结果 |
| PDF/PPT 导入 | `ProductService.createEntryFromStream()` | AI 识别成功后 | 同上 |
| 工厂单条录入 | `ProductService.createFactoryEntry()` | 创建 RSPU 时 | 用户表单传入 sizeCode |
| 普通 Excel 导入 | `ProductImportService.createRspu()` | 创建 RSPU 时 | Excel 中应包含 sizeCode |
| Excel AI 导入 | `ExcelAiImportService.createRspu()` | 创建 RSPU 时 | 若 sizeCode 缺失，先默认 X，后续 AI 识别不覆盖编码 |

### 6.2 图片新品录入改造

`ProductService.createEntry()` 当前流程：

```text
创建 RSPU 草稿 → 保存图片 → 创建异步任务 → 事务提交 → AI 异步识别
```

改造后：

```text
创建 RSPU 草稿 → 保存图片 → 创建异步任务 → 事务提交
    ↓
AI 异步识别完成
    ↓
AsyncTaskProcessor.processProductEntry()
    ↓
AiRecognitionPersistenceService.saveSuccess()
    ↓
更新 RSPU 字段（含尺寸推断）
    ↓
调用 RspuCodeService.assignCode() 生成并写入 rspu_code
    ↓
创建默认变体
```

关键逻辑：

- AI 识别后，根据 `labels.getOcr().getDimensions()` 或六维标签推断 `sizeCode`。
- 如果仍无法推断，使用 `X`。
- 调用 `RspuCodeService.assignCode(rspuId, categoryCode, styleCode, sizeCode)`。

### 6.3 工厂单条录入改造

`ProductService.createFactoryEntry()` 当前在创建 RSPU 后直接创建变体和 RSKU。

改造后：

```text
创建 RSPU（含用户传入的 categoryCode, positioningLabel, sizeCode）
    ↓
调用 RspuCodeService.assignCode() 写入 rspu_code
    ↓
创建默认变体
    ↓
创建第一条 RSKU（调用 RskuCodeService.assignCode()）
```

工厂录入表单需要增加 `sizeCode` 字段（如果还没有）。

### 6.4 Excel 导入改造

`ProductImportService.createRspu()` 和 `ExcelAiImportService.createRspu()`：

```text
生成 RSPU UUID
填充字段
调用 RspuCodeService.assignCode() 写入 rspu_code
保存 RSPU
```

Excel 模板中应增加 `sizeCode` 列。若缺失，默认 `X`。

---

## 七、改造所有 RSKU 创建入口

### 7.1 入口清单

| 入口 | Service 方法 |
|------|--------------|
| 工厂单条录入第一条 RSKU | `ProductService.createFactoryEntry()` |
| RSKU 新增报价 | `RskuService.createRsku()` |
| RSKU 批量新增 | `RskuService.batchCreateRsku()`（如存在） |
| Excel AI 导入 | `ExcelAiImportService` 中创建 RSKU 处 |
| 普通 Excel 导入 | `ProductImportService` 中创建 RSKU 处 |

### 7.2 RSKU 创建流程改造

所有 RSKU 创建统一增加：

```text
...准备 RSKU 字段...
调用 RskuCodeService.assignCode(rskuId, rspuCode, factoryCode, materialCode)
写入 rsku_code
执行 rskuSupplyMapper.insert(rsku)
```

注意：RSKU 编码依赖 RSPU 编码，因此所属 RSPU 必须先完成 `rspu_code` 写入。

---

## 八、存量数据迁移

### 8.1 迁移原则

1. **幂等**：迁移脚本可重复执行，已生成编码的数据不再重复生成。
2. **可追溯**：记录迁移日志，便于排查异常。
3. **不删除数据**：仅 UPDATE `rspu_code` / `rsku_code` 字段。
4. **计数器回初始化**：根据存量编码反初始化计数器，确保新数据不冲突。

### 8.2 RSPU 存量补码

新增 `database/V19__backfill_rspu_code.sql`（可与 V19 合并）：

```sql
-- 为没有 rspu_code 的存量 RSPU 生成编码
-- 需要先把 positioning_label 中文映射为风格码
-- 尺寸码缺失时默认 X
-- 由于需要按 category + style 分组生成流水号，建议用 Java 迁移类实现
```

**推荐用 Java 启动期迁移类实现**，因为涉及字典映射和分组递增：

`server/src/main/java/com/rsdp/migration/RspuCodeBackfillMigration.java`：

```java
@Component
public class RspuCodeBackfillMigration implements CommandLineRunner {
    // 1. 查询所有 rspu_code 为空的 RSPU
    // 2. 按 category_code + style_code 分组
    // 3. 对每个 RSPU 调用 RspuCodeService.assignCode()
    // 4. 记录日志
}
```

### 8.3 RSKU 存量补码

类似地，新增 `RskuCodeBackfillMigration`：

```java
@Component
public class RskuCodeBackfillMigration implements CommandLineRunner {
    // 1. 查询所有 rsku_code 为空的 RSKU
    // 2. 确保所属 RSPU 已有 rspu_code
    // 3. 按 rspu_code + factory_code + material_code 分组
    // 4. 调用 RskuCodeService.assignCode()
}
```

### 8.4 计数器初始化

在补码完成后，需要根据已生成的编码反初始化计数器：

```sql
-- RSPU
INSERT INTO rspu_code_counter (category_code, style_code, sequence_value, updated_at)
SELECT 
    split_part(rspu_code, '-', 1) AS category_code,
    split_part(rspu_code, '-', 2) AS style_code,
    MAX(CAST(split_part(rspu_code, '-', 3) AS BIGINT)) AS sequence_value,
    CURRENT_TIMESTAMP
FROM rspu_master
WHERE rspu_code IS NOT NULL
GROUP BY category_code, style_code
ON CONFLICT (category_code, style_code)
DO UPDATE SET sequence_value = EXCLUDED.sequence_value,
              updated_at = CURRENT_TIMESTAMP;

-- RSKU
INSERT INTO rsku_code_counter (rspu_code, factory_code, material_code, sequence_value, updated_at)
SELECT 
    split_part(rsku_code, '-', 1) || '-' || split_part(rsku_code, '-', 2) || '-' || split_part(rsku_code, '-', 3) || '-' || split_part(rsku_code, '-', 4) AS rspu_code,
    split_part(rsku_code, '-', 5) AS factory_code,
    split_part(rsku_code, '-', 6) AS material_code,
    MAX(CAST(split_part(rsku_code, '-', 7) AS BIGINT)) AS sequence_value,
    CURRENT_TIMESTAMP
FROM rsku_supply
WHERE rsku_code IS NOT NULL
GROUP BY rspu_code, factory_code, material_code
ON CONFLICT (rspu_code, factory_code, material_code)
DO UPDATE SET sequence_value = EXCLUDED.sequence_value,
              updated_at = CURRENT_TIMESTAMP;
```

> 注意：上述 SQL 假设 RSKU 编码为 7 段。如果采用 6 段规则，需要调整 split_part 索引。

---

## 九、前端与 DTO 同步

### 9.1 后端响应 DTO 加字段

需要在以下 DTO 中增加 `rspuCode` / `rskuCode`：

- `ProductSummaryResponse`
- `ProductDetailResponse`
- `RskuResponse`
- `OrderItemResponse` / `OrderDetailResponse`
- `SchemeItemResponse`
- `QuoteItemResponse`
- `FavoriteResponse`

### 9.2 前端展示调整

至少以下页面需要展示业务编码：

- 产品列表页 `/products`
- 产品详情页 `/products/:rspuId`
- RSKU 报价列表
- 方案详情页 `/schemes/:schemeId`
- 订单详情页 `/orders/:orderId`
- 报价单生成器 `/quotes/build`
- 收藏夹 `/favorites`

### 9.3 Excel 导入模板

- 增加 `sizeCode` 列（必填或可选，视决策而定）。
- 导出模板时增加 `rspuCode` / `rskuCode` 列。

---

## 十、测试计划

### 10.1 单元测试

新增/完善以下测试：

- `RspuCodeServiceTest`
  - 正常生成编码
  - 同一 category+style 多次生成流水号递增
  - sizeCode 缺失时默认 X
  - 非法字典码抛异常
  - 流水号超过 999 抛异常
- `RskuCodeServiceTest`
  - 正常生成编码
  - 同一 rspu+factory+material 多次生成流水号递增
  - RSPU 无编码时抛异常

### 10.2 并发测试

- 多线程同时创建同一 category+style 的 RSPU，验证流水号不重复。
- 多线程同时创建同一 rspu+factory+material 的 RSKU，验证流水号不重复。

### 10.3 集成测试

- 各创建入口（图片录入、工厂录入、PDF 导入、Excel 导入、Excel AI 导入）创建后，数据库中 `rspu_code` / `rsku_code` 正确。
- 存量数据迁移脚本执行后，所有 RSPU/RSKU 都有编码，且唯一。

### 10.4 字段对账

执行：

```bash
node scripts/check_entity_db_fields.js
```

确保 `RspuMaster` / `RskuSupply` 实体与数据库表字段一致。

---

## 十一、实施里程碑

建议按以下里程碑分阶段交付：

| 阶段 | 内容 | 预计工作量 | 可验证产出 |
|------|------|------------|------------|
| **M1** | 数据库改造 + 实体 + Mapper + Service | 1 天 | 编码服务可独立调用并生成编码 |
| **M2** | 改造一个最小入口（工厂单条录入） | 0.5 天 | 工厂录入后 RSPU/RSKU 都有业务编码 |
| **M3** | 改造其余 RSPU 入口（图片、PDF、Excel、Excel AI） | 1.5 天 | 所有入口创建的数据都有编码 |
| **M4** | 改造其余 RSKU 入口 | 1 天 | 所有 RSKU 创建入口都有编码 |
| **M5** | 存量数据迁移 | 0 天 | **跳过**（确认清空重新录入） |
| **M6** | 前端/DTO 同步 | 1 天 | 关键页面展示业务编码 |
| **M7** | 测试与修复 | 1 天 | 全部测试通过 |

**总计约 6 人天。**

---

## 十二、风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|----------|
| 存量数据风格/职级中文无法映射为编码 | 补码失败 | 新增字典映射兜底规则；无法映射时标记为 `XX` 并人工处理 |
| 并发下编码重复 | 数据异常 | 数据库唯一索引 + Service 重试机制 |
| 业务编码生成后又被修改 | 历史单据混乱 | 应用层禁止更新；数据库可加触发器或只读策略 |
| RSKU 编码规则变更 | 已生成编码不一致 | 规则必须在实施前最终确认；本方案采用带流水号的 7 段规则 |
| 现有外系统使用 UUID 主键 | 兼容性问题 | 保留 UUID 主键不变，业务编码仅新增字段，不影响现有接口 |

---

## 十三、已确认事项

经与业务方确认，以下关键决策已确定：

1. **RSPU 尺寸码规则**：**必须传入或由 AI 推断**，不允许默认 `X`。如果创建时无法确定尺寸码，应拦截并提示补充，而不是生成带 `X` 的编码。
2. **RSKU 编码规则**：采用 **7 段规则**，即 `{rspu_code}-{factory_code}-{material_code}-{variant_seq}`，以支持同一 RSPU + 同一工厂 + 同一材质下的多个变体报价。
3. **存量数据**：**不需要为已有 RSPU/RSKU 补业务编码**。后续将清空数据库重新录入，所有新数据均按新规则生成编码。
4. **业务编码变更**：**严格禁止变更**。编码一旦生成不可修改；若业务属性本质变化，应新建 RSPU/RSKU。
5. **前端展示优先级**：MVP 阶段至少覆盖产品列表、产品详情、RSKU 报价列表；方案、订单、报价单等第二批覆盖。

基于第 3 条，**M5 存量数据迁移阶段可以跳过**，实施计划调整为 6 个阶段、约 6 人天。

---

## 十四、结论

建议按本方案实施，优先完成 **M1（基础服务）+ M2（工厂录入入口）** 作为 MVP，验证编码生成与存储链路无误后，再推广到全部入口和存量迁移。
