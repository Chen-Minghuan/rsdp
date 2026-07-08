# RSDP 数据库完善方案：工厂管理模块 + Excel AI 导入模块

## 一、现状分析

### 1.1 现有数据库已覆盖的能力

| 模块 | 已有表 | 覆盖情况 |
|------|--------|----------|
| RSPU 款式管理 | `rspu_master`, `rspu_variant` | 完善 |
| 报价/RSKU | `rsku_supply`, `price_history` | 基本完善，缺多厂关联 |
| 工厂档案 | `factory_master`, `factory_warehouse` | 基础结构存在 |
| 产能管理 | `factory_variant_capacity` | 基础字段存在 |
| 工厂等级 | `factory_level_capability` | 基础结构存在 |
| Excel导入 | `excel_import_batch` | 仅批次级，缺行级记录 |
| 图片管理 | `image_assets` | 完善 |
| AI识别 | `ai_recognition` | 完善 |

### 1.2 Excel 数据特征（以 MUJU 沙发清单为例）

```
表头结构（多行父子表头）：
第3行: 产品图样 | 型号品名 | 规格/模块 | 产品尺寸 | 材质说明 | 价格（PRICE） | 交期
第4行:                                      A级布 | AA级布 | S级布 | SS级进口布 | 半皮 | A级全皮 | AA级全皮 | S级全皮 | SS级全皮

数据行特征：
- 产品型号行: MJ-S96欧拉沙发（B列，合并单元格）
  - 模块行: 模块组合A、贵妃A位、无扶手B位...（C列）
  - 每模块有独立尺寸、价格（9个价格列）、材质说明
- 153行数据，50个产品，含153张内嵌图片
- 交期: 30日（42条）、35日（6条）、40日（2条）
- 工厂地址在表头第2行
```

### 1.3 关键缺口识别

| 缺口 | 说明 | 影响 |
|------|------|------|
| **缺 RSPU-工厂多对多关联** | 现有只能通过 RSKU 间接关联，无法直接查询"某产品有哪些可选工厂" | 无法按发货地筛选可生产工厂 |
| **缺动态交期规则** | 交期硬编码在 RSKU 中，无法根据品类+材质+工艺动态计算 | 新增同款不同材质时无法自动推算交期 |
| **缺 Excel 行级导入记录** | 只有 batch 级状态，无法追踪每行处理结果 | 导入失败时无法精确定位和重试 |
| **缺材质等级字典** | A级布/AA级布/S级布等是工厂的自定义等级体系 | 价格列无法标准化映射到材质版本 |
| **缺产能评估历史** | 工厂等级是静态字段，无评估记录 | 无法追踪工厂等级变化趋势 |

---

## 二、完善方案总览

### 2.1 新增表（6张）

| 新表名 | 用途 | 优先级 |
|--------|------|--------|
| `rspu_factory_mapping` | RSPU-工厂多对多关联（支持发货地筛选） | P0 |
| `factory_lead_time_rule` | 工厂交期规则模板（按品类+材质等级+工艺） | P0 |
| `excel_import_row` | Excel 行级导入记录（追踪每行处理结果） | P0 |
| `factory_capacity_assessment` | 工厂产能评估历史记录 | P1 |
| `rspu_price_column_mapping` | 价格列→材质版本映射记录 | P1 |
| `excel_import_price_column` | 批次识别出的价格列列表 | P1 |

### 2.2 现有表修改（3张）

| 表名 | 修改内容 |
|------|----------|
| `excel_import_batch` | 增加 `factory_code`, `shipping_warehouse_id`, `default_lead_time_days`, `import_note` 字段 |
| `factory_master` | 增加 `capacity_tier_score`, `last_assessment_date`, `import_batch_source` 字段 |
| `category_dict` | 新增 `material_grade` 类型字典 |

---

## 三、详细设计

### 3.1 新增: RSPU-工厂多对多关联表 `rspu_factory_mapping`

**用途**: 明确记录"哪些工厂可以生产这款产品"，支持按发货地筛选。

**设计说明**:
- 一个 RSPU（款式）可关联多个工厂
- 每个关联可指定默认发货仓库
- `is_primary` 标记主供工厂（默认报价来源）
- `status` 控制可用状态（如某工厂暂停生产某款）

