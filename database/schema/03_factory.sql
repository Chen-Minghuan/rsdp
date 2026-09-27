-- ============================================================
-- RSDP 基线 DDL · 03 工厂域（03_factory.sql）
-- 包含表：factory_master, factory_level_capability, factory_warehouse, rspu_factory_mapping, factory_lead_time_rule, factory_product_capability
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
-- 主等级唯一：每工厂最多一条 is_primary = true（V15）
CREATE UNIQUE INDEX IF NOT EXISTS uk_factory_level_capability_primary
    ON factory_level_capability(factory_code) WHERE is_primary = true;
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
-- 组合候选键（V15）：支撑 rspu_factory_mapping / rsku_supply 归属一致组合外键
CREATE UNIQUE INDEX IF NOT EXISTS uk_factory_warehouse_factory_warehouse
    ON factory_warehouse(factory_code, warehouse_id);
-- 默认仓库唯一：每工厂最多一条 is_default = true（V15）
CREATE UNIQUE INDEX IF NOT EXISTS uk_factory_warehouse_default
    ON factory_warehouse(factory_code) WHERE is_default = true;

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
    -- 归属一致（V15）：工厂不得使用他厂仓库
    CONSTRAINT fk_rspu_factory_mapping_factory_warehouse
        FOREIGN KEY (factory_code, shipping_warehouse_id)
        REFERENCES factory_warehouse(factory_code, warehouse_id),
    UNIQUE (rspu_id, factory_code)
);
CREATE INDEX IF NOT EXISTS idx_rspu_factory_mapping_rspu ON rspu_factory_mapping(rspu_id, status);
CREATE INDEX IF NOT EXISTS idx_rspu_factory_mapping_factory ON rspu_factory_mapping(factory_code, status);
CREATE INDEX IF NOT EXISTS idx_rspu_factory_mapping_warehouse ON rspu_factory_mapping(shipping_warehouse_id);
-- 主供唯一：每 RSPU 最多一条 is_primary = true（V7 并入；应用层 clearOtherPrimary 之外的 DB 兜底）
CREATE UNIQUE INDEX IF NOT EXISTS uk_rspu_factory_mapping_primary ON rspu_factory_mapping(rspu_id) WHERE is_primary = true;

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

-- 工厂仓库索引
CREATE INDEX IF NOT EXISTS idx_factory_warehouse_factory ON factory_warehouse(factory_code, status);

-- 工厂产能索引
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
    -- 通配语义列 NULL 参与判重（NULLS NOT DISTINCT，PG15+，与 lead_time_rule 同口径）；
    -- 空串视为非法——统一用 NULL 表达通配（V15）
    CONSTRAINT uk_factory_product_capability_match
        UNIQUE NULLS NOT DISTINCT (factory_code, category_code, style_code, material_code),
    CONSTRAINT ck_factory_capability_no_blank_code
        CHECK ((category_code IS NULL OR category_code <> '')
           AND (style_code IS NULL OR style_code <> '')
           AND (material_code IS NULL OR material_code <> '')),
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code)
);
-- idx_factory_capability_factory 已于 V43 删除：UNIQUE(factory_code, ...) 左前缀，冗余
CREATE INDEX IF NOT EXISTS idx_factory_capability_keys ON factory_product_capability(category_code, style_code, material_code);

