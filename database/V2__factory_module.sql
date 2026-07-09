-- RSDP V2 数据库迁移：工厂管理模块完善 + Excel 导入增强
-- 基于 docs/02-architecture/05-RSDP-数据库完善方案-工厂与导入模块.md

-- ============================================================
-- 一、扩展现有表
-- ============================================================

-- 1.1 扩展 excel_import_batch：关联工厂、发货地、交期/MOQ、导入元数据
ALTER TABLE excel_import_batch
    ADD COLUMN IF NOT EXISTS factory_code VARCHAR(16),
    ADD COLUMN IF NOT EXISTS factory_name VARCHAR(128),
    ADD COLUMN IF NOT EXISTS shipping_warehouse_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS shipping_from VARCHAR(128),
    ADD COLUMN IF NOT EXISTS default_lead_time_days INTEGER,
    ADD COLUMN IF NOT EXISTS default_moq INTEGER,
    ADD COLUMN IF NOT EXISTS category_hint VARCHAR(16),
    ADD COLUMN IF NOT EXISTS header_row_count INTEGER DEFAULT 2,
    ADD COLUMN IF NOT EXISTS data_start_row INTEGER DEFAULT 3,
    ADD COLUMN IF NOT EXISTS import_note TEXT,
    ADD COLUMN IF NOT EXISTS processed_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_excel_import_batch_factory ON excel_import_batch(factory_code);

-- 1.2 扩展 factory_master：产能评估相关字段
ALTER TABLE factory_master
    ADD COLUMN IF NOT EXISTS capacity_tier_score DECIMAL(5,2),
    ADD COLUMN IF NOT EXISTS last_assessment_period VARCHAR(16),
    ADD COLUMN IF NOT EXISTS last_assessment_date DATE,
    ADD COLUMN IF NOT EXISTS import_batch_source VARCHAR(32),
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(16) DEFAULT 'manual';

COMMENT ON COLUMN factory_master.factory_level IS '工厂层级: S级战略厂/A级核心厂/B级合作厂/C级备选厂，由 capacity_tier_score 自动计算或手动指定';

-- ============================================================
-- 二、新增核心关联表
-- ============================================================

-- 2.1 RSPU-工厂多对多关联表（P0）
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
    UNIQUE (rspu_id, factory_code)
);

CREATE INDEX IF NOT EXISTS idx_rspu_factory_mapping_rspu ON rspu_factory_mapping(rspu_id, status);
CREATE INDEX IF NOT EXISTS idx_rspu_factory_mapping_factory ON rspu_factory_mapping(factory_code, status);
CREATE INDEX IF NOT EXISTS idx_rspu_factory_mapping_warehouse ON rspu_factory_mapping(shipping_warehouse_id);

-- 2.2 工厂交期规则表（P0）
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