```sql
CREATE TABLE IF NOT EXISTS rspu_factory_mapping (
    mapping_id BIGSERIAL PRIMARY KEY,
    rspu_id VARCHAR(64) NOT NULL,
    factory_code VARCHAR(16) NOT NULL,
    is_primary BOOLEAN DEFAULT FALSE,              -- 是否主供工厂
    shipping_warehouse_id VARCHAR(64),             -- 默认发货仓库
    moq INTEGER,                                    -- 该工厂对此款的MOQ
    base_lead_time_days INTEGER,                   -- 基础交期（天数）
    status VARCHAR(16) DEFAULT 'active',           -- active/paused/discontinued
    notes TEXT,                                     -- 备注：如"专做皮版"、"仅做布艺"
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code),
    FOREIGN KEY (shipping_warehouse_id) REFERENCES factory_warehouse(warehouse_id),
    UNIQUE (rspu_id, factory_code)                  -- 一个款式一个工厂只准一条
);

CREATE INDEX IF NOT EXISTS idx_rspu_factory_mapping_rspu ON rspu_factory_mapping(rspu_id, status);
CREATE INDEX IF NOT EXISTS idx_rspu_factory_mapping_factory ON rspu_factory_mapping(factory_code, status);
CREATE INDEX IF NOT EXISTS idx_rspu_factory_mapping_warehouse ON rspu_factory_mapping(shipping_warehouse_id);
```

**典型查询**:
```sql
-- 查询某产品所有可用工厂（按发货地筛选）
SELECT f.*, w.province, w.city, m.is_primary, m.base_lead_time_days
FROM rspu_factory_mapping m
JOIN factory_master f ON m.factory_code = f.factory_code
LEFT JOIN factory_warehouse w ON m.shipping_warehouse_id = w.warehouse_id
WHERE m.rspu_id = ? AND m.status = 'active' AND w.province = '广东省';
```

---

### 3.2 新增: 工厂交期规则表 `factory_lead_time_rule`

**用途**: 支持交期根据品类+材质等级+工艺类型动态计算。

**设计说明**:
- 按 `factory_code + category_code + material_grade_code + process_type` 定义基础交期
- `process_type` 区分工艺复杂度（如模块沙发 vs 整体沙发 vs 异形定制）
- `batch_size_threshold` 支持大批量额外加期
- `priority` 控制规则匹配优先级（越小的数字越优先）
- 与 `rspu_factory_mapping.base_lead_time_days` 的关系：规则算出默认值，mapping 可覆盖

```sql
CREATE TABLE IF NOT EXISTS factory_lead_time_rule (
    rule_id BIGSERIAL PRIMARY KEY,
    factory_code VARCHAR(16) NOT NULL,
    category_code VARCHAR(16),                    -- NULL=通配所有品类
    material_grade_code VARCHAR(32),              -- NULL=通配所有材质等级
    process_type VARCHAR(32) DEFAULT 'standard',  -- standard/modular/custom/irregular
    base_days INTEGER NOT NULL DEFAULT 30,        -- 基础交期天数
    batch_size_threshold INTEGER,                 -- 大批量阈值（超过此数量额外加期）
    batch_extra_days INTEGER DEFAULT 0,           -- 大批量额外交期
    material_switch_extra_days INTEGER DEFAULT 0, -- 换材质额外准备期
    priority INTEGER DEFAULT 100,                 -- 优先级，数字越小越优先
    status VARCHAR(16) DEFAULT 'active',
    notes TEXT,
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code),
    UNIQUE (factory_code, category_code, material_grade_code, process_type)
);

CREATE INDEX IF NOT EXISTS idx_lead_time_rule_factory ON factory_lead_time_rule(factory_code, status);
CREATE INDEX IF NOT EXISTS idx_lead_time_rule_match ON factory_lead_time_rule(factory_code, category_code, material_grade_code, process_type);
```

**交期计算逻辑**（伪代码）:
```
function calculateLeadTime(factoryCode, categoryCode, materialGradeCode, processType, quantity):
    // 1. 精确匹配
    rule = findRule(factoryCode, categoryCode, materialGradeCode, processType)
    if not rule:
        // 2. 忽略 material_grade 匹配
        rule = findRule(factoryCode, categoryCode, NULL, processType)
    if not rule:
        // 3. 忽略 process_type 匹配
        rule = findRule(factoryCode, categoryCode, NULL, 'standard')
    if not rule:
        // 4. 仅按 factory 默认
        rule = findRule(factoryCode, NULL, NULL, 'standard')
    
    days = rule.base_days
    if quantity > rule.batch_size_threshold:
        days += rule.batch_extra_days
    return days
```

