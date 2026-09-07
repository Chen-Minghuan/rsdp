-- ============================================================
-- RSDP 基线 DDL · 03 工厂域（03_factory.sql）
-- 包含表：factory_master, factory_level_capability, factory_warehouse, factory_variant_capacity, rspu_factory_mapping, factory_lead_time_rule, factory_capacity_assessment, factory_product_capability
-- 执行顺序：schema/ 目录按文件名 01 → 12 → 99 依次执行（编号即执行顺序，基线由原 V1__init_db.sql 按域拆分而来）
-- 同步约定：新增/修改本域表结构时须同步 ops/reset_db.sql，约定详见 database/README.md
-- ============================================================
-- 工厂档案表
CREATE TABLE IF NOT EXISTS factory_master (
    factory_code VARCHAR(16) PRIMARY KEY,
    factory_name VARCHAR(128) NOT NULL,
    factory_level VARCHAR(8) NOT NULL,
    home_commercial_tag VARCHAR(16),
    certification JSONB,
    engineering_cases JSONB,
    region VARCHAR(64),
    address TEXT,
    contact_person VARCHAR(64),
    contact_phone VARCHAR(32),
    first_audit_date DATE,
    next_visit_date DATE,
    notes TEXT,
    -- 规模信息
    factory_area DECIMAL(10,2),
    employee_count INTEGER,
    monthly_capacity INTEGER,
    founded_year INTEGER,
    -- 设备清单
    equipment_list JSONB,
    -- 原料来源
    frame_wood VARCHAR(32),
    sponge_supplier VARCHAR(128),
    leather_fabric_source VARCHAR(128),
    hardware_supplier VARCHAR(128),
    -- 品质控制
    qc_items JSONB,
    qc_staff_count INTEGER,
    -- 物流信息
    shipping_from VARCHAR(128),
    logistics_methods JSONB,
    default_packaging JSONB,
    -- 验厂信息
    auditor_signature VARCHAR(64),
    -- 工厂图片
    factory_images JSONB,
    -- 产能评估与来源（V2 并入）
    capacity_tier_score DECIMAL(5,2),                -- 最新综合评分
    last_assessment_period VARCHAR(16),              -- 最近评估周期
    last_assessment_date DATE,                       -- 最近评估日期
    import_batch_source VARCHAR(32),                 -- 首次来源导入批次
    source_type VARCHAR(16) DEFAULT 'manual',        -- manual/excel_import/api_sync
    status VARCHAR(16) DEFAULT 'active',
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ
);

COMMENT ON COLUMN factory_master.factory_level IS '工厂层级: S级战略厂/A级核心厂/B级合作厂/C级备选厂，由 capacity_tier_score 自动计算或手动指定';

-- 工厂能力等级表（记录工厂可承接的所有等级，其中主评级标记为 is_primary = true）
CREATE TABLE IF NOT EXISTS factory_level_capability (
    id BIGSERIAL PRIMARY KEY,
    factory_code VARCHAR(16) NOT NULL,
    level_code VARCHAR(8) NOT NULL,
    is_primary BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code),
    UNIQUE (factory_code, level_code)
);
-- idx_factory_level_capability_factory 已于 V43 删除：UNIQUE(factory_code, level_code) 左前缀，冗余

-- 工厂仓库表（一个工厂可有多个发货仓库）
CREATE TABLE IF NOT EXISTS factory_warehouse (
    warehouse_id VARCHAR(64) PRIMARY KEY,
    factory_code VARCHAR(16) NOT NULL,
    warehouse_name VARCHAR(128),
    province VARCHAR(64),
    city VARCHAR(64),
    district VARCHAR(64),
    address TEXT,
    contact_person VARCHAR(64),
    contact_phone VARCHAR(32),
    is_default BOOLEAN DEFAULT FALSE,
    status VARCHAR(16) DEFAULT 'active',
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code)
);

-- 工厂-变体产能表（记录工厂对某变体的产能能力）
CREATE TABLE IF NOT EXISTS factory_variant_capacity (
    factory_code VARCHAR(16) NOT NULL,
    variant_id VARCHAR(64) NOT NULL,
    monthly_capacity INTEGER,                      -- 月产能（件）
    current_booked INTEGER DEFAULT 0,              -- 已占用产能
    max_batch_size INTEGER,                        -- 单次最大接单量
    capacity_unit VARCHAR(16) DEFAULT '件',         -- 件 / 套 / 立方米
    lead_time_batch_days INTEGER,                  -- 大批量额外交期
    notes TEXT,
    updated_at TIMESTAMPTZ,
    PRIMARY KEY (factory_code, variant_id),
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code),
    FOREIGN KEY (variant_id) REFERENCES rspu_variant(variant_id)
);

-- RSPU-工厂多对多关联表（V2 并入）
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
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code),
    FOREIGN KEY (shipping_warehouse_id) REFERENCES factory_warehouse(warehouse_id),
    UNIQUE (rspu_id, factory_code)
);
CREATE INDEX IF NOT EXISTS idx_rspu_factory_mapping_rspu ON rspu_factory_mapping(rspu_id, status);
CREATE INDEX IF NOT EXISTS idx_rspu_factory_mapping_factory ON rspu_factory_mapping(factory_code, status);
CREATE INDEX IF NOT EXISTS idx_rspu_factory_mapping_warehouse ON rspu_factory_mapping(shipping_warehouse_id);

-- 工厂交期规则表（V2 并入）
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
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code)
);
CREATE INDEX IF NOT EXISTS idx_lead_time_rule_factory ON factory_lead_time_rule(factory_code, status);
-- 通配规则唯一约束（V43）：category_code/material_grade_code 可空（NULL=通配），
-- NULLS NOT DISTINCT（PG 15+）让 NULL 参与判重，替代原行内 UNIQUE（NULL 互不相等导致通配规则可重复）
CREATE UNIQUE INDEX IF NOT EXISTS uk_lead_time_rule_match_v2
    ON factory_lead_time_rule (factory_code, category_code, material_grade_code, process_type)
    NULLS NOT DISTINCT;

-- 工厂产能评估历史表（V2 并入）
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
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code)
);
CREATE INDEX IF NOT EXISTS idx_assessment_factory ON factory_capacity_assessment(factory_code, assessment_period);
CREATE INDEX IF NOT EXISTS idx_assessment_period ON factory_capacity_assessment(assessment_period);

-- 工厂仓库索引
CREATE INDEX IF NOT EXISTS idx_factory_warehouse_factory ON factory_warehouse(factory_code, status);

-- 工厂产能索引
CREATE INDEX IF NOT EXISTS idx_capacity_variant ON factory_variant_capacity(variant_id);
-- idx_capacity_factory 已于 V43 删除：PK (factory_code, variant_id) 左前缀，冗余

-- 工厂产品能力档案（用于全产品库去重）
CREATE TABLE IF NOT EXISTS factory_product_capability (
    id BIGSERIAL PRIMARY KEY,
    factory_code VARCHAR(16) NOT NULL,
    category_code VARCHAR(16),
    style_code VARCHAR(16),
    material_code VARCHAR(8),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    UNIQUE (factory_code, category_code, style_code, material_code),
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code)
);
-- idx_factory_capability_factory 已于 V43 删除：UNIQUE(factory_code, ...) 左前缀，冗余
CREATE INDEX IF NOT EXISTS idx_factory_capability_keys ON factory_product_capability(category_code, style_code, material_code);