-- 2.3 Excel 行级导入记录表（P0）
CREATE TABLE IF NOT EXISTS excel_import_row (
    row_id BIGSERIAL PRIMARY KEY,
    batch_id VARCHAR(32) NOT NULL,
    excel_row_number INTEGER NOT NULL,             -- Excel中的原始行号
    row_type VARCHAR(16) NOT NULL,                 -- product/module/header/unknown
    parent_row_id BIGINT,                          -- 模块行关联到产品型号行

    raw_data JSONB NOT NULL,                       -- 原始数据快照
    mapped_fields JSONB,                           -- AI映射后的字段
    selected_price_columns JSONB,                  -- 识别的价格列

    status VARCHAR(16) DEFAULT 'pending',          -- pending/processing/success/failed/skipped
    processing_stage VARCHAR(32),                  -- 当前处理阶段

    generated_rspu_id VARCHAR(64),                 -- 生成的RSPU ID
    generated_variant_id VARCHAR(64),              -- 生成的变体ID
    generated_rsku_ids JSONB,                      -- ["RSKU-001", "RSKU-002"]

    failure_reason TEXT,                           -- 失败原因描述
    failure_stage VARCHAR(32),                     -- 在哪个阶段失败

    extracted_image_count INTEGER DEFAULT 0,       -- 提取到的图片数量
    image_asset_ids JSONB,                         -- ["IMG-001", "IMG-002"]

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

-- ============================================================
-- 三、新增辅助表（P1）
-- ============================================================

-- 3.1 工厂产能评估历史表
CREATE TABLE IF NOT EXISTS factory_capacity_assessment (
    assessment_id BIGSERIAL PRIMARY KEY,
    factory_code VARCHAR(16) NOT NULL,
    assessment_period VARCHAR(16) NOT NULL,        -- 评估周期，如 2025Q2

    score_capacity_scale INTEGER,                  -- 产能规模得分
    score_on_time_rate INTEGER,                    -- 准时交付得分
    score_quality INTEGER,                         -- 质量合格率得分
    score_equipment INTEGER,                       -- 设备水平得分
    score_staffing INTEGER,                        -- 人员规模/稳定性得分
    score_flexibility INTEGER,                     -- 柔性生产能力得分

    tier_score DECIMAL(5,2) NOT NULL,              -- 综合评分
    calculated_tier VARCHAR(8),                    -- 计算出的层级 S/A/B/C

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

-- 3.2 RSPU 价格列映射记录表
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

-- 3.3 批次价格列识别表
CREATE TABLE IF NOT EXISTS excel_import_price_column (
    column_id BIGSERIAL PRIMARY KEY,
    batch_id VARCHAR(32) NOT NULL,
    excel_column_letter VARCHAR(8) NOT NULL,       -- 如 "G"
    column_header_name VARCHAR(128) NOT NULL,      -- 清洗后的列名
    raw_header_name VARCHAR(256),                  -- 原始列名
    suggested_material_grade VARCHAR(32),          -- AI建议的材质等级
    is_selected BOOLEAN DEFAULT TRUE,              -- 用户是否勾选
    sample_values JSONB,                           -- 前5个非空样本值
    data_type VARCHAR(16),                         -- numeric/text/mixed
    value_count INTEGER,                           -- 该列有值的行数
    min_value DECIMAL(18,2),                       -- 最小值
    max_value DECIMAL(18,2),                       -- 最大值
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (batch_id) REFERENCES excel_import_batch(batch_id)
);

CREATE INDEX IF NOT EXISTS idx_import_price_col_batch ON excel_import_price_column(batch_id);

-- ============================================================
-- 四、新增字典数据
-- ============================================================

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

-- 工厂来源类型字典
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('factory_source_type', 'MANUAL', '手动录入', 1),
('factory_source_type', 'EXCEL_IMPORT', 'Excel导入', 2),
('factory_source_type', 'API_SYNC', '接口同步', 3)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 导入行类型字典
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('import_row_type', 'PRODUCT', '产品型号行', 1),
('import_row_type', 'MODULE', '模块行', 2),
('import_row_type', 'HEADER', '表头行', 3),
('import_row_type', 'UNKNOWN', '未知', 4)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- ============================================================
-- 五、示例交期规则种子数据（MUJU 沙发工厂，可按实际调整）
-- ============================================================

-- 注意：以下数据为示例，实际使用前请确认 factory_master 中存在 'MUJU' 工厂
-- INSERT INTO factory_lead_time_rule (factory_code, category_code, material_grade_code, process_type, base_days, batch_size_threshold, batch_extra_days, priority, notes) VALUES
-- ('MUJU', 'SF', 'FABRIC_A', 'standard', 30, 50, 5, 10, '布艺沙发标准30天，超50套+5天'),
-- ('MUJU', 'SF', 'FABRIC_AA', 'standard', 30, 50, 5, 10, NULL),
-- ('MUJU', 'SF', 'LEATHER_A', 'standard', 35, 30, 7, 10, '真皮沙发需裁皮时间，35天起'),
-- ('MUJU', 'SF', 'LEATHER_AA', 'standard', 35, 30, 7, 10, NULL),
-- ('MUJU', 'SF', NULL, 'modular', 30, 50, 5, 50, '模块沙发通配规则'),
-- ('MUJU', 'SF', NULL, 'standard', 30, 50, 5, 100, '沙发品类默认规则');