**种子数据示例**（MUJU工厂）:
```sql
INSERT INTO factory_lead_time_rule (factory_code, category_code, material_grade_code, process_type, base_days, batch_size_threshold, batch_extra_days, priority, notes) VALUES
('MUJU', 'SF', 'A级布', 'standard', 30, 50, 5, 10, '布艺沙发标准30天，超50套+5天'),
('MUJU', 'SF', 'AA级布', 'standard', 30, 50, 5, 10, NULL),
('MUJU', 'SF', 'A级全皮', 'standard', 35, 30, 7, 10, '真皮沙发需裁皮时间，35天起'),
('MUJU', 'SF', 'AA级全皮', 'standard', 35, 30, 7, 10, NULL),
('MUJU', 'SF', NULL, 'modular', 30, 50, 5, 50, '模块沙发通配规则'),
('MUJU', 'SF', NULL, 'standard', 30, 50, 5, 100, '沙发品类默认规则');
```

---

### 3.3 新增: Excel 行级导入记录表 `excel_import_row`

**用途**: 追踪每一行 Excel 数据的处理状态、原始值、映射结果、生成的实体ID。

**设计说明**:
- 每行 Excel 数据对应一条记录（包括产品型号行和模块行）
- `row_type` 区分产品型号行(product)和模块行(module)
- `raw_data` 存储该行的原始JSON快照
- `mapped_fields` 存储AI映射后的字段值
- `generated_rspu_id` / `generated_variant_id` / `generated_rsku_ids` 记录生成的实体
- `status` 追踪处理状态
- `failure_reason` 记录失败原因

```sql
CREATE TABLE IF NOT EXISTS excel_import_row (
    row_id BIGSERIAL PRIMARY KEY,
    batch_id VARCHAR(32) NOT NULL,
    excel_row_number INTEGER NOT NULL,             -- Excel中的原始行号
    row_type VARCHAR(16) NOT NULL,                 -- product(型号行) / module(模块行) / header / unknown
    parent_row_id BIGINT,                          -- 模块行关联到产品型号行
    
    -- 原始数据快照
    raw_data JSONB NOT NULL,                       -- {colA: "...", colB: "...", ...}
    
    -- AI映射后的字段
    mapped_fields JSONB,                           -- {rspu_name: "...", model_no: "...", dimensions: "...", ...}
    
    -- 识别的价格列（模块行有效）
    selected_price_columns JSONB,                  -- ["A级布", "AA级布"]
    
    -- 处理状态
    status VARCHAR(16) DEFAULT 'pending',          -- pending / processing / success / failed / skipped
    processing_stage VARCHAR(32),                  -- 当前处理阶段
    
    -- 生成的实体关联
    generated_rspu_id VARCHAR(64),                 -- 生成的RSPU ID
    generated_variant_id VARCHAR(64),              -- 生成的变体ID
    generated_rsku_ids JSONB,                      -- ["RSKU-001", "RSKU-002"]
    
    -- 失败信息
    failure_reason TEXT,                           -- 失败原因描述
    failure_stage VARCHAR(32),                     -- 在哪个阶段失败
    
    -- 图片处理
    extracted_image_count INTEGER DEFAULT 0,       -- 提取到的图片数量
    image_asset_ids JSONB,                         -- ["IMG-001", "IMG-002"]
    
    -- AI识别任务
    ai_task_id VARCHAR(64),                        -- 关联的异步AI识别任务
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    
    FOREIGN KEY (batch_id) REFERENCES excel_import_batch(batch_id),
    FOREIGN KEY (parent_row_id) REFERENCES excel_import_row(row_id),
    FOREIGN KEY (generated_rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (generated_variant_id) REFERENCES rspu_variant(variant_id),
    UNIQUE (batch_id, excel_row_number)
);

CREATE INDEX IF NOT EXISTS idx_import_row_batch ON excel_import_row(batch_id, status);
CREATE INDEX IF NOT EXISTS idx_import_row_type ON excel_import_row(batch_id, row_type);
CREATE INDEX IF NOT EXISTS idx_import_row_rspu ON excel_import_row(generated_rspu_id);
CREATE INDEX IF NOT EXISTS idx_import_row_parent ON excel_import_row(parent_row_id);
```

**典型使用场景**:
```sql
-- 查询某批次中所有失败行
SELECT excel_row_number, row_type, raw_data, failure_reason, failure_stage
FROM excel_import_row
WHERE batch_id = ? AND status = 'failed';

-- 查询某批次处理统计
SELECT 
    status,
    row_type,
    COUNT(*) as cnt
FROM excel_import_row
WHERE batch_id = ?
GROUP BY status, row_type;

-- 查询某RSPU是从哪次导入创建的
SELECT batch_id, excel_row_number, raw_data->>'型号品名' as model_name
FROM excel_import_row
WHERE generated_rspu_id = ?;
```

---

### 3.4 新增: 工厂产能评估历史表 `factory_capacity_assessment`

**用途**: 记录工厂产能评估的历史，支持动态分层。

**设计说明**:
- 定期（如每季度）对工厂进行产能评估
- 评估维度：产能规模、准时率、质量合格率、设备水平、人员规模
- 最终计算出 `tier_score`（0-100），决定工厂层级
- `factory_master.capacity_tier_score` 存最新值

```sql
CREATE TABLE IF NOT EXISTS factory_capacity_assessment (
    assessment_id BIGSERIAL PRIMARY KEY,
    factory_code VARCHAR(16) NOT NULL,
    assessment_period VARCHAR(16) NOT NULL,        -- 评估周期，如 2025Q2
    
    -- 各维度评分（0-100）
    score_capacity_scale INTEGER,                  -- 产能规模得分
    score_on_time_rate INTEGER,                    -- 准时交付得分
    score_quality INTEGER,                         -- 质量合格率得分
    score_equipment INTEGER,                       -- 设备水平得分
    score_staffing INTEGER,                        -- 人员规模/稳定性得分
    score_flexibility INTEGER,                     -- 柔性生产能力得分
    
    tier_score DECIMAL(5,2) NOT NULL,              -- 综合评分
    calculated_tier VARCHAR(8),                    -- 计算出的层级 S/A/B/C
    
    -- 评估依据数据
    monthly_capacity_avg INTEGER,                  -- 月均产能
    on_time_rate DECIMAL(5,4),                     -- 准时率
    quality_return_rate DECIMAL(5,4),              -- 退货率
    active_rspu_count INTEGER,                     -- 在产款式数
    active_rsku_count INTEGER,                     -- 在产报价数
    
    assessed_by VARCHAR(64),
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code)
);

CREATE INDEX IF NOT EXISTS idx_assessment_factory ON factory_capacity_assessment(factory_code, assessment_period);
CREATE INDEX IF NOT EXISTS idx_assessment_period ON factory_capacity_assessment(assessment_period);
```

**层级计算规则**（建议）:
| 综合评分 | 层级 | 说明 |
|----------|------|------|
| 85-100 | S | 战略级工厂，核心合作伙伴 |
| 70-84 | A | 核心工厂，主要订单来源 |
| 55-69 | B | 合作工厂，补充产能 |
| <55 | C | 备选工厂，小单/试单 |

---

### 3.5 新增: 价格列映射记录表 `rspu_price_column_mapping`

**用途**: 记录每次 Excel 导入时，识别出的价格列与实际材质版本的映射关系，便于后续追踪和复用。

```sql
CREATE TABLE IF NOT EXISTS rspu_price_column_mapping (
    mapping_id BIGSERIAL PRIMARY KEY,
    rspu_id VARCHAR(64) NOT NULL,
    batch_id VARCHAR(32) NOT NULL,
    price_column_name VARCHAR(64) NOT NULL,        -- 如 "A级布"
    material_grade_code VARCHAR(32),               -- 映射到的材质等级码
    material_code VARCHAR(32),                     -- 映射到的主材质码
    factory_price DECIMAL(18,2),                   -- 导入时的价格
    factory_code VARCHAR(16),                      -- 所属工厂
    is_selected BOOLEAN DEFAULT TRUE,              -- 用户是否勾选导入
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (batch_id) REFERENCES excel_import_batch(batch_id),
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code)
);

CREATE INDEX IF NOT EXISTS idx_price_col_mapping_rspu ON rspu_price_column_mapping(rspu_id);
CREATE INDEX IF NOT EXISTS idx_price_col_mapping_batch ON rspu_price_column_mapping(batch_id);
```

---

### 3.6 新增: 批次价格列识别表 `excel_import_price_column`

**用途**: 记录每个导入批次中 AI 识别出的所有价格列，供用户确认勾选。

```sql
CREATE TABLE IF NOT EXISTS excel_import_price_column (
    column_id BIGSERIAL PRIMARY KEY,
    batch_id VARCHAR(32) NOT NULL,
    excel_column_letter VARCHAR(8) NOT NULL,       -- 如 "G"
    column_header_name VARCHAR(128) NOT NULL,      -- 清洗后的列名，如 "A级布"
    raw_header_name VARCHAR(256),                  -- 原始列名（未清洗）
    suggested_material_grade VARCHAR(32),          -- AI建议的材质等级
    is_selected BOOLEAN DEFAULT TRUE,              -- 用户是否勾选
    sample_values JSONB,                           -- 前5个非空样本值
    data_type VARCHAR(16),                         -- numeric / text / mixed
    value_count INTEGER,                           -- 该列有值的行数
    min_value DECIMAL(18,2),                       -- 最小值
    max_value DECIMAL(18,2),                       -- 最大值
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (batch_id) REFERENCES excel_import_batch(batch_id)
);

CREATE INDEX IF NOT EXISTS idx_import_price_col_batch ON excel_import_price_column(batch_id);
```

---

### 3.7 修改: `excel_import_batch` 表

```sql
-- 增加工厂和发货地相关字段
ALTER TABLE excel_import_batch 
    ADD COLUMN IF NOT EXISTS factory_code VARCHAR(16),
    ADD COLUMN IF NOT EXISTS factory_name VARCHAR(128),           -- 从Excel提取的工厂名
    ADD COLUMN IF NOT EXISTS shipping_warehouse_id VARCHAR(64),   -- 默认发货仓库
    ADD COLUMN IF NOT EXISTS shipping_from VARCHAR(128),          -- 发货地（冗余，快速展示）
    ADD COLUMN IF NOT EXISTS default_lead_time_days INTEGER,      -- 默认交期
    ADD COLUMN IF NOT EXISTS default_moq INTEGER,                 -- 默认MOQ
    ADD COLUMN IF NOT EXISTS category_hint VARCHAR(16),           -- 品类提示（用户输入）
    ADD COLUMN IF NOT EXISTS header_row_count INTEGER DEFAULT 2,  -- 表头占用行数（用于跳过）
    ADD COLUMN IF NOT EXISTS data_start_row INTEGER DEFAULT 5,    -- 数据起始行号
    ADD COLUMN IF NOT EXISTS import_note TEXT,                    -- 导入备注
    ADD COLUMN IF NOT EXISTS processed_at TIMESTAMP;              -- 处理完成时间

-- 外键（可选，根据实际数据情况决定是否加）
-- ALTER TABLE excel_import_batch 
--     ADD CONSTRAINT fk_import_batch_factory FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code);

CREATE INDEX IF NOT EXISTS idx_excel_import_batch_factory ON excel_import_batch(factory_code);
```

---

### 3.8 修改: `factory_master` 表

```sql
-- 增加产能评估相关字段
ALTER TABLE factory_master
    ADD COLUMN IF NOT EXISTS capacity_tier_score DECIMAL(5,2),      -- 最新综合评分
    ADD COLUMN IF NOT EXISTS last_assessment_period VARCHAR(16),    -- 最近评估周期
    ADD COLUMN IF NOT EXISTS last_assessment_date DATE,             -- 最近评估日期
    ADD COLUMN IF NOT EXISTS import_batch_source VARCHAR(32),       -- 首次来源导入批次
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(16) DEFAULT 'manual'; -- manual / excel_import / api_sync

-- 增加产能分层字段（如果字典值不够，可以扩展）
COMMENT ON COLUMN factory_master.factory_level IS '工厂层级: S级战略厂/A级核心厂/B级合作厂/C级备选厂，由 capacity_tier_score 自动计算或手动指定';
```

---

### 3.9 新增字典数据

```sql
-- 材质等级字典（对应Excel中的价格列名）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('material_grade', 'FABRIC_A', 'A级布', 1),
('material_grade', 'FABRIC_AA', 'AA级布', 2),
('material_grade', 'FABRIC_S', 'S级布', 3),
('material_grade', 'FABRIC_SS', 'SS级进口布', 4),
('material_grade', 'LEATHER_HALF', '半皮', 10),
('material_grade', 'LEATHER_A', 'A级全皮', 11),
('material_grade', 'LEATHER_AA', 'AA级全皮', 12),
('material_grade', 'LEATHER_S', 'S级全皮', 13),
('material_grade', 'LEATHER_SS', 'SS级全皮', 14)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 工艺类型字典
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('process_type', 'STANDARD', '标准工艺', 1),
('process_type', 'MODULAR', '模块化组合', 2),
('process_type', 'CUSTOM', '非标定制', 3),
('process_type', 'IRREGULAR', '异形/特殊', 4),
('process_type', 'QUICK', '快单/现货', 5)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 导入行状态字典
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('import_row_status', 'PENDING', '待处理', 1),
('import_row_status', 'PROCESSING', '处理中', 2),
('import_row_status', 'SUCCESS', '成功', 3),
('import_row_status', 'FAILED', '失败', 4),
('import_row_status', 'SKIPPED', '已跳过', 5)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 映射状态字典
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('mapping_status', 'ACTIVE', '生效中', 1),
('mapping_status', 'PAUSED', '暂停', 2),
('mapping_status', 'DISCONTINUED', '已终止', 3)
ON CONFLICT (dict_type, dict_code) DO NOTHING;
```

---

## 四、导入流程的数据库交互时序

### 4.1 阶段1: 上传并预览映射

```
1. 用户上传 Excel
   → INSERT INTO excel_import_batch (batch_id, file_name, storage_path, status='uploaded')
   
2. 后端清洗表头，提取价格列
   → INSERT INTO excel_import_price_column (batch_id, excel_column_letter, column_header_name, ...)
   每条价格列一条记录
   
3. AI返回字段映射建议
   → UPDATE excel_import_batch SET column_mapping = {...}, preview_rows = {...}, status='mapped'
```

### 4.2 阶段2: 用户确认映射

```
4. 用户确认字段映射、勾选价格列、输入工厂信息
   → UPDATE excel_import_price_column SET is_selected = true/false WHERE ...
   → UPDATE excel_import_batch SET 
       factory_code = 'MUJU',
       shipping_warehouse_id = 'WH-001',
       default_lead_time_days = 30,
       default_moq = 1,
       category_hint = 'SF',
       status = 'confirmed'
```

### 4.3 阶段3: 执行导入

```
5. 逐行处理 Excel 数据
   → INSERT INTO excel_import_row (batch_id, excel_row_number, row_type, raw_data, mapped_fields, status='processing')
   
6. 创建 RSPU
   → INSERT INTO rspu_master (...)
   → UPDATE excel_import_row SET generated_rspu_id = 'RSPU-XXX', status='success'
   
7. 创建 RSPU-工厂映射
   → INSERT INTO rspu_factory_mapping (rspu_id, factory_code, is_primary=true, ...)
   
8. 创建变体 + RSKU（每个勾选的价格列）
   → INSERT INTO rspu_variant (...)
   → INSERT INTO rsku_supply (..., factory_price, lead_time_days, ...)
   → UPDATE excel_import_row SET generated_variant_id = 'VAR-XXX', generated_rsku_ids = '[...]'
   → INSERT INTO rspu_price_column_mapping (...)
   
9. 提取图片 → INSERT INTO image_assets (...)
   → UPDATE excel_import_row SET extracted_image_count = N, image_asset_ids = '[...]'
   
10. 触发AI识别 → INSERT INTO ai_recognition (...)
    → UPDATE excel_import_row SET ai_task_id = 'TASK-XXX'
```

### 4.4 阶段4: 查询结果

```
11. 更新批次状态
    → UPDATE excel_import_batch SET 
        status = 'completed',
        total_rows = 50,
        success_count = 48,
        failed_count = 2,
        failures = '[...]',
        processed_at = NOW()
```

---

## 五、关键业务查询示例

### 5.1 按发货地筛选可生产某款的工厂

```sql
SELECT 
    f.factory_code,
    f.factory_name,
    f.factory_level,
    w.warehouse_id,
    w.province,
    w.city,
    m.is_primary,
    m.base_lead_time_days,
    m.moq
FROM rspu_factory_mapping m
JOIN factory_master f ON m.factory_code = f.factory_code
LEFT JOIN factory_warehouse w ON m.shipping_warehouse_id = w.warehouse_id
WHERE m.rspu_id = 'RSPU-SF-MC-001'
  AND m.status = 'active'
  AND (w.province = '广东省' OR m.shipping_warehouse_id IS NULL)
ORDER BY m.is_primary DESC, f.factory_level;
```

### 5.2 动态计算某工厂某款的交期

```sql
-- 使用最匹配的规则
SELECT base_days, batch_extra_days, batch_size_threshold
FROM factory_lead_time_rule
WHERE factory_code = 'MUJU'
  AND status = 'active'
  AND (category_code = 'SF' OR category_code IS NULL)
  AND (material_grade_code = 'A级布' OR material_grade_code IS NULL)
  AND (process_type = 'modular' OR process_type = 'standard')
ORDER BY priority, 
         (CASE WHEN category_code IS NOT NULL THEN 0 ELSE 1 END),
         (CASE WHEN material_grade_code IS NOT NULL THEN 0 ELSE 1 END)
LIMIT 1;
```

### 5.3 查询某导入批次的完整结果

```sql
-- 批次概览
SELECT 
    b.batch_id,
    b.file_name,
    b.factory_code,
    b.status,
    COUNT(r.row_id) FILTER (WHERE r.status = 'success') as success_count,
    COUNT(r.row_id) FILTER (WHERE r.status = 'failed') as failed_count,
    COUNT(r.row_id) FILTER (WHERE r.row_type = 'product') as product_count,
    COUNT(r.row_id) FILTER (WHERE r.row_type = 'module') as module_count
FROM excel_import_batch b
LEFT JOIN excel_import_row r ON b.batch_id = r.batch_id
WHERE b.batch_id = 'BATCH-20250710-001'
GROUP BY b.batch_id;

-- 失败明细
SELECT 
    excel_row_number,
    row_type,
    raw_data->>'B' as product_name,
    raw_data->>'C' as module_name,
    failure_stage,
    failure_reason
FROM excel_import_row
WHERE batch_id = 'BATCH-20250710-001' AND status = 'failed'
ORDER BY excel_row_number;
```

### 5.4 查询某工厂产能评估趋势

```sql
SELECT 
    assessment_period,
    tier_score,
    calculated_tier,
    score_capacity_scale,
    score_on_time_rate,
    score_quality,
    monthly_capacity_avg,
    on_time_rate
FROM factory_capacity_assessment
WHERE factory_code = 'MUJU'
ORDER BY assessment_period;
```

---

## 六、实施建议

### 6.1 实施优先级

| 阶段 | 内容 | 预计工时 |
|------|------|----------|
| **Phase 1（本周）** | 执行本文SQL脚本（新增表+修改表+字典数据） | 0.5天 |
| **Phase 1** | 后端：Excel导入批次接口改造（关联工厂/仓库） | 1天 |
| **Phase 1** | 后端：行级导入记录写入逻辑 | 1天 |
| **Phase 2（下周）** | 后端：RSPU-工厂映射自动创建 | 0.5天 |
| **Phase 2** | 后端：交期规则匹配算法 | 1天 |
| **Phase 2** | 前端：用户确认界面增加工厂/发货地/MOQ输入 | 1天 |
| **Phase 3** | 后端：产能评估定时任务 | 1天 |
| **Phase 3** | 后端：按发货地筛选工厂API | 0.5天 |

### 6.2 数据迁移

现有数据无需迁移，新表均为增量使用。`factory_master` 新增的字段允许 NULL，不影响现有数据。

### 6.3 兼容性

- 所有修改均为向后兼容（新增表 + 新增可空字段）
- `excel_import_batch` 表的外键约束建议不加（防止工厂表数据缺失导致导入失败），由应用层校验
- 现有 `rsku_supply` 表继续工作，新逻辑通过 `rspu_factory_mapping` 补充多厂关联能力

---

## 七、ER关系补充说明

```
rspu_master (1) ───< rspu_factory_mapping >─── (N) factory_master
                                    │
                                    └─> factory_warehouse

factory_master (1) ───< factory_lead_time_rule >─── (N) [rule combinations]

factory_master (1) ───< factory_capacity_assessment >─── (N) [history]

excel_import_batch (1) ───< excel_import_row >─── (N) [row records]
                        │
                        ├──< excel_import_price_column >─── (N) [price columns]
                        │
                        └── rspu_price_column_mapping (via rspu)
```
