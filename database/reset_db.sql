-- RSDP 数据库重置脚本（PostgreSQL）
-- 用途：清空当前 rsdp 数据库并重新初始化表结构 + 种子数据
-- 执行方式：在 IDEA 数据库插件的 rsdp@localhost Console 中全选执行

-- 环境守卫：防止误在其他数据库执行
DO $$
BEGIN
    IF current_database() != 'rsdp' THEN
        RAISE EXCEPTION '此脚本只能在 rsdp 数据库中执行，当前数据库: %', current_database();
    END IF;
END $$;

-- =================== 1. 清理旧表 ===================
DROP TABLE IF EXISTS matching_feedback CASCADE;
DROP TABLE IF EXISTS product_style_match CASCADE;
DROP TABLE IF EXISTS style_element CASCADE;
DROP TABLE IF EXISTS style_matching_formula CASCADE;
DROP TABLE IF EXISTS style_case CASCADE;
DROP TABLE IF EXISTS ai_recognition CASCADE;
DROP TABLE IF EXISTS image_assets CASCADE;
DROP TABLE IF EXISTS price_history CASCADE;
DROP TABLE IF EXISTS rsku_supply CASCADE;
DROP TABLE IF EXISTS rspu_price_column_mapping CASCADE;
DROP TABLE IF EXISTS excel_import_price_column CASCADE;
DROP TABLE IF EXISTS excel_import_row CASCADE;
DROP TABLE IF EXISTS factory_lead_time_rule CASCADE;
DROP TABLE IF EXISTS rspu_factory_mapping CASCADE;
DROP TABLE IF EXISTS factory_capacity_assessment CASCADE;
DROP TABLE IF EXISTS factory_variant_capacity CASCADE;
DROP TABLE IF EXISTS factory_warehouse CASCADE;
DROP TABLE IF EXISTS factory_level_capability CASCADE;
DROP TABLE IF EXISTS variant_code_counter CASCADE;
DROP TABLE IF EXISTS rspu_variant CASCADE;
DROP TABLE IF EXISTS rspu_relation CASCADE;
DROP TABLE IF EXISTS factory_master CASCADE;
DROP TABLE IF EXISTS rspu_scene CASCADE;
DROP TABLE IF EXISTS rspu_code_counter CASCADE;
DROP TABLE IF EXISTS rsku_code_counter CASCADE;
DROP TABLE IF EXISTS rspu_style CASCADE;
DROP TABLE IF EXISTS rspu_master CASCADE;
DROP TABLE IF EXISTS excel_import_batch CASCADE;
DROP TABLE IF EXISTS async_task CASCADE;
DROP TABLE IF EXISTS audit_log CASCADE;
DROP TABLE IF EXISTS user_operator CASCADE;
DROP TABLE IF EXISTS category_dict CASCADE;
DROP TABLE IF EXISTS dict_alias CASCADE;
DROP TABLE IF EXISTS dict_unresolved_value CASCADE;
DROP TABLE IF EXISTS six_dim_schema CASCADE;
DROP TABLE IF EXISTS scheme_item CASCADE;
DROP TABLE IF EXISTS scheme CASCADE;
DROP TABLE IF EXISTS scheme_candidate CASCADE;
DROP TABLE IF EXISTS recommendation_score_config CASCADE;
DROP TABLE IF EXISTS designer_profile CASCADE;
DROP TABLE IF EXISTS product_collection_item CASCADE;
DROP TABLE IF EXISTS product_collection CASCADE;
DROP TABLE IF EXISTS user_favorite CASCADE;
DROP TABLE IF EXISTS favorite_folder CASCADE;
DROP TABLE IF EXISTS template_tag CASCADE;
DROP TABLE IF EXISTS platform_banner CASCADE;
DROP TABLE IF EXISTS platform_case CASCADE;
DROP TABLE IF EXISTS platform_content CASCADE;
DROP TABLE IF EXISTS platform_custom_dict CASCADE;
DROP TABLE IF EXISTS platform_customized CASCADE;
DROP TABLE IF EXISTS project CASCADE;
DROP TABLE IF EXISTS design_order_item CASCADE;
DROP TABLE IF EXISTS design_order CASCADE;
DROP TABLE IF EXISTS order_no_counter CASCADE;
DROP TABLE IF EXISTS sys_config CASCADE;
DROP TABLE IF EXISTS factory_product_capability CASCADE;
DROP TABLE IF EXISTS sys_user_factory CASCADE;
DROP TABLE IF EXISTS sys_user_role CASCADE;
DROP TABLE IF EXISTS sys_role_permission CASCADE;
DROP TABLE IF EXISTS sys_permission CASCADE;
DROP TABLE IF EXISTS sys_role CASCADE;
DROP TABLE IF EXISTS invite_record CASCADE;
DROP TABLE IF EXISTS member_group CASCADE;
DROP TABLE IF EXISTS company CASCADE;
DROP TABLE IF EXISTS sys_user CASCADE;

-- =================== 2. 创建字典表 ===================
CREATE TABLE IF NOT EXISTS category_dict (
    dict_type VARCHAR(32) NOT NULL,
    dict_code VARCHAR(32) NOT NULL,
    dict_name VARCHAR(64) NOT NULL,
    dict_name_en VARCHAR(64),
    parent_code VARCHAR(32),
    sort_order INTEGER,
    status VARCHAR(16) DEFAULT 'active',
    aliases TEXT,
    remark TEXT,
    PRIMARY KEY (dict_type, dict_code)
);

-- 六维标签维度定义表（V30）：品类 × A-F 维度键 → 标签/说明，替代前后端双写
CREATE TABLE IF NOT EXISTS six_dim_schema (
    id            BIGSERIAL PRIMARY KEY,
    category_code VARCHAR(16)  NOT NULL,
    dim_key       VARCHAR(4)   NOT NULL,
    label         VARCHAR(64)  NOT NULL,
    description   VARCHAR(255) NOT NULL DEFAULT '',
    sort_order    INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_six_dim_schema UNIQUE (category_code, dim_key)
);

-- =================== 3. 创建业务表 ===================

-- RSPU 设计原型主表（款式概念）
CREATE TABLE IF NOT EXISTS rspu_master (
    rspu_id VARCHAR(64) PRIMARY KEY,
    external_code VARCHAR(64),                       -- 外部编码（Excel/ERP 导入用）
    rspu_code VARCHAR(32) UNIQUE,                    -- 业务编码，如 FS-MC-001-M
    category_code VARCHAR(16) NOT NULL,
    category_path TEXT NOT NULL,
    positioning_label VARCHAR(64) NOT NULL,
    product_name VARCHAR(256),
    description TEXT,                                -- 长文本描述原文（Excel 导入材质解析/功能配置等，V18 并入）
    retail_price NUMERIC(14,2),                      -- 零售参考价（销售价/含税价，不加密，V18 并入）
    six_dim_tags JSONB,
    style_vector JSONB,
    color_primary_name VARCHAR(64),
    color_primary_hsv JSONB,
    color_secondary VARCHAR(64),
    material_tags JSONB,
    fabric_tags JSONB DEFAULT '[]',
    scene_tags JSONB,
    reference_price_band VARCHAR(16),
    product_level VARCHAR(16),                     -- 产品档次：经济型/中端/高端/轻奢/豪华
    budget_range JSONB,
    warranty_years INTEGER,
    key_specs JSONB,
    status VARCHAR(16) DEFAULT 'active',
    review_status VARCHAR(16) DEFAULT '待复核',
    review_comment TEXT,                           -- 复核备注/说明
    aesthetics_confidence VARCHAR(16),
    source_agent_version VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP
);

-- RSPU 多风格关联表
CREATE TABLE IF NOT EXISTS rspu_style (
    rspu_id VARCHAR(64) NOT NULL,
    dict_type VARCHAR(32) NOT NULL DEFAULT 'style',
    style_code VARCHAR(32) NOT NULL,
    is_primary BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (rspu_id, style_code),
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (dict_type, style_code) REFERENCES category_dict(dict_type, dict_code)
);

-- RSPU 编码流水计数器
CREATE TABLE IF NOT EXISTS rspu_code_counter (
    category_code VARCHAR(16) NOT NULL,
    style_code VARCHAR(16) NOT NULL,
    sequence_value BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (category_code, style_code)
);

-- RSKU 编码流水计数器
CREATE TABLE IF NOT EXISTS rsku_code_counter (
    rspu_code VARCHAR(32) NOT NULL,
    factory_code VARCHAR(16) NOT NULL,
    material_code VARCHAR(16) NOT NULL,
    sequence_value BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (rspu_code, factory_code, material_code)
);

-- RSPU 多场景关联表
CREATE TABLE IF NOT EXISTS rspu_scene (
    rspu_id VARCHAR(64) NOT NULL,
    dict_type VARCHAR(32) NOT NULL DEFAULT 'scene',
    scene_code VARCHAR(32) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (rspu_id, scene_code),
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (dict_type, scene_code) REFERENCES category_dict(dict_type, dict_code)
);

-- RSPU 变体表
-- 建议变体编码使用无业务含义顺序号，如 {rspu_id}-V001，避免尺寸/材质变化导致编码变更
-- 可读名称存入 display_name 字段，尺寸/材质等业务属性存入对应字段
CREATE TABLE IF NOT EXISTS rspu_variant (
    variant_id VARCHAR(64) PRIMARY KEY,            -- 建议格式：{rspu_id}-V001/V002，不嵌入尺寸/材质
    rspu_id VARCHAR(64) NOT NULL,
    display_name VARCHAR(128),                     -- 变体显示名称，如"兰卡沙发 2450mm A级布"
    variant_code VARCHAR(32),
    size_code VARCHAR(32),
    size_text VARCHAR(64),
    dimensions JSONB,
    color_code VARCHAR(32),
    color_text VARCHAR(64),
    material_code VARCHAR(32),
    material_text VARCHAR(128),
    material_mix JSONB,
    reference_price_band VARCHAR(16),
    product_level VARCHAR(8),
    status VARCHAR(16) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id)
);

-- 变体编码流水计数器
CREATE TABLE IF NOT EXISTS variant_code_counter (
    rspu_id VARCHAR(64) NOT NULL,
    sequence_value BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (rspu_id),
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id)
);

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
    capacity_tier_score DECIMAL(5,2),                -- 最新综合评分
    last_assessment_period VARCHAR(16),              -- 最近评估周期
    last_assessment_date DATE,                       -- 最近评估日期
    import_batch_source VARCHAR(32),                 -- 首次来源导入批次
    source_type VARCHAR(16) DEFAULT 'manual',        -- manual/excel_import/api_sync
    status VARCHAR(16) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP
);

COMMENT ON COLUMN factory_master.factory_level IS '工厂层级: S级战略厂/A级核心厂/B级合作厂/C级备选厂，由 capacity_tier_score 自动计算或手动指定';

-- 工厂能力等级表
CREATE TABLE IF NOT EXISTS factory_level_capability (
    id BIGSERIAL PRIMARY KEY,
    factory_code VARCHAR(16) NOT NULL,
    level_code VARCHAR(8) NOT NULL,
    is_primary BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code),
    UNIQUE (factory_code, level_code)
);
CREATE INDEX IF NOT EXISTS idx_factory_level_capability_factory ON factory_level_capability(factory_code);

-- 工厂仓库表
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
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code)
);

-- 工厂-变体产能表
CREATE TABLE IF NOT EXISTS factory_variant_capacity (
    factory_code VARCHAR(16) NOT NULL,
    variant_id VARCHAR(64) NOT NULL,
    monthly_capacity INTEGER,
    current_booked INTEGER DEFAULT 0,
    max_batch_size INTEGER,
    capacity_unit VARCHAR(16) DEFAULT '件',
    lead_time_batch_days INTEGER,
    notes TEXT,
    updated_at TIMESTAMP,
    PRIMARY KEY (factory_code, variant_id),
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code),
    FOREIGN KEY (variant_id) REFERENCES rspu_variant(variant_id)
);

-- RSPU-工厂多对多关联表（V2 新增）
CREATE TABLE IF NOT EXISTS rspu_factory_mapping (
    mapping_id BIGSERIAL PRIMARY KEY,
    rspu_id VARCHAR(64) NOT NULL,
    factory_code VARCHAR(16) NOT NULL,
    is_primary BOOLEAN DEFAULT FALSE,
    shipping_warehouse_id VARCHAR(64),
    moq INTEGER,
    base_lead_time_days INTEGER,
    status VARCHAR(16) DEFAULT 'active',
    notes TEXT,
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

-- 工厂交期规则表（V2 新增）
CREATE TABLE IF NOT EXISTS factory_lead_time_rule (
    rule_id BIGSERIAL PRIMARY KEY,
    factory_code VARCHAR(16) NOT NULL,
    category_code VARCHAR(16),
    material_grade_code VARCHAR(32),
    process_type VARCHAR(32) DEFAULT 'standard',
    base_days INTEGER NOT NULL DEFAULT 30,
    batch_size_threshold INTEGER,
    batch_extra_days INTEGER DEFAULT 0,
    material_switch_extra_days INTEGER DEFAULT 0,
    priority INTEGER DEFAULT 100,
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

-- 工厂产能评估历史表（V2 新增）
CREATE TABLE IF NOT EXISTS factory_capacity_assessment (
    assessment_id BIGSERIAL PRIMARY KEY,
    factory_code VARCHAR(16) NOT NULL,
    assessment_period VARCHAR(16) NOT NULL,
    score_capacity_scale INTEGER,
    score_on_time_rate INTEGER,
    score_quality INTEGER,
    score_equipment INTEGER,
    score_staffing INTEGER,
    score_flexibility INTEGER,
    tier_score DECIMAL(5,2) NOT NULL,
    calculated_tier VARCHAR(8),
    monthly_capacity_avg INTEGER,
    on_time_rate DECIMAL(5,4),
    quality_return_rate DECIMAL(5,4),
    active_rspu_count INTEGER,
    active_rsku_count INTEGER,
    assessed_by VARCHAR(64),
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code)
);
CREATE INDEX IF NOT EXISTS idx_assessment_factory ON factory_capacity_assessment(factory_code, assessment_period);
CREATE INDEX IF NOT EXISTS idx_assessment_period ON factory_capacity_assessment(assessment_period);

-- RSKU 供应单元子表
CREATE TABLE IF NOT EXISTS rsku_supply (
    rsku_id VARCHAR(64) PRIMARY KEY,
    rsku_code VARCHAR(64) UNIQUE,                    -- 业务编码，如 FS-MC-001-M-A004-PE-001
    rspu_id VARCHAR(64) NOT NULL,
    variant_id VARCHAR(64),
    factory_code VARCHAR(16) NOT NULL,
    factory_sku VARCHAR(64),
    factory_price TEXT,
    price_band VARCHAR(16),
    product_level VARCHAR(8),
    material_code VARCHAR(8),
    material_description TEXT,
    lead_time_days INTEGER,
    moq INTEGER,
    warranty_years INTEGER,
    shipping_from VARCHAR(128),
    shipping_warehouse_id VARCHAR(64),
    structure_strength_rating VARCHAR(32),
    flame_retardant_capability VARCHAR(32),
    factory_photo_path TEXT,
    factory_credit_score INTEGER,
    on_time_rate DECIMAL(5, 4),
    quality_return_rate DECIMAL(5, 4),
    diff_notes TEXT,
    quote_confidence VARCHAR(16),
    review_status VARCHAR(16) DEFAULT '待复核',
    price_updated DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (variant_id) REFERENCES rspu_variant(variant_id),
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code),
    FOREIGN KEY (shipping_warehouse_id) REFERENCES factory_warehouse(warehouse_id)
);

-- 价格历史表
CREATE TABLE IF NOT EXISTS price_history (
    history_id SERIAL PRIMARY KEY,
    rsku_id VARCHAR(64) NOT NULL,
    old_price TEXT,
    new_price TEXT,
    changed_by VARCHAR(64),
    change_reason TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (rsku_id) REFERENCES rsku_supply(rsku_id)
);

-- 图片资源表
CREATE TABLE IF NOT EXISTS image_assets (
    image_id VARCHAR(64) PRIMARY KEY,
    rspu_id VARCHAR(64),
    variant_id VARCHAR(64),
    rsku_id VARCHAR(64),
    image_type VARCHAR(32) NOT NULL,
    storage_path TEXT NOT NULL,
    storage_url TEXT,
    file_size BIGINT,
    width INTEGER,
    height INTEGER,
    format VARCHAR(16),
    is_primary BOOLEAN DEFAULT FALSE,
    ai_processed BOOLEAN DEFAULT FALSE,
    quality_score DECIMAL(5, 4),
    uploaded_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (variant_id) REFERENCES rspu_variant(variant_id),
    FOREIGN KEY (rsku_id) REFERENCES rsku_supply(rsku_id)
);

-- RSPU 关系表
CREATE TABLE IF NOT EXISTS rspu_relation (
    relation_id VARCHAR(64) PRIMARY KEY,
    anchor_rspu_id VARCHAR(64) NOT NULL,
    related_rspu_id VARCHAR(64) NOT NULL,
    relation_type VARCHAR(16) NOT NULL,
    reason TEXT,
    sort_order INTEGER DEFAULT 0,
    status VARCHAR(16) DEFAULT 'active',
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    FOREIGN KEY (anchor_rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (related_rspu_id) REFERENCES rspu_master(rspu_id)
);
CREATE INDEX IF NOT EXISTS idx_rspu_relation_anchor ON rspu_relation(anchor_rspu_id, relation_type, status);
CREATE INDEX IF NOT EXISTS idx_rspu_relation_related ON rspu_relation(related_rspu_id, relation_type, status);

-- AI 识别记录表
CREATE TABLE IF NOT EXISTS ai_recognition (
    recognition_id VARCHAR(64) PRIMARY KEY,
    image_id VARCHAR(64),
    rspu_id VARCHAR(64),
    task_id VARCHAR(64),
    model_name VARCHAR(64),
    recognition_type VARCHAR(16),
    endpoint TEXT,
    input_data JSONB,
    output_data JSONB,
    parsed_style VARCHAR(64),
    parsed_six_dim JSONB,
    parsed_color_hsv JSONB,
    parsed_scene_tags JSONB,
    parsed_ocr JSONB,
    confidence VARCHAR(16),
    processing_time_ms INTEGER,
    status VARCHAR(16),
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (image_id) REFERENCES image_assets(image_id),
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id)
);

-- 搭配方案主表
CREATE TABLE IF NOT EXISTS scheme (
    scheme_id VARCHAR(64) PRIMARY KEY,
    scheme_name VARCHAR(128) NOT NULL,
    room_type VARCHAR(32),
    budget_limit DECIMAL(18, 2),
    total_price DECIMAL(18, 2),
    factory_count INTEGER,
    max_lead_time_days INTEGER,
    item_count INTEGER,
    status VARCHAR(16) DEFAULT 'active',
    project_id VARCHAR(64),
    is_template BOOLEAN NOT NULL DEFAULT false,
    template_tags TEXT,
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_scheme_created_by ON scheme(created_by, status);
CREATE INDEX IF NOT EXISTS idx_scheme_project ON scheme(project_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_scheme_template ON scheme(is_template) WHERE is_template = true AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_scheme_name_user_active
    ON scheme(scheme_name, created_by)
    WHERE project_id IS NULL AND status = 'active' AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_scheme_name_project_active
    ON scheme(scheme_name, project_id)
    WHERE project_id IS NOT NULL AND status = 'active' AND deleted_at IS NULL;

-- 搭配方案项表
CREATE TABLE IF NOT EXISTS scheme_item (
    scheme_item_id BIGSERIAL PRIMARY KEY,
    scheme_id VARCHAR(64) NOT NULL,
    rspu_id VARCHAR(64) NOT NULL,
    rsku_id VARCHAR(64) NOT NULL,
    factory_code VARCHAR(16) NOT NULL,
    factory_price TEXT,
    lead_time_days INTEGER,
    moq INTEGER,
    quantity INTEGER DEFAULT 1,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    FOREIGN KEY (scheme_id) REFERENCES scheme(scheme_id),
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (rsku_id) REFERENCES rsku_supply(rsku_id),
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code)
);
CREATE INDEX IF NOT EXISTS idx_scheme_item_scheme ON scheme_item(scheme_id);
CREATE INDEX IF NOT EXISTS idx_scheme_item_rspu ON scheme_item(rspu_id);

-- 异步任务表
CREATE TABLE IF NOT EXISTS async_task (
    task_id VARCHAR(64) PRIMARY KEY,
    task_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) DEFAULT 'pending',
    progress INTEGER DEFAULT 0,
    input_data JSONB,
    result_data JSONB,
    error_message TEXT,
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

-- Excel AI 辅助导入批次表
CREATE TABLE IF NOT EXISTS excel_import_batch (
    batch_id VARCHAR(64) PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    storage_path VARCHAR(512),                      -- 原始 Excel 文件存储路径
    status VARCHAR(20) DEFAULT 'pending',
    total_rows INT DEFAULT 0,
    success_count INT DEFAULT 0,
    failed_count INT DEFAULT 0,
    column_mapping JSONB,
    preview_rows JSONB,
    price_columns JSONB,
    failures JSONB,
    factory_code VARCHAR(16),
    factory_name VARCHAR(128),
    shipping_warehouse_id VARCHAR(64),
    shipping_from VARCHAR(128),
    default_lead_time_days INTEGER,
    default_moq INTEGER,
    category_hint VARCHAR(16),
    header_row_count INTEGER DEFAULT 2,
    data_start_row INTEGER DEFAULT 3,
    import_note TEXT,
    sheet_index INT NOT NULL DEFAULT 0,                 -- 多 Sheet 文件批次解析的工作表索引（V18 并入）
    processed_at TIMESTAMP,
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
    -- 外键在 sys_user 表创建后通过 ALTER TABLE 添加
);
CREATE INDEX IF NOT EXISTS idx_excel_import_batch_status ON excel_import_batch(status);
CREATE INDEX IF NOT EXISTS idx_excel_import_batch_created_by ON excel_import_batch(created_by);
CREATE INDEX IF NOT EXISTS idx_excel_import_batch_factory ON excel_import_batch(factory_code);

-- Excel 行级导入记录表（V2 新增）
CREATE TABLE IF NOT EXISTS excel_import_row (
    row_id BIGSERIAL PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
    excel_row_number INTEGER NOT NULL,
    row_type VARCHAR(32) NOT NULL,
    parent_row_id BIGINT,
    raw_data JSONB NOT NULL,
    mapped_fields JSONB,
    selected_price_columns JSONB,
    status VARCHAR(16) DEFAULT 'pending',
    processing_stage VARCHAR(32),
    generated_rspu_id VARCHAR(64),
    generated_variant_id VARCHAR(64),
    generated_rsku_ids JSONB,
    failure_reason TEXT,
    failure_stage VARCHAR(32),
    extracted_image_count INTEGER DEFAULT 0,
    image_asset_ids JSONB,
    override_image_asset_ids JSONB,
    ai_task_id VARCHAR(64),
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

-- RSPU 价格列映射记录表（V2 新增）
CREATE TABLE IF NOT EXISTS rspu_price_column_mapping (
    mapping_id BIGSERIAL PRIMARY KEY,
    rspu_id VARCHAR(64) NOT NULL,
    batch_id VARCHAR(64) NOT NULL,
    price_column_name VARCHAR(64) NOT NULL,
    material_grade_code VARCHAR(32),
    material_code VARCHAR(32),
    factory_price DECIMAL(18,2),
    factory_code VARCHAR(16),
    is_selected BOOLEAN DEFAULT TRUE,
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (batch_id) REFERENCES excel_import_batch(batch_id),
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code)
);
CREATE INDEX IF NOT EXISTS idx_price_col_mapping_rspu ON rspu_price_column_mapping(rspu_id);
CREATE INDEX IF NOT EXISTS idx_price_col_mapping_batch ON rspu_price_column_mapping(batch_id);

-- 批次价格列识别表（V2 新增）
CREATE TABLE IF NOT EXISTS excel_import_price_column (
    column_id BIGSERIAL PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
    excel_column_letter VARCHAR(8) NOT NULL,
    column_header_name VARCHAR(128) NOT NULL,
    raw_header_name VARCHAR(256),
    suggested_material_grade VARCHAR(32),
    is_selected BOOLEAN DEFAULT TRUE,
    sample_values JSONB,
    data_type VARCHAR(16),
    value_count INTEGER,
    min_value DECIMAL(18,2),
    max_value DECIMAL(18,2),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (batch_id) REFERENCES excel_import_batch(batch_id)
);
CREATE INDEX IF NOT EXISTS idx_import_price_col_batch ON excel_import_price_column(batch_id);

-- 审计日志表
CREATE TABLE IF NOT EXISTS audit_log (
    id SERIAL PRIMARY KEY,
    table_name VARCHAR(64) NOT NULL,
    record_id VARCHAR(64) NOT NULL,
    action VARCHAR(16) NOT NULL,
    old_value JSONB,
    new_value JSONB,
    operator VARCHAR(64),
    ip_address VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 操作员表
CREATE TABLE IF NOT EXISTS user_operator (
    user_id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    real_name VARCHAR(64),
    role VARCHAR(32),
    status VARCHAR(16) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

-- 风格数据库 Skill 表

-- 案例库：成功/失败的设计案例
CREATE TABLE IF NOT EXISTS style_case (
    case_id VARCHAR(64) PRIMARY KEY,
    case_name VARCHAR(128) NOT NULL,
    dict_type VARCHAR(32) NOT NULL DEFAULT 'style',
    style_code VARCHAR(32) NOT NULL,
    room_type VARCHAR(32),
    is_success BOOLEAN NOT NULL DEFAULT TRUE,
    source_type VARCHAR(32),
    source_url TEXT,
    description TEXT,
    image_url TEXT,
    ai_raw_output JSONB,
    negative_lesson TEXT,
    review_status VARCHAR(16) DEFAULT '待复核',
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    FOREIGN KEY (dict_type, style_code) REFERENCES category_dict(dict_type, dict_code)
);

-- 元素库：从案例中拆解出的标准化元素
CREATE TABLE IF NOT EXISTS style_element (
    element_id VARCHAR(64) PRIMARY KEY,
    case_id VARCHAR(64) NOT NULL,
    element_type VARCHAR(32) NOT NULL,
    element_value VARCHAR(128) NOT NULL,
    normalized_code VARCHAR(64),
    is_primary BOOLEAN DEFAULT FALSE,
    confidence VARCHAR(16),
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (case_id) REFERENCES style_case(case_id)
);

-- 搭配公式库：可解释的搭配规则
CREATE TABLE IF NOT EXISTS style_matching_formula (
    formula_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(256) NOT NULL,
    dict_type VARCHAR(32) NOT NULL DEFAULT 'style',
    style_code VARCHAR(32) NOT NULL,
    room_type VARCHAR(32),
    priority INTEGER DEFAULT 0,
    formula_json JSONB NOT NULL,
    source_case_ids JSONB,
    negative_case_ids JSONB,
    success_count INTEGER DEFAULT 0,
    fail_count INTEGER DEFAULT 0,
    status VARCHAR(16) DEFAULT 'active',
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    FOREIGN KEY (dict_type, style_code) REFERENCES category_dict(dict_type, dict_code)
);

-- 产品-风格匹配结果：产品录入后自动计算
CREATE TABLE IF NOT EXISTS product_style_match (
    match_id SERIAL PRIMARY KEY,
    rspu_id VARCHAR(64) NOT NULL,
    dict_type VARCHAR(32) NOT NULL DEFAULT 'style',
    style_code VARCHAR(32) NOT NULL,
    element_match JSONB,
    formula_scores JSONB,
    overall_score DECIMAL(5,4),
    confidence VARCHAR(16),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    UNIQUE (rspu_id, style_code),
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (dict_type, style_code) REFERENCES category_dict(dict_type, dict_code)
);

-- 推荐反馈：用于后续优化公式
CREATE TABLE IF NOT EXISTS matching_feedback (
    feedback_id SERIAL PRIMARY KEY,
    rspu_id VARCHAR(64) NOT NULL,
    recommended_rspu_id VARCHAR(64) NOT NULL,
    formula_id VARCHAR(64),
    score DECIMAL(5,4),
    feedback VARCHAR(16),
    reason TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (recommended_rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (formula_id) REFERENCES style_matching_formula(formula_id)
);

-- =================== 4. 创建索引 ===================
CREATE INDEX IF NOT EXISTS idx_rspu_category ON rspu_master(category_code, status);
CREATE INDEX IF NOT EXISTS idx_rspu_positioning ON rspu_master(positioning_label, category_code);
CREATE INDEX IF NOT EXISTS idx_rspu_review ON rspu_master(review_status);
CREATE INDEX IF NOT EXISTS idx_rspu_meta ON rspu_master(category_code, positioning_label, status) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_rspu_external_code ON rspu_master(external_code) WHERE deleted_at IS NULL;
-- 外部编码部分唯一索引（V17 并入）：防并发导入产生重复外部编码，仅约束未软删除且非空记录
CREATE UNIQUE INDEX IF NOT EXISTS uk_rspu_external_code ON rspu_master(external_code) WHERE deleted_at IS NULL AND external_code IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_rspu_style ON rspu_style(style_code);
CREATE INDEX IF NOT EXISTS idx_rspu_scene ON rspu_scene(scene_code);
CREATE INDEX IF NOT EXISTS idx_rspu_style_rspu ON rspu_style(rspu_id, style_code);

CREATE INDEX IF NOT EXISTS idx_variant_rspu ON rspu_variant(rspu_id, status);
CREATE INDEX IF NOT EXISTS idx_variant_color ON rspu_variant(color_code);
CREATE INDEX IF NOT EXISTS idx_variant_material ON rspu_variant(material_code);
CREATE INDEX IF NOT EXISTS idx_variant_size ON rspu_variant(size_code);

-- 变体属性组合唯一约束（防并发导入产生重复变体；NULL 归一为空串；仅约束未软删除记录）
-- V19 起改为"码或原文"语义：COALESCE(code, text, '')，有码按码、无码按工厂原文判重
CREATE UNIQUE INDEX IF NOT EXISTS uk_variant_attrs
    ON rspu_variant (
        rspu_id,
        COALESCE(size_code, size_text, ''),
        COALESCE(color_code, color_text, ''),
        COALESCE(material_code, material_text, '')
    )
    WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_rspu_six_dim_gin ON rspu_master USING GIN (six_dim_tags jsonb_path_ops);
CREATE INDEX IF NOT EXISTS idx_variant_dimensions_gin ON rspu_variant USING GIN (dimensions jsonb_path_ops);

CREATE INDEX IF NOT EXISTS idx_factory_warehouse_factory ON factory_warehouse(factory_code, status);
CREATE INDEX IF NOT EXISTS idx_capacity_variant ON factory_variant_capacity(variant_id);
CREATE INDEX IF NOT EXISTS idx_capacity_factory ON factory_variant_capacity(factory_code);

CREATE INDEX IF NOT EXISTS idx_rsku_rspu ON rsku_supply(rspu_id);
CREATE INDEX IF NOT EXISTS idx_rsku_variant ON rsku_supply(variant_id);
CREATE INDEX IF NOT EXISTS idx_rsku_factory ON rsku_supply(factory_code);
CREATE INDEX IF NOT EXISTS idx_rsku_warehouse ON rsku_supply(shipping_warehouse_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_rsku_unique ON rsku_supply(rspu_id, variant_id, factory_code) WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_price_history ON price_history(rsku_id, created_at);

CREATE INDEX IF NOT EXISTS idx_image_rspu ON image_assets(rspu_id, image_type);
CREATE INDEX IF NOT EXISTS idx_image_variant ON image_assets(variant_id, image_type);
CREATE INDEX IF NOT EXISTS idx_image_primary ON image_assets(rspu_id, is_primary);
CREATE INDEX IF NOT EXISTS idx_image_rsku ON image_assets(rsku_id);

CREATE INDEX IF NOT EXISTS idx_ai_image ON ai_recognition(image_id, recognition_type);
CREATE INDEX IF NOT EXISTS idx_ai_rspu ON ai_recognition(rspu_id, created_at);

CREATE INDEX IF NOT EXISTS idx_audit_record ON audit_log(table_name, record_id, created_at);
CREATE INDEX IF NOT EXISTS idx_task_status ON async_task(status, created_at);

-- 风格数据库索引
CREATE INDEX IF NOT EXISTS idx_style_case_style ON style_case(style_code, is_success);
CREATE INDEX IF NOT EXISTS idx_style_case_room ON style_case(room_type, is_success);
CREATE INDEX IF NOT EXISTS idx_style_element_case ON style_element(case_id);
CREATE INDEX IF NOT EXISTS idx_style_element_type ON style_element(element_type, normalized_code);
CREATE INDEX IF NOT EXISTS idx_formula_style_room ON style_matching_formula(style_code, room_type, status);
CREATE INDEX IF NOT EXISTS idx_product_match_rspu ON product_style_match(rspu_id);
CREATE INDEX IF NOT EXISTS idx_product_match_score ON product_style_match(overall_score DESC);

-- 系统用户表
CREATE TABLE IF NOT EXISTS sys_user (
    user_id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    nickname VARCHAR(64),
    company_name VARCHAR(128),
    group_name VARCHAR(64),
    status VARCHAR(16) DEFAULT 'active',
    token_version INT DEFAULT 0,
    view_full_catalog BOOLEAN NOT NULL DEFAULT false,
    company_id VARCHAR(64),
    group_id VARCHAR(64),
    invite_code VARCHAR(16),
    invited_by VARCHAR(64),
    certified_designer BOOLEAN NOT NULL DEFAULT false,
    last_login_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sys_user_username ON sys_user(username);
CREATE UNIQUE INDEX IF NOT EXISTS idx_sys_user_invite_code ON sys_user(invite_code);
CREATE INDEX IF NOT EXISTS idx_sys_user_company ON sys_user(company_id);

-- 补齐 excel_import_batch 外键（该表在 sys_user 之前创建）
ALTER TABLE excel_import_batch
    DROP CONSTRAINT IF EXISTS fk_excel_import_batch_created_by;
ALTER TABLE excel_import_batch
    ADD CONSTRAINT fk_excel_import_batch_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(user_id);

-- 企业表（V13 并入）
CREATE TABLE IF NOT EXISTS company (
    company_id    VARCHAR(64) PRIMARY KEY,
    company_name  VARCHAR(128) NOT NULL,
    logo_image_id VARCHAR(64),
    price_ratio   NUMERIC(5,4) NOT NULL DEFAULT 1,
    owner_id      VARCHAR(64) NOT NULL REFERENCES sys_user(user_id),
    status        VARCHAR(16) NOT NULL DEFAULT 'active',
    deleted_at    TIMESTAMP,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_company_price_ratio CHECK (price_ratio >= 0 AND price_ratio <= 1)
);
CREATE INDEX IF NOT EXISTS idx_company_owner ON company(owner_id);
CREATE INDEX IF NOT EXISTS idx_company_name ON company(company_name) WHERE deleted_at IS NULL;

-- 企业内分组/部门表（V13 并入）
CREATE TABLE IF NOT EXISTS member_group (
    group_id    VARCHAR(64) PRIMARY KEY,
    company_id  VARCHAR(64) NOT NULL REFERENCES company(company_id),
    group_name  VARCHAR(64) NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT true,
    deleted_at  TIMESTAMP,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_member_group_company ON member_group(company_id) WHERE deleted_at IS NULL;

-- 邀请记录表（V13 并入）
CREATE TABLE IF NOT EXISTS invite_record (
    id          BIGSERIAL PRIMARY KEY,
    inviter_id  VARCHAR(64) NOT NULL REFERENCES sys_user(user_id),
    invitee_id  VARCHAR(64) NOT NULL REFERENCES sys_user(user_id),
    invite_code VARCHAR(16) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_invite_record_inviter ON invite_record(inviter_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_invite_record_invitee ON invite_record(invitee_id);

-- 补齐 sys_user 企业/邀请外键（company/member_group 在 sys_user 之后创建，循环引用需后置）
ALTER TABLE sys_user DROP CONSTRAINT IF EXISTS fk_sys_user_company;
ALTER TABLE sys_user
    ADD CONSTRAINT fk_sys_user_company FOREIGN KEY (company_id) REFERENCES company(company_id);
ALTER TABLE sys_user DROP CONSTRAINT IF EXISTS fk_sys_user_group;
ALTER TABLE sys_user
    ADD CONSTRAINT fk_sys_user_group FOREIGN KEY (group_id) REFERENCES member_group(group_id);
ALTER TABLE sys_user DROP CONSTRAINT IF EXISTS fk_sys_user_invited_by;
ALTER TABLE sys_user
    ADD CONSTRAINT fk_sys_user_invited_by FOREIGN KEY (invited_by) REFERENCES sys_user(user_id);

-- 角色表
CREATE TABLE IF NOT EXISTS sys_role (
    role_id BIGSERIAL PRIMARY KEY,
    role_code VARCHAR(32) NOT NULL UNIQUE,
    role_name VARCHAR(64) NOT NULL,
    status VARCHAR(16) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

-- 权限表
CREATE TABLE IF NOT EXISTS sys_permission (
    permission_id BIGSERIAL PRIMARY KEY,
    permission_code VARCHAR(64) NOT NULL UNIQUE,
    permission_name VARCHAR(128) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 角色权限关联表
CREATE TABLE IF NOT EXISTS sys_role_permission (
    id BIGSERIAL PRIMARY KEY,
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (role_id, permission_id),
    FOREIGN KEY (role_id) REFERENCES sys_role(role_id),
    FOREIGN KEY (permission_id) REFERENCES sys_permission(permission_id)
);

-- 用户角色关联表
CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    role_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES sys_user(user_id),
    FOREIGN KEY (role_id) REFERENCES sys_role(role_id)
);

-- 用户工厂关联表（用于厂商业务员数据权限）
CREATE TABLE IF NOT EXISTS sys_user_factory (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    factory_code VARCHAR(16) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, factory_code),
    FOREIGN KEY (user_id) REFERENCES sys_user(user_id),
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code)
);

-- 工厂产品能力档案（用于全产品库去重）
CREATE TABLE IF NOT EXISTS factory_product_capability (
    id BIGSERIAL PRIMARY KEY,
    factory_code VARCHAR(16) NOT NULL,
    category_code VARCHAR(16),
    style_code VARCHAR(16),
    material_code VARCHAR(8),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    UNIQUE (factory_code, category_code, style_code, material_code),
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code)
);
CREATE INDEX IF NOT EXISTS idx_factory_capability_factory ON factory_product_capability(factory_code);
CREATE INDEX IF NOT EXISTS idx_factory_capability_keys ON factory_product_capability(category_code, style_code, material_code);

-- 产品集（管理员维护的主流搭配集合）
CREATE TABLE IF NOT EXISTS product_collection (
    collection_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    collection_code VARCHAR(32) UNIQUE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    category_codes JSONB,
    style_codes JSONB,
    target_segments JSONB,
    is_featured BOOLEAN DEFAULT false,
    sort_order INT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    FOREIGN KEY (created_by) REFERENCES sys_user(user_id)
);
CREATE INDEX IF NOT EXISTS idx_product_collection_status ON product_collection(status);
CREATE INDEX IF NOT EXISTS idx_product_collection_featured ON product_collection(is_featured, sort_order);

-- 产品集与 RSPU 关联
CREATE TABLE IF NOT EXISTS product_collection_item (
    id BIGSERIAL PRIMARY KEY,
    collection_id UUID NOT NULL,
    rspu_id VARCHAR(64) NOT NULL,
    sort_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (collection_id, rspu_id),
    FOREIGN KEY (collection_id) REFERENCES product_collection(collection_id) ON DELETE CASCADE,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id)
);
CREATE INDEX IF NOT EXISTS idx_collection_item_collection ON product_collection_item(collection_id);
CREATE INDEX IF NOT EXISTS idx_collection_item_rspu ON product_collection_item(rspu_id);

-- 设计师画像
CREATE TABLE IF NOT EXISTS designer_profile (
    profile_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id VARCHAR(64) NOT NULL UNIQUE,
    real_name VARCHAR(64),
    avatar_url TEXT,
    specialties JSONB,
    preferred_styles JSONB,
    preferred_categories JSONB,
    price_sensitivity VARCHAR(16),
    location VARCHAR(64),
    company_name VARCHAR(128),
    contact_phone VARCHAR(32),
    bio TEXT,
    default_budget_min DECIMAL(18,2),
    default_budget_max DECIMAL(18,2),
    is_public BOOLEAN DEFAULT false,
    status VARCHAR(16) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES sys_user(user_id)
);
CREATE INDEX IF NOT EXISTS idx_designer_profile_user ON designer_profile(user_id);
CREATE INDEX IF NOT EXISTS idx_designer_profile_status ON designer_profile(status);

-- 推荐打分配置
CREATE TABLE IF NOT EXISTS recommendation_score_config (
    config_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    config_key VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    weights JSONB NOT NULL,
    is_default BOOLEAN DEFAULT false,
    is_active BOOLEAN DEFAULT true,
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    FOREIGN KEY (created_by) REFERENCES sys_user(user_id)
);
CREATE INDEX IF NOT EXISTS idx_recommendation_config_key ON recommendation_score_config(config_key);
CREATE INDEX IF NOT EXISTS idx_recommendation_config_default ON recommendation_score_config(is_default, is_active);

-- AI 推荐候选清单
CREATE TABLE IF NOT EXISTS scheme_candidate (
    candidate_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recommend_request_id UUID NOT NULL,
    rspu_id VARCHAR(64) NOT NULL,
    rsku_id VARCHAR(64),
    score DECIMAL(5,4),
    ai_reason TEXT,
    match_factors JSONB,
    status VARCHAR(16) DEFAULT 'pending',
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (rsku_id) REFERENCES rsku_supply(rsku_id)
);
CREATE INDEX IF NOT EXISTS idx_scheme_candidate_request ON scheme_candidate(recommend_request_id, status);
CREATE INDEX IF NOT EXISTS idx_scheme_candidate_rspu ON scheme_candidate(rspu_id);
CREATE INDEX IF NOT EXISTS idx_scheme_candidate_created_by ON scheme_candidate(created_by, status);

-- 收藏夹（V4 并入）：用户级产品收藏，支持分组
CREATE TABLE IF NOT EXISTS user_favorite (
    favorite_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL REFERENCES sys_user(user_id),
    rspu_id VARCHAR(64) NOT NULL REFERENCES rspu_master(rspu_id),
    group_name VARCHAR(64),
    folder_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, rspu_id)
);
CREATE INDEX IF NOT EXISTS idx_favorite_user ON user_favorite(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_favorite_folder ON user_favorite(folder_id);

-- 收藏夹文件夹（V14 并入）
CREATE TABLE IF NOT EXISTS favorite_folder (
    folder_id   VARCHAR(64) PRIMARY KEY,
    user_id     VARCHAR(64) NOT NULL REFERENCES sys_user(user_id),
    folder_name VARCHAR(64) NOT NULL,
    sort_order  INT NOT NULL DEFAULT 0,
    deleted_at  TIMESTAMP,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_favorite_folder_user ON favorite_folder(user_id) WHERE deleted_at IS NULL;

-- 补齐 user_favorite 文件夹外键（favorite_folder 在 user_favorite 之后创建）
ALTER TABLE user_favorite DROP CONSTRAINT IF EXISTS fk_user_favorite_folder;
ALTER TABLE user_favorite
    ADD CONSTRAINT fk_user_favorite_folder FOREIGN KEY (folder_id) REFERENCES favorite_folder(folder_id);

-- 模板标签（V14 并入）：受控字典，scheme.template_tags 存名称 JSON，以名称为业务键
CREATE TABLE IF NOT EXISTS template_tag (
    tag_id     VARCHAR(64) PRIMARY KEY,
    tag_name   VARCHAR(64) NOT NULL UNIQUE,
    sort_order INT NOT NULL DEFAULT 0,
    enabled    BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 设计项目（V4 并入）
CREATE TABLE IF NOT EXISTS project (
    project_id VARCHAR(64) PRIMARY KEY,
    project_name VARCHAR(128) NOT NULL,
    project_type VARCHAR(32),
    company_name VARCHAR(128),
    owner_id VARCHAR(64) NOT NULL REFERENCES sys_user(user_id),
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    remark VARCHAR(512),
    share_enabled BOOLEAN NOT NULL DEFAULT false,
    share_expire_at TIMESTAMP,
    deleted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_project_owner ON project(owner_id) WHERE deleted_at IS NULL;

-- scheme.project_id 外键（表创建顺序约束，单独补加）
ALTER TABLE scheme DROP CONSTRAINT IF EXISTS fk_scheme_project;
ALTER TABLE scheme ADD CONSTRAINT fk_scheme_project FOREIGN KEY (project_id) REFERENCES project(project_id);

-- 订单主表（V5 并入；价格字段 AES 加密 TypeHandler 读写）
CREATE TABLE IF NOT EXISTS design_order (
    order_id VARCHAR(64) PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL UNIQUE,
    project_id VARCHAR(64) REFERENCES project(project_id),
    scheme_id VARCHAR(64) REFERENCES scheme(scheme_id),
    receiver_name VARCHAR(64),
    receiver_phone VARCHAR(32),
    receiver_area VARCHAR(128),
    receiver_address VARCHAR(256),
    original_total_price TEXT,
    price_rate NUMERIC(5, 4) NOT NULL DEFAULT 1 CHECK (price_rate >= 0 AND price_rate <= 1),
    final_total_price TEXT,
    item_count INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    expected_lead_time INT,
    remark VARCHAR(512),
    invite_token_hash VARCHAR(128),
    invite_expire_at TIMESTAMP,
    invite_confirmed_at TIMESTAMP,
    contract_file_id VARCHAR(64),
    idempotency_key VARCHAR(64),
    created_by VARCHAR(64) NOT NULL REFERENCES sys_user(user_id),
    deleted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_order_creator ON design_order(created_by) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_order_status ON design_order(status) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_design_order_project ON design_order(project_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_design_order_scheme ON design_order(scheme_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_design_order_idempotency
    ON design_order(created_by, idempotency_key) WHERE deleted_at IS NULL;

-- 订单号每日序号计数器（解决 COUNT+1 在软删除下与唯一索引冲突的问题）
CREATE TABLE IF NOT EXISTS order_no_counter (
    date_part VARCHAR(16) PRIMARY KEY,
    sequence_value BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 订单明细（V5 并入）
CREATE TABLE IF NOT EXISTS design_order_item (
    id BIGSERIAL PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL REFERENCES design_order(order_id),
    rspu_id VARCHAR(64) NOT NULL,
    rsku_id VARCHAR(64),
    variant_id VARCHAR(64),
    product_name VARCHAR(256),
    model VARCHAR(128),
    image_id VARCHAR(64),
    quantity INT NOT NULL DEFAULT 1,
    original_price TEXT,
    final_price TEXT,
    adjust_price TEXT,
    factory_code VARCHAR(16),
    snapshot_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_order_item_order ON design_order_item(order_id);
CREATE INDEX IF NOT EXISTS idx_order_item_rspu ON design_order_item(rspu_id);
CREATE INDEX IF NOT EXISTS idx_order_item_factory ON design_order_item(factory_code);

-- 轻量配置表（V5 并入）
CREATE TABLE IF NOT EXISTS sys_config (
    config_key VARCHAR(64) PRIMARY KEY,
    config_value TEXT,
    remark VARCHAR(256),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 字典别名表（V16 并入）：工厂方言叫法 → 字典码的持久化映射（导入确认后自学习积累）
CREATE TABLE IF NOT EXISTS dict_alias (
    id          BIGSERIAL PRIMARY KEY,
    dict_type   VARCHAR(32) NOT NULL,
    alias_name  VARCHAR(64) NOT NULL,
    dict_code   VARCHAR(16) NOT NULL,
    source      VARCHAR(16) NOT NULL DEFAULT 'ai_confirmed',
    created_by  VARCHAR(64),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_dict_alias UNIQUE (dict_type, alias_name)
);
CREATE INDEX IF NOT EXISTS idx_dict_alias_type ON dict_alias(dict_type);

-- 未归一值采集表（V19 并入）
CREATE TABLE IF NOT EXISTS dict_unresolved_value (
    id BIGSERIAL PRIMARY KEY,
    dict_type VARCHAR(32) NOT NULL,
    raw_value VARCHAR(128) NOT NULL,
    occurrence_count INT NOT NULL DEFAULT 1,
    first_seen_at TIMESTAMP NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMP NOT NULL DEFAULT now(),
    last_batch_id VARCHAR(64),
    last_username VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    resolved_code VARCHAR(16),
    resolved_by VARCHAR(64),
    resolved_at TIMESTAMP,
    CONSTRAINT uk_dict_unresolved UNIQUE (dict_type, raw_value)
);
CREATE INDEX IF NOT EXISTS idx_dict_unresolved_status ON dict_unresolved_value(status, dict_type);

-- =================== 5. 插入种子数据 ===================

-- 产品类别
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('category', 'FS', '座椅', 1),
('category', 'SF', '沙发', 2),
('category', 'TB', '茶几', 3),
('category', 'FC', '柜类', 4),
('category', 'BS', '吧椅', 5),
('category', 'OF', '办公家具', 6),
('category', 'DT', '餐桌', 7),
('category', 'BD', '床', 8),
('category', 'LT', '灯具', 9)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 家装风格（扩展为 11 个独立风格 + 6 个基础风格，保留 2 位编码）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('style', 'MC', '中古风', 1),
('style', 'BA', '包豪斯', 2),
('style', 'IT', '意式', 3),
('style', 'FR', '法式', 4),
('style', 'WJ', '侘寂', 5),
('style', 'NC', '新中式', 6),
('style', 'CR', '奶油风', 7),
('style', 'IN', '工业风', 8),
('style', 'MP', '孟菲斯', 9),
('style', 'IL', '意式极简轻奢', 10),
('style', 'ZS', '新中式宋式', 11),
('style', 'MB', '现代极简包豪斯', 12),
('style', 'MD', '孟菲斯多巴胺', 13),
('style', 'IO', '工业风LOFT', 14),
('style', 'FN', '法式复古南洋', 15),
('style', 'HH', '混搭风', 16),
('style', 'DL', '国外顶尖大牌搭配', 17)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 办公家具职级
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('grade', 'EX', '总裁级', 1),
('grade', 'MG', '经理级', 2),
('grade', 'ST', '职员级', 3),
('grade', 'PU', '公共区', 4),
('grade', 'CO', '会议区', 5)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 材质版本码（精简为 19 个准确大类，覆盖风格百科高频材质）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('material', 'TN', '真藤/竹编/草编', 1),
('material', 'PE', 'PE仿藤', 2),
('material', 'LE', '皮革', 3),
('material', 'NP', '纳帕皮', 4),
('material', 'SU', '磨砂皮', 5),
('material', 'MA', '马鞍皮', 6),
('material', 'LI', '亚麻/棉麻', 7),
('material', 'SF', '羊羔绒/泰迪绒', 8),
('material', 'VE', '天鹅绒/绒布', 9),
('material', 'WO', '实木', 10),
('material', 'RK', '藤编+实木混血', 11),
('material', 'MT', '金属/不锈钢/黄铜', 12),
('material', 'WL', '羊毛', 13),
('material', 'GL', '玻璃', 14),
('material', 'ST', '天然石材/大理石/洞石/岩板', 15),
('material', 'CE', '水泥/混凝土/微水泥', 16),
('material', 'CL', '陶瓷', 17),
('material', 'GP', '石膏/PU线条', 18),
('material', 'PL', '塑料/亚克力', 19)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 面料类型字典（V22，软体类商品专用；与 material_grade 面料等级区分）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('fabric', 'LI', '亚麻/棉麻', 1),
('fabric', 'XN', '雪尼尔', 2),
('fabric', 'KJ', '科技布', 3),
('fabric', 'VE', '天鹅绒/绒布', 4),
('fabric', 'SF', '羊羔绒/泰迪绒', 5),
('fabric', 'DX', '灯芯绒', 6),
('fabric', 'ZP', '真皮', 7),
('fabric', 'NP', '纳帕皮', 8),
('fabric', 'MS', '磨砂皮', 9),
('fabric', 'CX', '超纤皮', 10),
('fabric', 'PU', 'PU/PVC革', 11),
('fabric', 'WB', '网布', 12)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 常用材质/面料别名（V22：AI 识别叫法 → 字典标准项）
UPDATE category_dict SET aliases = '["橡木","胡桃木","松木","原木","木头","木质"]' WHERE dict_type='material' AND dict_code='WO';
UPDATE category_dict SET aliases = '["真皮","牛皮","头层牛皮","黄牛皮","皮质"]' WHERE dict_type='material' AND dict_code='LE';
UPDATE category_dict SET aliases = '["棉麻","麻布","亚麻布","棉麻布"]' WHERE dict_type='material' AND dict_code='LI';
UPDATE category_dict SET aliases = '["绒布","丝绒","天鹅绒布"]' WHERE dict_type='material' AND dict_code='VE';
UPDATE category_dict SET aliases = '["羊羔毛","泰迪绒","圈圈绒","羊羔绒"]' WHERE dict_type='material' AND dict_code='SF';
UPDATE category_dict SET aliases = '["不锈钢","黄铜","铁艺","铝合金","金属框架"]' WHERE dict_type='material' AND dict_code='MT';
UPDATE category_dict SET aliases = '["大理石","岩板","洞石","石材"]' WHERE dict_type='material' AND dict_code='ST';
UPDATE category_dict SET aliases = '["仿藤","PE藤","塑料藤"]' WHERE dict_type='material' AND dict_code='PE';
UPDATE category_dict SET aliases = '["藤编","竹编","草编","真藤"]' WHERE dict_type='material' AND dict_code='TN';
UPDATE category_dict SET aliases = '["亚克力","有机玻璃"]' WHERE dict_type='material' AND dict_code='PL';
UPDATE category_dict SET aliases = '["棉麻","麻布","亚麻布","棉麻布"]' WHERE dict_type='fabric' AND dict_code='LI';
UPDATE category_dict SET aliases = '["雪尼尔绒","雪尼尔布"]' WHERE dict_type='fabric' AND dict_code='XN';
UPDATE category_dict SET aliases = '["科技绒","科技皮革","三防布"]' WHERE dict_type='fabric' AND dict_code='KJ';
UPDATE category_dict SET aliases = '["绒布","丝绒","天鹅绒布"]' WHERE dict_type='fabric' AND dict_code='VE';
UPDATE category_dict SET aliases = '["羊羔毛","泰迪绒","圈圈绒","羊羔绒"]' WHERE dict_type='fabric' AND dict_code='SF';
UPDATE category_dict SET aliases = '["灯芯绒布","条绒"]' WHERE dict_type='fabric' AND dict_code='DX';
UPDATE category_dict SET aliases = '["牛皮","头层牛皮","黄牛皮","全皮"]' WHERE dict_type='fabric' AND dict_code='ZP';
UPDATE category_dict SET aliases = '["纳帕真皮","Napa皮","NAPPA皮"]' WHERE dict_type='fabric' AND dict_code='NP';
UPDATE category_dict SET aliases = '["反绒皮","麂皮"]' WHERE dict_type='fabric' AND dict_code='MS';
UPDATE category_dict SET aliases = '["超纤","超纤革"]' WHERE dict_type='fabric' AND dict_code='CX';
UPDATE category_dict SET aliases = '["PU皮","PVC革","人造革","仿皮","西皮"]' WHERE dict_type='fabric' AND dict_code='PU';
UPDATE category_dict SET aliases = '["网眼布","透气网布"]' WHERE dict_type='fabric' AND dict_code='WB';

-- 六维标签字典种子（V29：dict_code = {品类码}-{中文名}，aliases/remark 内联；E 维度不建枚举，统一引用 material/fabric 字典）
-- >>> SIX_DIM_DICT_SEED (generated by scripts/generate_six_dim_dict_seed.js, do not edit manually) >>>
-- 六维 A 维度 × FS 座椅/沙发（12 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'FS-一字型', '一字型', 'FS', 1, '["直排","直列"]', '正视呈一条直线，无转角无延伸位'),
('six_dim_A', 'FS-L型/转角组合', 'L型/转角组合', 'FS', 2, '["转角沙发","贵妃位","贵妃榻","L形组合"]', '座位向一侧拐出或一端延伸出无靠背长躺位'),
('six_dim_A', 'FS-方盒形', '方盒形', 'FS', 3, '["豆腐块","方块沙发","盒子形"]', '外轮廓方正利落、棱角分明、厚度均匀的体块感'),
('six_dim_A', 'FS-弧形/环抱形', '弧形/环抱形', 'FS', 4, '["圆弧形","月牙形","腰果形","帆船沙发"]', '整体呈弧线或两侧向内环抱的曲线轮廓'),
('six_dim_A', 'FS-低矮阔坐形', '低矮阔坐形', 'FS', 5, '["云朵沙发","低趴沙发","地平线沙发"]', '整体明显低矮、坐面宽大松软、重心贴地'),
('six_dim_A', 'FS-蛋形/球形', '蛋形/球形', 'FS', 6, '["蛋椅","蛋壳椅","球椅","太空舱椅"]', '座椅主体呈蛋/球形壳体，人被包裹在壳内'),
('six_dim_A', 'FS-贝壳/花瓣形', '贝壳/花瓣形', 'FS', 7, '["贝壳椅","扇贝椅","花瓣椅","微笑椅"]', '座面靠背呈展开的扇形/瓣状壳体，边缘上翘'),
('six_dim_A', 'FS-分节柱形', '分节柱形', 'FS', 8, '["毛毛虫沙发","褶皱沙发","Togo式"]', '无外露硬骨架，由横向分节/褶皱段堆叠成低矮长条'),
('six_dim_A', 'FS-外露骨架形', '外露骨架形', 'FS', 9, '["A字架形","骨架外露","框架式"]', '木/金属骨架构成整椅主体轮廓（含靠背扶手），软包嵌挂其间'),
('six_dim_A', 'FS-沙漏形/收腰', '沙漏形/收腰', 'FS', 10, '["收腰","细腰"]', '腰线明显内收，上下两端外张'),
('six_dim_A', 'FS-模块化组合', '模块化组合', 'FS', 11, '["模块沙发","自由组合","积木沙发"]', '可见多个独立相同模块拼接，模块间接缝清晰'),
('six_dim_A', 'FS-异形/其他', '异形/其他', 'FS', 12, '["不规则造型","艺术造型"]', '不属于以上任何一种的雕塑感/不规则轮廓')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × FS 座椅/沙发（12 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'FS-中高靠背', '中高靠背', 'FS', 1, '["标准靠背","中靠背"]', '靠背顶约在坐姿肩部附近，最常见'),
('six_dim_B', 'FS-低靠背', '低靠背', 'FS', 2, '["矮靠背","半靠背"]', '靠背明显低于肩部，仅及腰背部'),
('six_dim_B', 'FS-高靠背', '高靠背', 'FS', 3, '["高背","带头枕靠背"]', '靠背高过头部，可支撑头颈'),
('six_dim_B', 'FS-翼形包裹靠背', '翼形包裹靠背', 'FS', 4, '["翼背","耳朵靠背","包围式靠背","大象耳朵"]', '靠背两侧向前伸出侧翼，环抱头部'),
('six_dim_B', 'FS-分段靠包', '分段靠包', 'FS', 5, '["多靠枕","分离靠包","面包块靠背","活动靠包"]', '靠背由多个独立可移动靠包排列组成'),
('six_dim_B', 'FS-板面壳体靠背', '板面壳体靠背', 'FS', 6, '["曲木靠背","一体板背","贝壳靠背","塑料壳背"]', '靠背为一整片无分缝的弯曲板/壳体'),
('six_dim_B', 'FS-条形/梳背靠背', '条形/梳背靠背', 'FS', 7, '["竖条靠背","格栅靠背","温莎式","竖棂"]', '靠背由多根竖向细条/梳齿状杆件构成，有间隙'),
('six_dim_B', 'FS-Y形/叉骨靠背', 'Y形/叉骨靠背', 'FS', 8, '["叉骨椅","Y椅","Wishbone"]', '靠背中央为单根 Y 形（叉骨形）支撑构件'),
('six_dim_B', 'FS-编织镂空靠背', '编织镂空靠背', 'FS', 9, '["藤编靠背","绳编靠背","网面靠背"]', '靠背为编织/网状面，可见透空缝隙'),
('six_dim_B', 'FS-无靠背', '无靠背', 'FS', 10, '["无背","榻式"]', '坐面之后无任何靠背构件'),
('six_dim_B', 'FS-可调节/翻折靠背', '可调节/翻折靠背', 'FS', 11, '["可调靠背","活动头枕","档位靠背","可躺靠背"]', '可见铰链/档位结构，或靠背处于明显倾躺状态'),
('six_dim_B', 'FS-异形/其他', '异形/其他', 'FS', 12, NULL, '不属于以上形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × FS 座椅/沙发（11 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'FS-无扶手', '无扶手', 'FS', 1, '["光杆","无臂"]', '座面两侧无任何扶手构件'),
('six_dim_C', 'FS-标准方扶手', '标准方扶手', 'FS', 2, '["直扶手","方扶手","常规扶手"]', '高度接近靠背、截面方正的常规软包扶手'),
('six_dim_C', 'FS-宽厚扶手', '宽厚扶手', 'FS', 3, '["宽扶手","厚扶手","面包扶手","大软垫扶手"]', '扶手又宽又厚，顶面可置物/坐人'),
('six_dim_C', 'FS-低扶手', '低扶手', 'FS', 4, '["矮扶手","低平扶手"]', '扶手仅略高于座面，明显低于标准高度'),
('six_dim_C', 'FS-卷边外翻扶手', '卷边外翻扶手', 'FS', 5, '["卷扶手","外翻扶手","美式卷臂"]', '扶手顶端向外/向前翻卷成圆卷'),
('six_dim_C', 'FS-斜面/梯形扶手', '斜面/梯形扶手', 'FS', 6, '["斜扶手","楔形扶手"]', '扶手呈内斜面或上窄下宽的楔形'),
('six_dim_C', 'FS-环形/圈形扶手', '环形/圈形扶手', 'FS', 7, '["圈扶手","圆扶手","环抱扶手"]', '扶手与靠背上沿连成连续圆环/半环'),
('six_dim_C', 'FS-细杆金属扶手', '细杆金属扶手', 'FS', 8, '["铁艺扶手","金属细扶手","线条扶手"]', '扶手由细圆管/扁钢线框构成'),
('six_dim_C', 'FS-木面扶手', '木面扶手', 'FS', 9, '["实木扶手","外露木扶手","木框扶手"]', '扶手可见完整木质面（按可见外观判定，不论真实材质）'),
('six_dim_C', 'FS-一体成型扶手', '一体成型扶手', 'FS', 10, '["一体扶手","壳体扶手"]', '扶手与座面/靠背为同一连续壳体，无分缝'),
('six_dim_C', 'FS-异形/其他', '异形/其他', 'FS', 11, NULL, '不属于以上形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × FS 座椅/沙发（12 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'FS-落地底座', '落地底座', 'FS', 1, '["落地式","无可见腿","贴地"]', '座体底边直接落地，看不到任何腿'),
('six_dim_D', 'FS-实木腿', '实木腿', 'FS', 2, '["木腿","实木脚","锥形木腿"]', '可见四条（或更多）木质腿，常为锥形/斜撑'),
('six_dim_D', 'FS-细金属腿', '细金属腿', 'FS', 3, '["金属细腿","铁艺细腿","细高脚"]', '纤细金属直腿/锥腿，离地间隙大'),
('six_dim_D', 'FS-敦实柱腿', '敦实柱腿', 'FS', 4, '["粗腿","方块脚","矮粗脚"]', '粗短块状或柱状脚，视觉厚重'),
('six_dim_D', 'FS-金属框架底座', '金属框架底座', 'FS', 5, '["铁架底座","外露框架","金属骨架"]', '座面以下由连续钢管/扁钢框架承托，框架外露'),
('six_dim_D', 'FS-雪橇底座', '雪橇底座', 'FS', 6, '["雪橇脚","滑橇底座","弓形架"]', '金属管沿地面形成前后连续滑橇状底架'),
('six_dim_D', 'FS-悬臂底座', '悬臂底座', 'FS', 7, '["悬臂椅","C形架","无后腿"]', '仅前部/单侧支撑，座面后端悬空无后腿'),
('six_dim_D', 'FS-中央柱底座', '中央柱底座', 'FS', 8, '["五星脚","圆盘底座","转椅底座","喇叭底座","郁金香底座"]', '单根中柱下接五星爪/圆盘/喇叭形底座，常可旋转'),
('six_dim_D', 'FS-摇椅底座', '摇椅底座', 'FS', 9, '["摇腿","弧形摇脚","摇摇椅"]', '底部为两根弧形摇杆，可前后摇晃'),
('six_dim_D', 'FS-悬浮底座', '悬浮底座', 'FS', 10, '["悬浮式","内收底座","悬空底座"]', '底座内收/隐藏，正视座体似漂浮（底部有阴影间隙）'),
('six_dim_D', 'FS-滚轮脚', '滚轮脚', 'FS', 11, '["带轮","脚轮","万向轮"]', '腿部末端可见滚轮'),
('six_dim_D', 'FS-异形/其他', '异形/其他', 'FS', 12, NULL, '不属于以上形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × FS 座椅/沙发（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'FS-光面软包', '光面软包', 'FS', 1, '["素面","平整面","无明显工艺"]', '表面平整绷紧，无装饰性缝迹（最常见的默认项）'),
('six_dim_F', 'FS-饱满蓬松软包', '饱满蓬松软包', 'FS', 2, '["饱满填充","羽绒感","云朵感","厚软包"]', '垫体鼓起外溢、边缘松垮下垂、褶皱自然'),
('six_dim_F', 'FS-薄垫紧凑软包', '薄垫紧凑软包', 'FS', 3, '["薄垫","薄软包","贴合坐垫"]', '垫体薄且紧贴框架，轮廓利落'),
('six_dim_F', 'FS-绗缝', '绗缝', 'FS', 4, '["车格子","间棉","菱形格缝线"]', '表面有规则缝线格纹/条纹'),
('six_dim_F', 'FS-拉扣', '拉扣', 'FS', 5, '["纽扣软包","拉点","tufted"]', '表面有规律分布的内凹扣点/纽扣'),
('six_dim_F', 'FS-褶皱/抽褶', '褶皱/抽褶', 'FS', 6, '["抓褶","毛毛虫褶皱","横向褶皱"]', '表面呈规律横向/放射状挤压褶皱'),
('six_dim_F', 'FS-分块面包块', '分块面包块', 'FS', 7, '["面包块","豆腐块分格","块状软包"]', '座面/靠背分成若干饱满凸起的方块或长条块'),
('six_dim_F', 'FS-局部软包', '局部软包', 'FS', 8, '["半软包","仅坐垫软包","座面垫"]', '仅座面（或仅靠背）有软包，其余为裸露框架/硬面'),
('six_dim_F', 'FS-无软包', '无软包', 'FS', 9, '["硬座","裸面","板面"]', '坐靠处为木/塑/金属硬面，无任何软垫'),
('six_dim_F', 'FS-异形/其他', '异形/其他', 'FS', 10, NULL, '不属于以上工艺')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 A 维度 × SF 沙发（同 FS 清单）（12 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'SF-一字型', '一字型', 'SF', 1, '["直排","直列"]', '正视呈一条直线，无转角无延伸位'),
('six_dim_A', 'SF-L型/转角组合', 'L型/转角组合', 'SF', 2, '["转角沙发","贵妃位","贵妃榻","L形组合"]', '座位向一侧拐出或一端延伸出无靠背长躺位'),
('six_dim_A', 'SF-方盒形', '方盒形', 'SF', 3, '["豆腐块","方块沙发","盒子形"]', '外轮廓方正利落、棱角分明、厚度均匀的体块感'),
('six_dim_A', 'SF-弧形/环抱形', '弧形/环抱形', 'SF', 4, '["圆弧形","月牙形","腰果形","帆船沙发"]', '整体呈弧线或两侧向内环抱的曲线轮廓'),
('six_dim_A', 'SF-低矮阔坐形', '低矮阔坐形', 'SF', 5, '["云朵沙发","低趴沙发","地平线沙发"]', '整体明显低矮、坐面宽大松软、重心贴地'),
('six_dim_A', 'SF-蛋形/球形', '蛋形/球形', 'SF', 6, '["蛋椅","蛋壳椅","球椅","太空舱椅"]', '座椅主体呈蛋/球形壳体，人被包裹在壳内'),
('six_dim_A', 'SF-贝壳/花瓣形', '贝壳/花瓣形', 'SF', 7, '["贝壳椅","扇贝椅","花瓣椅","微笑椅"]', '座面靠背呈展开的扇形/瓣状壳体，边缘上翘'),
('six_dim_A', 'SF-分节柱形', '分节柱形', 'SF', 8, '["毛毛虫沙发","褶皱沙发","Togo式"]', '无外露硬骨架，由横向分节/褶皱段堆叠成低矮长条'),
('six_dim_A', 'SF-外露骨架形', '外露骨架形', 'SF', 9, '["A字架形","骨架外露","框架式"]', '木/金属骨架构成整椅主体轮廓（含靠背扶手），软包嵌挂其间'),
('six_dim_A', 'SF-沙漏形/收腰', '沙漏形/收腰', 'SF', 10, '["收腰","细腰"]', '腰线明显内收，上下两端外张'),
('six_dim_A', 'SF-模块化组合', '模块化组合', 'SF', 11, '["模块沙发","自由组合","积木沙发"]', '可见多个独立相同模块拼接，模块间接缝清晰'),
('six_dim_A', 'SF-异形/其他', '异形/其他', 'SF', 12, '["不规则造型","艺术造型"]', '不属于以上任何一种的雕塑感/不规则轮廓')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × SF 沙发（同 FS 清单）（12 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'SF-中高靠背', '中高靠背', 'SF', 1, '["标准靠背","中靠背"]', '靠背顶约在坐姿肩部附近，最常见'),
('six_dim_B', 'SF-低靠背', '低靠背', 'SF', 2, '["矮靠背","半靠背"]', '靠背明显低于肩部，仅及腰背部'),
('six_dim_B', 'SF-高靠背', '高靠背', 'SF', 3, '["高背","带头枕靠背"]', '靠背高过头部，可支撑头颈'),
('six_dim_B', 'SF-翼形包裹靠背', '翼形包裹靠背', 'SF', 4, '["翼背","耳朵靠背","包围式靠背","大象耳朵"]', '靠背两侧向前伸出侧翼，环抱头部'),
('six_dim_B', 'SF-分段靠包', '分段靠包', 'SF', 5, '["多靠枕","分离靠包","面包块靠背","活动靠包"]', '靠背由多个独立可移动靠包排列组成'),
('six_dim_B', 'SF-板面壳体靠背', '板面壳体靠背', 'SF', 6, '["曲木靠背","一体板背","贝壳靠背","塑料壳背"]', '靠背为一整片无分缝的弯曲板/壳体'),
('six_dim_B', 'SF-条形/梳背靠背', '条形/梳背靠背', 'SF', 7, '["竖条靠背","格栅靠背","温莎式","竖棂"]', '靠背由多根竖向细条/梳齿状杆件构成，有间隙'),
('six_dim_B', 'SF-Y形/叉骨靠背', 'Y形/叉骨靠背', 'SF', 8, '["叉骨椅","Y椅","Wishbone"]', '靠背中央为单根 Y 形（叉骨形）支撑构件'),
('six_dim_B', 'SF-编织镂空靠背', '编织镂空靠背', 'SF', 9, '["藤编靠背","绳编靠背","网面靠背"]', '靠背为编织/网状面，可见透空缝隙'),
('six_dim_B', 'SF-无靠背', '无靠背', 'SF', 10, '["无背","榻式"]', '坐面之后无任何靠背构件'),
('six_dim_B', 'SF-可调节/翻折靠背', '可调节/翻折靠背', 'SF', 11, '["可调靠背","活动头枕","档位靠背","可躺靠背"]', '可见铰链/档位结构，或靠背处于明显倾躺状态'),
('six_dim_B', 'SF-异形/其他', '异形/其他', 'SF', 12, NULL, '不属于以上形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × SF 沙发（同 FS 清单）（11 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'SF-无扶手', '无扶手', 'SF', 1, '["光杆","无臂"]', '座面两侧无任何扶手构件'),
('six_dim_C', 'SF-标准方扶手', '标准方扶手', 'SF', 2, '["直扶手","方扶手","常规扶手"]', '高度接近靠背、截面方正的常规软包扶手'),
('six_dim_C', 'SF-宽厚扶手', '宽厚扶手', 'SF', 3, '["宽扶手","厚扶手","面包扶手","大软垫扶手"]', '扶手又宽又厚，顶面可置物/坐人'),
('six_dim_C', 'SF-低扶手', '低扶手', 'SF', 4, '["矮扶手","低平扶手"]', '扶手仅略高于座面，明显低于标准高度'),
('six_dim_C', 'SF-卷边外翻扶手', '卷边外翻扶手', 'SF', 5, '["卷扶手","外翻扶手","美式卷臂"]', '扶手顶端向外/向前翻卷成圆卷'),
('six_dim_C', 'SF-斜面/梯形扶手', '斜面/梯形扶手', 'SF', 6, '["斜扶手","楔形扶手"]', '扶手呈内斜面或上窄下宽的楔形'),
('six_dim_C', 'SF-环形/圈形扶手', '环形/圈形扶手', 'SF', 7, '["圈扶手","圆扶手","环抱扶手"]', '扶手与靠背上沿连成连续圆环/半环'),
('six_dim_C', 'SF-细杆金属扶手', '细杆金属扶手', 'SF', 8, '["铁艺扶手","金属细扶手","线条扶手"]', '扶手由细圆管/扁钢线框构成'),
('six_dim_C', 'SF-木面扶手', '木面扶手', 'SF', 9, '["实木扶手","外露木扶手","木框扶手"]', '扶手可见完整木质面（按可见外观判定，不论真实材质）'),
('six_dim_C', 'SF-一体成型扶手', '一体成型扶手', 'SF', 10, '["一体扶手","壳体扶手"]', '扶手与座面/靠背为同一连续壳体，无分缝'),
('six_dim_C', 'SF-异形/其他', '异形/其他', 'SF', 11, NULL, '不属于以上形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × SF 沙发（同 FS 清单）（12 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'SF-落地底座', '落地底座', 'SF', 1, '["落地式","无可见腿","贴地"]', '座体底边直接落地，看不到任何腿'),
('six_dim_D', 'SF-实木腿', '实木腿', 'SF', 2, '["木腿","实木脚","锥形木腿"]', '可见四条（或更多）木质腿，常为锥形/斜撑'),
('six_dim_D', 'SF-细金属腿', '细金属腿', 'SF', 3, '["金属细腿","铁艺细腿","细高脚"]', '纤细金属直腿/锥腿，离地间隙大'),
('six_dim_D', 'SF-敦实柱腿', '敦实柱腿', 'SF', 4, '["粗腿","方块脚","矮粗脚"]', '粗短块状或柱状脚，视觉厚重'),
('six_dim_D', 'SF-金属框架底座', '金属框架底座', 'SF', 5, '["铁架底座","外露框架","金属骨架"]', '座面以下由连续钢管/扁钢框架承托，框架外露'),
('six_dim_D', 'SF-雪橇底座', '雪橇底座', 'SF', 6, '["雪橇脚","滑橇底座","弓形架"]', '金属管沿地面形成前后连续滑橇状底架'),
('six_dim_D', 'SF-悬臂底座', '悬臂底座', 'SF', 7, '["悬臂椅","C形架","无后腿"]', '仅前部/单侧支撑，座面后端悬空无后腿'),
('six_dim_D', 'SF-中央柱底座', '中央柱底座', 'SF', 8, '["五星脚","圆盘底座","转椅底座","喇叭底座","郁金香底座"]', '单根中柱下接五星爪/圆盘/喇叭形底座，常可旋转'),
('six_dim_D', 'SF-摇椅底座', '摇椅底座', 'SF', 9, '["摇腿","弧形摇脚","摇摇椅"]', '底部为两根弧形摇杆，可前后摇晃'),
('six_dim_D', 'SF-悬浮底座', '悬浮底座', 'SF', 10, '["悬浮式","内收底座","悬空底座"]', '底座内收/隐藏，正视座体似漂浮（底部有阴影间隙）'),
('six_dim_D', 'SF-滚轮脚', '滚轮脚', 'SF', 11, '["带轮","脚轮","万向轮"]', '腿部末端可见滚轮'),
('six_dim_D', 'SF-异形/其他', '异形/其他', 'SF', 12, NULL, '不属于以上形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × SF 沙发（同 FS 清单）（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'SF-光面软包', '光面软包', 'SF', 1, '["素面","平整面","无明显工艺"]', '表面平整绷紧，无装饰性缝迹（最常见的默认项）'),
('six_dim_F', 'SF-饱满蓬松软包', '饱满蓬松软包', 'SF', 2, '["饱满填充","羽绒感","云朵感","厚软包"]', '垫体鼓起外溢、边缘松垮下垂、褶皱自然'),
('six_dim_F', 'SF-薄垫紧凑软包', '薄垫紧凑软包', 'SF', 3, '["薄垫","薄软包","贴合坐垫"]', '垫体薄且紧贴框架，轮廓利落'),
('six_dim_F', 'SF-绗缝', '绗缝', 'SF', 4, '["车格子","间棉","菱形格缝线"]', '表面有规则缝线格纹/条纹'),
('six_dim_F', 'SF-拉扣', '拉扣', 'SF', 5, '["纽扣软包","拉点","tufted"]', '表面有规律分布的内凹扣点/纽扣'),
('six_dim_F', 'SF-褶皱/抽褶', '褶皱/抽褶', 'SF', 6, '["抓褶","毛毛虫褶皱","横向褶皱"]', '表面呈规律横向/放射状挤压褶皱'),
('six_dim_F', 'SF-分块面包块', '分块面包块', 'SF', 7, '["面包块","豆腐块分格","块状软包"]', '座面/靠背分成若干饱满凸起的方块或长条块'),
('six_dim_F', 'SF-局部软包', '局部软包', 'SF', 8, '["半软包","仅坐垫软包","座面垫"]', '仅座面（或仅靠背）有软包，其余为裸露框架/硬面'),
('six_dim_F', 'SF-无软包', '无软包', 'SF', 9, '["硬座","裸面","板面"]', '坐靠处为木/塑/金属硬面，无任何软垫'),
('six_dim_F', 'SF-异形/其他', '异形/其他', 'SF', 10, NULL, '不属于以上工艺')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 A 维度 × TB 茶几（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'TB-圆形', '圆形', 'TB', 1, '["圆几","大小圆"]', '俯视台面为正圆或近圆'),
('six_dim_A', 'TB-方形', '方形', 'TB', 2, '["方几","正方形茶几"]', '俯视四边近等长、四角分明（含近方矩形）'),
('six_dim_A', 'TB-矩形', '矩形', 'TB', 3, '["长方形茶几","长几"]', '俯视明显长宽不等的直边形'),
('six_dim_A', 'TB-跑道形', '跑道形', 'TB', 4, '["胶囊形","腰果形","长圆形","腰子形"]', '两端半圆+中间直边的拉长圆弧轮廓'),
('six_dim_A', 'TB-鹅卵石形', '鹅卵石形', 'TB', 5, '["蛋形","水滴形","不规则有机形"]', '轮廓为无直边的自由曲线有机形'),
('six_dim_A', 'TB-C形', 'C形', 'TB', 6, '["C型边几","U形边几","沙发边桌","嵌入式边几"]', '侧视呈C字悬臂，底座可插入沙发/床底'),
('six_dim_A', 'TB-鼓形/墩式', '鼓形/墩式', 'TB', 7, '["鼓凳茶几","圆墩茶几","柱墩茶几"]', '整体为一个实心鼓/墩体，无独立细腿'),
('six_dim_A', 'TB-托盘式', '托盘式', 'TB', 8, '["托盘茶几","围边茶几","碟形茶几"]', '俯视整体呈碟形/托盘轮廓且台面围边上翻（围边工艺归 C 维度）'),
('six_dim_A', 'TB-组合套几', '组合套几', 'TB', 9, '["子母茶几","大小组合","套几","高低组合"]', '两张以上大小/高低茶几嵌套或成组出现'),
('six_dim_A', 'TB-异形/其他', '异形/其他', 'TB', 10, '["不规则形","艺术造型"]', '无法归入上述类别的特殊轮廓')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × TB 茶几（7 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'TB-薄台面', '薄台面', 'TB', 1, '["薄板台面","极简薄边"]', '侧视台面单薄一片（厚度相对台面宽度比例小）'),
('six_dim_B', 'TB-厚台面', '厚台面', 'TB', 2, '["厚板台面","大板面"]', '侧视台面明显厚实有体量感'),
('six_dim_B', 'TB-悬浮台面', '悬浮台面', 'TB', 3, '["悬空台面","漂浮台面"]', '台面与支撑间可见内收/缝隙，似漂浮（支撑形态正常）'),
('six_dim_B', 'TB-内嵌/下沉台面', '内嵌/下沉台面', 'TB', 4, '["内凹台面","嵌入式台面"]', '面心低于边框，形成浅槽或嵌板结构'),
('six_dim_B', 'TB-双层台面', '双层台面', 'TB', 5, '["上下双层","夹层台面"]', '可见上下两层平行台面'),
('six_dim_B', 'TB-异形拼色/拼接台面', '异形拼色/拼接台面', 'TB', 6, '["拼色台面","组合台面"]', '台面由两种以上材质/色块拼接'),
('six_dim_B', 'TB-其他', '其他', 'TB', 7, NULL, '无法归类的台面构造')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × TB 茶几（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'TB-直边', '直边', 'TB', 1, '["直角边","平边"]', '边缘为垂直利落的直切面'),
('six_dim_C', 'TB-倒角边', '倒角边', 'TB', 2, '["斜边","切角边","斜切边"]', '边缘可见明显斜切坡面/斜面反光带'),
('six_dim_C', 'TB-圆弧边', '圆弧边', 'TB', 3, '["圆角边","R角","磨圆边"]', '边缘倒圆过渡，无棱角'),
('six_dim_C', 'TB-马肚边', '马肚边', 'TB', 4, '["鸭嘴边","法国边","弧腹边"]', '边缘外凸成连续饱满弧线'),
('six_dim_C', 'TB-瀑布边', '瀑布边', 'TB', 5, '["下挂边","垂边","waterfall"]', '台面材质沿侧边垂直下延至支撑/地面'),
('six_dim_C', 'TB-自然边', '自然边', 'TB', 6, '["原木边","树皮边","随形边"]', '边缘保留木材天然起伏/树皮轮廓'),
('six_dim_C', 'TB-围边', '围边', 'TB', 7, '["挡水边","立边","翻边","托盘边"]', '边缘向上凸起一圈挡沿（托盘式茶几的围边工艺归此维度）'),
('six_dim_C', 'TB-水波纹边', '水波纹边', 'TB', 8, '["热熔边","波浪边","熔岩边"]', '边缘呈不规则流动波浪起伏'),
('six_dim_C', 'TB-异形/其他', '异形/其他', 'TB', 9, NULL, '其他边缘处理')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × TB 茶几（11 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'TB-细直腿', '细直腿', 'TB', 1, '["细腿","直脚","细圆腿"]', '3~4根纤细垂直腿支撑'),
('six_dim_D', 'TB-锥形腿', '锥形腿', 'TB', 2, '["锥形脚","上粗下细腿","斜锥腿"]', '腿自上而下逐渐收细'),
('six_dim_D', 'TB-外八腿', '外八腿', 'TB', 3, '["八字腿","斜腿","A字腿"]', '腿向外倾斜张开呈八字'),
('six_dim_D', 'TB-粗柱腿', '粗柱腿', 'TB', 4, '["敦实柱腿","墩柱腿","象腿","粗圆/方柱"]', '单根或多根粗壮柱状支撑'),
('six_dim_D', 'TB-金属框架', '金属框架', 'TB', 5, '["铁艺框架","口字架","U形架","方框腿"]', '金属管/板构成框式支撑'),
('six_dim_D', 'TB-交叉底座', '交叉底座', 'TB', 6, '["X形底座","交叉腿","剪刀腿"]', '支撑件交叉成X/剪刀形'),
('six_dim_D', 'TB-单柱圆盘底座', '单柱圆盘底座', 'TB', 7, '["郁金香底座","喇叭底座","独柱底盘"]', '中央单柱+落地圆/喇叭盘'),
('six_dim_D', 'TB-悬浮底座', '悬浮底座', 'TB', 8, '["内收底座","隐形底座","亚克力底座"]', '底座明显内收/透明，台面似悬空'),
('six_dim_D', 'TB-落地式', '落地式', 'TB', 9, '["箱式底座","落地围板","满底"]', '支撑为落地箱体/围板，不见腿'),
('six_dim_D', 'TB-无腿墩式', '无腿墩式', 'TB', 10, '["整体墩","实心墩"]', '整器为一落地实心墩体，无腿可辨'),
('six_dim_D', 'TB-异形/其他', '异形/其他', 'TB', 11, NULL, '其他支撑形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × TB 茶几（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'TB-无功能件', '无功能件', 'TB', 1, '["固定式","纯台面"]', '无任何附加可动/储物结构（默认兜底）'),
('six_dim_F', 'TB-抽屉', '抽屉', 'TB', 2, '["带抽茶几"]', '立面可见抽屉面板/拉手'),
('six_dim_F', 'TB-层板', '层板', 'TB', 3, '["隔板","置物层","开放格"]', '台面下可见开放层板/隔层'),
('six_dim_F', 'TB-升降台面', '升降台面', 'TB', 4, '["升降茶几","Lift-top","可升桌板"]', '台面可抬升，可见铰链/气压杆结构'),
('six_dim_F', 'TB-旋转功能件', '旋转功能件', 'TB', 5, '["旋转台面","转动盘","旋转层"]', '可见可转动圆盘/旋转层结构'),
('six_dim_F', 'TB-折叠', '折叠', 'TB', 6, '["折叠茶几","翻折面"]', '可见折叠铰链/可收折的腿或面'),
('six_dim_F', 'TB-滚轮', '滚轮', 'TB', 7, '["带轮茶几","移动茶几"]', '底部可见脚轮/万向轮'),
('six_dim_F', 'TB-套叠收纳', '套叠收纳', 'TB', 8, '["可叠放","嵌套收纳"]', '小几可完全收入大几之下'),
('six_dim_F', 'TB-其他', '其他', 'TB', 9, NULL, '其他可见功能件')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 A 维度 × DT 餐桌（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'DT-长桌', '长桌', 'DT', 1, '["长方形餐桌","条桌","长条桌"]', '俯视为长矩形，市场绝对主力'),
('six_dim_A', 'DT-圆桌', '圆桌', 'DT', 2, '["圆餐桌","大圆桌"]', '俯视为正圆'),
('six_dim_A', 'DT-方桌', '方桌', 'DT', 3, '["方餐桌","八仙桌","四人方桌"]', '俯视四边等长'),
('six_dim_A', 'DT-椭圆桌', '椭圆桌', 'DT', 4, '["椭圆形餐桌","蛋形桌"]', '俯视为规则椭圆'),
('six_dim_A', 'DT-跑道形', '跑道形', 'DT', 5, '["胶囊形","腰果形","长圆桌"]', '两端半圆+直边的拉长轮廓'),
('six_dim_A', 'DT-半圆桌', '半圆桌', 'DT', 6, '["半月桌","靠墙半圆"]', '一侧直边靠墙、一侧半圆'),
('six_dim_A', 'DT-岛台一体桌', '岛台一体桌', 'DT', 7, '["岛台餐桌","餐岛一体","中岛台"]', '桌与厨房岛台连成一体的组合形态'),
('six_dim_A', 'DT-吧台桌', '吧台桌', 'DT', 8, '["吧桌","高脚桌","高吧桌"]', '明显高台面（约90cm及以上）配高脚凳'),
('six_dim_A', 'DT-异形/其他', '异形/其他', 'DT', 9, '["不规则桌"]', '其他轮廓')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × DT 餐桌（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'DT-平板薄面', '平板薄面', 'DT', 1, '["薄边平板","薄板台面"]', '侧视台面单薄平整'),
('six_dim_B', 'DT-厚台面', '厚台面', 'DT', 2, '["厚板","大板台面"]', '侧视台面厚实有分量'),
('six_dim_B', 'DT-悬浮台面', '悬浮台面', 'DT', 3, '["悬空台面"]', '台面与底座间内收/留缝似漂浮'),
('six_dim_B', 'DT-转盘台面', '转盘台面', 'DT', 4, '["带转盘桌面","双层圆面","旋转餐盘"]', '圆桌面中央可见可转动转盘（转盘特征统一归此维度）'),
('six_dim_B', 'DT-翻板台面', '翻板台面', 'DT', 5, '["蝶形翻板","折叠面板"]', '桌面可见翻折板与铰链拼缝'),
('six_dim_B', 'DT-拼缝台面', '拼缝台面', 'DT', 6, '["拼板台面","伸缩缝面"]', '桌面中部可见伸缩拼接缝/轨道线'),
('six_dim_B', 'DT-内嵌台面', '内嵌台面', 'DT', 7, '["嵌板面","框中嵌面"]', '面板嵌入边框，材质混搭'),
('six_dim_B', 'DT-围边台面', '围边台面', 'DT', 8, '["挡边面","托盘面"]', '桌面边缘上翻有围挡'),
('six_dim_B', 'DT-其他', '其他', 'DT', 9, NULL, '其他台面构造')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × DT 餐桌（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'DT-直边', '直边', 'DT', 1, '["直角边","平边"]', '边缘垂直利落'),
('six_dim_C', 'DT-圆角边', '圆角边', 'DT', 2, '["圆弧边","R角","磨圆边"]', '角部/边缘倒圆'),
('six_dim_C', 'DT-倒角边', '倒角边', 'DT', 3, '["斜边","切角边"]', '边缘可见斜切坡面'),
('six_dim_C', 'DT-马肚边', '马肚边', 'DT', 4, '["鸭嘴边","弧腹边","法国边"]', '边缘外凸成饱满连续弧线'),
('six_dim_C', 'DT-瀑布边', '瀑布边', 'DT', 5, '["下挂边","垂边","waterfall edge"]', '台面材质沿侧边垂直下延'),
('six_dim_C', 'DT-自然边', '自然边', 'DT', 6, '["原木边","随形边"]', '保留木材天然轮廓的边缘'),
('six_dim_C', 'DT-裙边/立水', '裙边/立水', 'DT', 7, '["围板","牙板","束腰"]', '台面下沿有一圈可见围板/牙条结构'),
('six_dim_C', 'DT-异形/其他', '异形/其他', 'DT', 8, NULL, '其他边缘/结构')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × DT 餐桌（12 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'DT-四直腿', '四直腿', 'DT', 1, '["直腿","四方腿","立柱腿"]', '四角各一根垂直腿'),
('six_dim_D', 'DT-外八腿', '外八腿', 'DT', 2, '["八字腿","斜腿","A字腿"]', '腿向外张开呈八字'),
('six_dim_D', 'DT-锥形腿', '锥形腿', 'DT', 3, '["锥形脚","收细腿"]', '腿上粗下细逐渐收分'),
('six_dim_D', 'DT-喇叭/郁金香底座', '喇叭/郁金香底座', 'DT', 4, '["喇叭腿","郁金香脚","独柱圆盘"]', '中央单柱下接喇叭形圆盘'),
('six_dim_D', 'DT-粗柱腿', '粗柱腿', 'DT', 5, '["大象腿","墩柱腿","粗方/圆柱"]', '粗壮柱状腿，体量感强'),
('six_dim_D', 'DT-T形/一字底座', 'T形/一字底座', 'DT', 6, '["T字腿","一字脚架"]', '两端各一T字/一字横脚支撑'),
('six_dim_D', 'DT-工字/H形底座', '工字/H形底座', 'DT', 7, '["工字架","H形腿"]', '侧视呈工/H形的框架支撑'),
('six_dim_D', 'DT-交叉底座', '交叉底座', 'DT', 8, '["X形底座","交叉腿","叉骨腿"]', '支撑交叉成X形'),
('six_dim_D', 'DT-U形/口字框架', 'U形/口字框架', 'DT', 9, '["口字腿","U形架","框式腿"]', '两端各一封闭框式支撑'),
('six_dim_D', 'DT-悬浮/内收底座', '悬浮/内收底座', 'DT', 10, '["悬浮底座","隐形底座"]', '底座深内收或透明，桌面似悬空'),
('six_dim_D', 'DT-落地箱式', '落地箱式', 'DT', 11, '["箱体底座","岛台落地柜"]', '支撑为落地柜体/箱体'),
('six_dim_D', 'DT-异形/其他', '异形/其他', 'DT', 12, NULL, '其他支撑形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × DT 餐桌（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'DT-固定式', '固定式', 'DT', 1, '["不可伸缩","整板"]', '桌面一整块，无变形结构'),
('six_dim_F', 'DT-伸缩式', '伸缩式', 'DT', 2, '["抽拉伸缩","导轨伸缩","加长桌"]', '桌面可沿导轨拉开加长（可见伸缩缝/导轨）'),
('six_dim_F', 'DT-折叠式', '折叠式', 'DT', 3, '["折叠桌","翻板桌","蝶形折叠"]', '桌板/腿可翻折收合，可见铰链'),
('six_dim_F', 'DT-旋转展开', '旋转展开', 'DT', 4, '["旋转变径","旋转伸缩圆桌"]', '圆桌旋开后拼板展开变大'),
('six_dim_F', 'DT-升降', '升降', 'DT', 5, '["升降餐桌","茶几餐桌两用"]', '桌面高度可调（气压/电动柱可见）'),
('six_dim_F', 'DT-带储物', '带储物', 'DT', 6, '["带抽屉","带柜餐桌"]', '桌体可见抽屉/柜门'),
('six_dim_F', 'DT-带电器件', '带电器件', 'DT', 7, '["电磁炉餐桌","暖菜板桌","火锅桌"]', '桌面可见嵌入式电磁炉/暖菜板/插座面板'),
('six_dim_F', 'DT-带滚轮', '带滚轮', 'DT', 8, '["移动餐桌","带轮岛台"]', '底部可见脚轮'),
('six_dim_F', 'DT-其他', '其他', 'DT', 9, NULL, '其他可见功能')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 A 维度 × FC 柜类（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'FC-矮柜', '矮柜', 'FC', 1, '["地柜","半高柜","矮边柜","低柜"]', '高度齐腰及以下、横长比例的独立柜体'),
('six_dim_A', 'FC-高柜', '高柜', 'FC', 2, '["立柜","通顶柜","到顶柜","一门到顶"]', '高度明显超过人胸/到顶、竖长比例'),
('six_dim_A', 'FC-组合柜', '组合柜', 'FC', 3, '["高低组合柜","模块柜","电视墙柜","满墙柜"]', '多个柜体单元拼接、高低错落或满墙一体'),
('six_dim_A', 'FC-斗柜', '斗柜', 'FC', 4, '["三斗柜","四斗柜","五斗柜","多斗柜"]', '竖向或横向叠放多个等大抽屉、无门或少量门'),
('six_dim_A', 'FC-转角柜', '转角柜', 'FC', 5, '["L型柜","角柜","拐角柜","三角柜"]', '贴合两面墙转角、俯视呈L形或三角形'),
('six_dim_A', 'FC-弧形柜', '弧形柜', 'FC', 6, '["圆弧柜","圆角柜","弧形边柜","拱形柜"]', '侧板或转角为圆弧曲面而非直角'),
('six_dim_A', 'FC-隔断柜', '隔断柜', 'FC', 7, '["博古架柜","双面柜","镂空置物柜"]', '柜体通透双面可用、作空间分隔'),
('six_dim_A', 'FC-其他', '其他', 'FC', 8, NULL, '无法归入以上形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × FC 柜类（11 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'FC-平板门', '平板门', 'FC', 1, '["光板门","双饰面门","PET门板","肤感门"]', '门面平整无造型、无压线'),
('six_dim_B', 'FC-造型门', '造型门', 'FC', 2, '["吸塑门","模压门","拼框门","法式线条门","回字门"]', '门面带内凹/凸起的框线造型'),
('six_dim_B', 'FC-骨骼线门', '骨骼线门', 'FC', 3, '["骨骼门","外凸线条门","井字/回字线条门"]', '门板表面有外凸装饰木线条且常兼作拉手'),
('six_dim_B', 'FC-格栅门', '格栅门', 'FC', 4, '["竖条纹门","木格栅门","栅格门","长城板门"]', '门面为连续竖向凸条纹或镂空格栅'),
('six_dim_B', 'FC-藤编门', '藤编门', 'FC', 5, '["藤面门","藤编框门","编藤柜门"]', '门框内嵌编织藤面、可见经纬编织纹'),
('six_dim_B', 'FC-长虹玻璃门', '长虹玻璃门', 'FC', 6, '["竖纹玻璃门","瓦楞玻璃门"]', '玻璃面带竖向压花条纹、朦胧透光'),
('six_dim_B', 'FC-玻璃门', '玻璃门', 'FC', 7, '["清玻门","灰玻门","茶玻门","铝框玻璃门"]', '透明/有色平玻璃、柜内物品可见'),
('six_dim_B', 'FC-移门', '移门', 'FC', 8, '["推拉门","趟门","滑门"]', '门板左右滑轨平移开启、有重叠门扇'),
('six_dim_B', 'FC-开放格为主', '开放格为主', 'FC', 9, '["敞口柜","格子柜","无门柜"]', '立面大部分为无门敞口格'),
('six_dim_B', 'FC-抽屉为主', '抽屉为主', 'FC', 10, '["全抽屉柜","多抽柜"]', '立面大部分为抽屉面板'),
('six_dim_B', 'FC-其他', '其他', 'FC', 11, '["百叶门","软包门"]', '百叶门、软包门等少见形式')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × FC 柜类（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'FC-明装拉手', '明装拉手', 'FC', 1, '["外装拉手","明拉手","圆钮/长条/黄铜把手"]', '拉手凸出安装于门板表面'),
('six_dim_C', 'FC-无拉手', '无拉手', 'FC', 2, '["反弹器","按压弹开","碰碰开"]', '门面完全平整无任何拉手/凹槽'),
('six_dim_C', 'FC-铣槽拉手', '铣槽拉手', 'FC', 3, '["凹槽拉手","J型/C型槽","牛角槽","一字槽"]', '门板边或面上铣出同色暗槽作抠手'),
('six_dim_C', 'FC-斜切拉手', '斜切拉手', 'FC', 4, '["45°斜切","斜边拉手","切角拉手"]', '门板上/下沿45°斜切留缝作抠手位'),
('six_dim_C', 'FC-G型拉手', 'G型拉手', 'FC', 5, '["G型/L型/U型金属型材拉手","内嵌型材拉手"]', '柜体层板间嵌金属型材形成通长拉槽'),
('six_dim_C', 'FC-门板下延', '门板下延', 'FC', 6, '["门板下挂","门板加长","上延抠手"]', '门板比柜体长1-2cm，抠延伸边开门'),
('six_dim_C', 'FC-隐形拉手', '隐形拉手', 'FC', 7, '["线性隐形拉手","拇指拉手","嵌入式拉手"]', '门缝处极窄长条、存在感极低'),
('six_dim_C', 'FC-其他', '其他', 'FC', 8, '["皮拉手","旋转拉手"]', '皮拉手、旋转拉手等')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × FC 柜类（7 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'FC-落地式', '落地式', 'FC', 1, '["落地柜","围板落地","踢脚板底座"]', '柜体直接落地、底部为围板/踢脚线'),
('six_dim_D', 'FC-高脚', '高脚', 'FC', 2, '["细高腿","锥形木脚","实木脚"]', '四只细长脚抬高柜体、底部大空隙'),
('six_dim_D', 'FC-金属支脚', '金属支脚', 'FC', 3, '["金属脚","不锈钢脚","金色细脚","铁艺脚"]', '金属材质细脚或框架脚'),
('six_dim_D', 'FC-矮脚', '矮脚', 'FC', 4, '["小矮脚","方墩脚","离地缝脚"]', '脚高不超过15cm、留缝可过扫地机'),
('six_dim_D', 'FC-悬浮挂墙', '悬浮挂墙', 'FC', 5, '["挂墙柜","壁挂柜","悬空柜","吊柜"]', '柜体固定上墙、底部完全悬空'),
('six_dim_D', 'FC-带轮', '带轮', 'FC', 6, '["万向轮","滑轮柜","移动柜"]', '底部可见滚轮、可移动'),
('six_dim_D', 'FC-其他', '其他', 'FC', 7, '["旋转底座"]', '旋转底座等少见形式')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × FC 柜类（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'FC-隔层', '隔层', 'FC', 1, '["层板","可调层板","隔板分隔"]', '内部以水平层板分隔空间'),
('six_dim_F', 'FC-抽屉', '抽屉', 'FC', 2, '["内置抽屉","抽内分隔"]', '门内或外露抽屉收纳'),
('six_dim_F', 'FC-设备位', '设备位', 'FC', 3, '["视听位","机顶盒位","走线孔","插座位"]', '可见开放设备格、背板走线孔/插座'),
('six_dim_F', 'FC-酒架杯架', '酒架杯架', 'FC', 4, '["红酒格","酒格","酒杯挂架"]', '可见X/方格酒架或高脚杯倒挂架'),
('six_dim_F', 'FC-鞋柜结构', '鞋柜结构', 'FC', 5, '["斜插鞋架","翻斗鞋柜","翻转鞋架"]', '斜放鞋位或下翻斗门结构'),
('six_dim_F', 'FC-台面功能区', '台面功能区', 'FC', 6, '["操作台","水吧台","轨道插座","咖啡角"]', '中部留空台面+背板插座/管线位'),
('six_dim_F', 'FC-灯带', '灯带', 'FC', 7, '["感应灯","氛围灯带","展示灯"]', '层板下/柜内可见发光灯带'),
('six_dim_F', 'FC-挂物区', '挂物区', 'FC', 8, '["挂衣钩","洞洞板","挂杆"]', '开放挂衣/挂物区'),
('six_dim_F', 'FC-其他', '其他', 'FC', 9, '["旋转鞋架","抽拉台面"]', '旋转鞋架、抽拉台面等')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 A 维度 × BD 床（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'BD-齐边床', '齐边床', 'BD', 1, '["齐边设计","窄边床","省空间床"]', '床边与床垫基本齐平、无外扩床框'),
('six_dim_A', 'BD-内嵌床', '内嵌床', 'BD', 2, '["包边床","宽边床","床垫内嵌床"]', '床垫陷入床框、四周有明显宽床沿'),
('six_dim_A', 'BD-地台床', '地台床', 'BD', 3, '["榻榻米床","落地矮床","无腿床"]', '低矮落地台座、床垫直接置于地台'),
('six_dim_A', 'BD-箱体床', '箱体床', 'BD', 4, '["箱框床","厚床身床","高床体床"]', '床身整体厚实落地、呈箱子轮廓'),
('six_dim_A', 'BD-上下床', '上下床', 'BD', 5, '["高低床","双层床","子母床","上下铺"]', '上下两层床面+爬梯/梯柜'),
('six_dim_A', 'BD-半高床/高架床', '半高床/高架床', 'BD', 6, '["中高床","上床下桌","上床下柜"]', '床面抬高、床下为书桌/柜/活动空间'),
('six_dim_A', 'BD-异形造型床', '异形造型床', 'BD', 7, '["汽车床","房子床","城堡床","圆床"]', '整体为卡通/非常规几何造型'),
('six_dim_A', 'BD-其他', '其他', 'BD', 8, '["折叠床","壁床","墨菲床"]', '折叠床、壁床（墨菲床）等')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × BD 床（12 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'BD-软包大靠包', '软包大靠包', 'BD', 1, '["大靠枕","双靠包","饱满靠包","分段靠包"]', '床头为1-2个饱满突出的软靠包'),
('six_dim_B', 'BD-拉扣床头', '拉扣床头', 'BD', 2, '["拉点床头","纽扣床头","法式拉扣","铆钉床头"]', '软包表面有规则凹点纽扣拉花'),
('six_dim_B', 'BD-竖琴床头', '竖琴床头', 'BD', 3, '["竖琴床","温莎床","竖条床头","栅栏床屏"]', '床头由竖向细条/立柱排列构成'),
('six_dim_B', 'BD-平板薄床头', '平板薄床头', 'BD', 4, '["超薄床头","极简床屏","直板床头"]', '床头为薄而平整的一整块板/软包面'),
('six_dim_B', 'BD-折耳靠包', '折耳靠包', 'BD', 5, '["大象耳朵","象耳床","翻折耳朵","Baxter象耳"]', '靠包两侧可翻折下垂如大象耳朵'),
('six_dim_B', 'BD-信封床头', '信封床头', 'BD', 6, '["信封床","信封翻折靠头","皮带扣床头"]', '软包如信封翻折、饰皮带/五金扣'),
('six_dim_B', 'BD-云朵床头', '云朵床头', 'BD', 7, '["云朵床","花瓣床头","波浪软包"]', '床屏轮廓为圆润云朵/花瓣曲线'),
('six_dim_B', 'BD-拱形床头', '拱形床头', 'BD', 8, '["拱门床屏","法式拱门","圆弧床头"]', '床屏顶部为拱形/大圆弧线'),
('six_dim_B', 'BD-卡通造型床头', '卡通造型床头', 'BD', 9, '["猫耳床","兔耳床","皇冠床头"]', '床头带动物耳朵/卡通轮廓'),
('six_dim_B', 'BD-功能床头', '功能床头', 'BD', 10, '["置物床头","书架床头","储物床屏"]', '床屏带置物格/开放架/插座夜灯'),
('six_dim_B', 'BD-无床头', '无床头', 'BD', 11, '["无床屏","裸床架","榻榻米床"]', '无床头板、床尾床头同高或靠墙'),
('six_dim_B', 'BD-其他', '其他', 'BD', 12, '["屏风式","超宽屏床头"]', '屏风式、超宽屏床头等')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × BD 床（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'BD-齐边无床尾', '齐边无床尾', 'BD', 1, '["无床尾屏","低床尾","齐平床尾"]', '床尾与床垫齐平或更低、无遮挡'),
('six_dim_C', 'BD-高床尾屏', '高床尾屏', 'BD', 2, '["床尾板","美式床尾","高尾屏"]', '床尾有明显高出的屏板'),
('six_dim_C', 'BD-立柱床尾', '立柱床尾', 'BD', 3, '["罗马柱床","四柱床","床尾柱"]', '床尾带装饰立柱'),
('six_dim_C', 'BD-软包床尾', '软包床尾', 'BD', 4, '["软包尾板","床尾凳一体"]', '床尾为软包面或与床体同面料'),
('six_dim_C', 'BD-宽边床沿', '宽边床沿', 'BD', 5, '["宽床沿","可坐床边","平台床边"]', '床侧床沿宽大可坐可置物'),
('six_dim_C', 'BD-圆角床边', '圆角床边', 'BD', 6, '["圆弧收边","防撞圆边","圆润转角"]', '床边转角为圆弧无棱角'),
('six_dim_C', 'BD-铁艺床尾', '铁艺床尾', 'BD', 7, '["金属管床尾","铁艺花纹床围"]', '床尾为铁艺管材/花纹'),
('six_dim_C', 'BD-其他', '其他', 'BD', 8, '["弧形床尾","船型床尾"]', '弧形床尾、船型床尾等')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × BD 床（7 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'BD-实木脚', '实木脚', 'BD', 1, '["木腿","锥形木脚","实木床脚"]', '可见木质支脚（含中脚）'),
('six_dim_D', 'BD-金属脚', '金属脚', 'BD', 2, '["金属细脚","不锈钢脚","碳素钢脚","金色脚"]', '可见金属材质支脚'),
('six_dim_D', 'BD-矮脚', '矮脚', 'BD', 3, '["小矮脚","短脚","离地缝脚"]', '床底仅留小缝隙（约5-15cm）'),
('six_dim_D', 'BD-落地无脚', '落地无脚', 'BD', 4, '["全落地","围边落地","床裙落地"]', '床体四周直接落地不见脚'),
('six_dim_D', 'BD-悬浮底座', '悬浮底座', 'BD', 5, '["内收底座","悬浮床脚","灯带底座","悬浮床剪影"]', '支脚内缩视觉悬浮、常配床底灯（悬浮特征统一归此维度）'),
('six_dim_D', 'BD-排骨架床架', '排骨架床架', 'BD', 6, '["排骨架","床条架","卷闸排骨条"]', '床面由横向木条排列构成（无床垫图可见时适用）'),
('six_dim_D', 'BD-其他', '其他', 'BD', 7, '["万向轮脚","气压升降脚"]', '万向轮脚、气压升降脚等')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × BD 床（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'BD-无储物', '无储物', 'BD', 1, '["框架床","空床底","普通床架"]', '床底通透或封闭但无任何储物开启结构'),
('six_dim_F', 'BD-气压上掀储物', '气压上掀储物', 'BD', 2, '["高箱床","气压杆床","液压箱体床","油压床"]', '床板连床垫整体可上掀、床体为大箱仓'),
('six_dim_F', 'BD-床体抽屉', '床体抽屉', 'BD', 3, '["侧抽床","床尾抽屉","三抽储物床"]', '床侧/床尾可见抽屉面板与拉手缝'),
('six_dim_F', 'BD-床头功能区', '床头功能区', 'BD', 4, '["置物床头","充电床头","书架床头","夜灯床头"]', '床屏带格架/插座/灯等可见功能件'),
('six_dim_F', 'BD-床底灯带', '床底灯带', 'BD', 5, '["悬浮灯","感应夜灯","氛围灯"]', '床底沿可见灯带发光'),
('six_dim_F', 'BD-组合功能床', '组合功能床', 'BD', 6, '["上床下桌","梯柜床","床柜一体","榻榻米组合"]', '床与书桌/梯柜/衣柜连成一体'),
('six_dim_F', 'BD-拖床', '拖床', 'BD', 7, '["拖拉子床","抽拉床","子母拖床"]', '主床下藏可拉出的小床'),
('six_dim_F', 'BD-其他', '其他', 'BD', 8, '["电动床架","按摩功能"]', '电动床架、按摩功能等')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 A 维度 × BS 吧椅（7 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'BS-圆形座', '圆形座', 'BS', 1, '["圆凳面","圆盘座","圆面吧凳"]', '座面俯视呈正圆或椭圆形，无方向性'),
('six_dim_A', 'BS-方形座', '方形座', 'BS', 2, '["方凳面","矩形座","方座"]', '座面俯视呈正方形或矩形，可见直边直角'),
('six_dim_A', 'BS-曲壳座', '曲壳座', 'BS', 3, '["曲木座","弯板座","一体壳座","PP座壳"]', '座面为一体化成型曲面壳体，带人体工学下凹弧度'),
('six_dim_A', 'BS-马鞍座', '马鞍座', 'BS', 4, '["鞍形座","马鞍凳","W形座"]', '座面呈马鞍状前后翘起、中间下凹的W双曲面'),
('six_dim_A', 'BS-镂空铁座', '镂空铁座', 'BS', 5, '["拖拉机座","拖拉机凳","铸铁座","工业风座"]', '金属座面两侧凸起带镂空孔洞，形似老式拖拉机座'),
('six_dim_A', 'BS-马蹄形座', '马蹄形座', 'BS', 6, '["马蹄座","U形座","半圆口座"]', '座面后缘或前缘带U形/马蹄形缺口或轮廓'),
('six_dim_A', 'BS-其他', '其他', 'BS', 7, NULL, '不属于以上任何形态的座面')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × BS 吧椅（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'BS-无靠背', '无靠背', 'BS', 1, '["无背","光凳","吧凳"]', '座面上方完全无竖向支撑结构'),
('six_dim_B', 'BS-低靠背', '低靠背', 'BS', 2, '["半靠背","矮靠背","腰靠"]', '靠背高度仅及腰部，明显低于肩胛'),
('six_dim_B', 'BS-高靠背', '高靠背', 'BS', 3, '["全靠背","高背吧椅"]', '靠背向上延伸至肩胛及以上，形成完整背部支撑面'),
('six_dim_B', 'BS-环抱式靠背', '环抱式靠背', 'BS', 4, '["环绕靠背","一体壳背","天鹅椅式","蝴蝶背"]', '靠背呈弧形向前环抱包覆，与座壳连成有机曲面'),
('six_dim_B', 'BS-竖条靠背', '竖条靠背', 'BS', 5, '["梳背","温莎背","条背"]', '靠背由多根竖向细条排列构成，条间可见缝隙'),
('six_dim_B', 'BS-镂空网格靠背', '镂空网格靠背', 'BS', 6, '["镂空背","铁艺背","网格背","冲孔背"]', '靠背为带规则或不规则镂空图案的板面/网格'),
('six_dim_B', 'BS-编织靠背', '编织靠背', 'BS', 7, '["藤编背","绳编背","编带背"]', '靠背可见藤条/绳/织带交叉编织纹理'),
('six_dim_B', 'BS-其他', '其他', 'BS', 8, NULL, '不属于以上任何形态的靠背')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × BS 吧椅（6 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'BS-无扶手', '无扶手', 'BS', 1, '["光身","无臂"]', '座面两侧无任何供手臂搭放的结构'),
('six_dim_C', 'BS-双扶手', '双扶手', 'BS', 2, '["两侧扶手","独立扶手","全扶手"]', '座面左右各有一根独立于靠背的扶手杆/板'),
('six_dim_C', 'BS-一体环绕扶手', '一体环绕扶手', 'BS', 3, '["一体扶手","环抱扶手","壳式扶手"]', '扶手与靠背由同一曲面/杆件连续环绕成型，无断点'),
('six_dim_C', 'BS-小扶手', '小扶手', 'BS', 4, '["半扶手","短扶手","半截扶手"]', '扶手长度明显短于座面深度，仅覆盖后半段或呈短柱状'),
('six_dim_C', 'BS-环形扶手', '环形扶手', 'BS', 5, '["圆环扶手","皇冠扶手"]', '扶手为一整圈环形杆件环绕座面上方（低频，注意与座下脚踏圈区分）'),
('six_dim_C', 'BS-其他', '其他', 'BS', 6, NULL, '不属于以上任何形态的扶手')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × BS 吧椅（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'BS-气压升降底座', '气压升降底座', 'BS', 1, '["气压杆","升降底座","气杆圆盘"]', '座下可见单根金属气压柱+圆形平底盘，常带环形脚踏圈'),
('six_dim_D', 'BS-四脚底座', '四脚底座', 'BS', 2, '["四条腿","木脚","四腿架"]', '四根独立椅腿支撑，腿间常有横撑（踏脚撑）'),
('six_dim_D', 'BS-单柱圆盘底座', '单柱圆盘底座', 'BS', 3, '["固定圆盘","喇叭底座","郁金香脚","圆盘脚"]', '单根立柱下接一体成型圆盘/喇叭形盘，不可升降'),
('six_dim_D', 'BS-五星脚底座', '五星脚底座', 'BS', 4, '["五爪脚","五星脚","带轮底座"]', '中心柱下伸出五爪支脚且末端带滚轮'),
('six_dim_D', 'BS-螺旋升降底座', '螺旋升降底座', 'BS', 5, '["螺旋杆","旋转升降","丝杆升降"]', '座面靠螺纹丝杆旋转调节高度，座下可见螺杆结构'),
('six_dim_D', 'BS-雪橇脚底座', '雪橇脚底座', 'BS', 6, '["弓形脚","滑橇脚","钢筋弓架"]', '钢管弯曲成落地滑橇状连贯底架'),
('six_dim_D', 'BS-三脚底座', '三脚底座', 'BS', 7, '["三腿","三角架"]', '三根支腿呈三角分布支撑座面'),
('six_dim_D', 'BS-其他', '其他', 'BS', 8, NULL, '不属于以上任何形态的底座')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × BS 吧椅（6 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'BS-无软包', '无软包', 'BS', 1, '["硬面","光板","素面"]', '座面/靠背为裸露木、金属、塑料或藤面，无织物皮革覆盖'),
('six_dim_F', 'BS-薄垫', '薄垫', 'BS', 2, '["薄软包","平板垫","贴面垫"]', '座面覆一层扁平垫层，厚度薄、轮廓贴合座面'),
('six_dim_F', 'BS-厚软包', '厚软包', 'BS', 3, '["厚垫","饱满软包","面包座"]', '垫层明显隆起饱满，边缘可见鼓胀体量'),
('six_dim_F', 'BS-绗缝', '绗缝', 'BS', 4, '["车线格纹","菱形格","竖条绗缝","拉线"]', '软包表面有规则缝线分割出的格纹/条纹肌理'),
('six_dim_F', 'BS-拉扣', '拉扣', 'BS', 5, '["拉点","纽扣软包","Chesterfield"]', '软包表面有纽扣下陷形成的均匀凹点与放射褶皱'),
('six_dim_F', 'BS-其他', '其他', 'BS', 6, NULL, '不属于以上任何形态的软包')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 A 维度 × OF 办公家具（11 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'OF-办公椅', '办公椅', 'OF', 1, '["职员椅","转椅","电脑椅","老板椅","会议椅"]', '带靠背与底座的单人居坐家具'),
('six_dim_A', 'OF-职员桌', '职员桌', 'OF', 2, '["员工桌","电脑桌","开放式工位","办公桌"]', '单人/多人开放式条形工作桌，无围合屏风'),
('six_dim_A', 'OF-屏风工位', '屏风工位', 'OF', 3, '["卡位","卡座","隔断工位","屏风桌"]', '桌面被高于台面的屏风板半围合，形成独立格子间'),
('six_dim_A', 'OF-文件柜', '文件柜', 'OF', 4, '["资料柜","书柜","档案柜","铁皮柜","储物柜"]', '以竖向柜体与门/抽屉为主的储物家具，无工作台面'),
('six_dim_A', 'OF-会议桌', '会议桌', 'OF', 5, '["会议台","洽谈长桌"]', '可供多人围坐的长条/圆形大台面桌'),
('six_dim_A', 'OF-班台', '班台', 'OF', 6, '["大班台","老板桌","总裁桌","大班桌"]', '体量大、台面宽厚、带落地侧板与副柜的独立 executive 桌'),
('six_dim_A', 'OF-主管桌', '主管桌', 'OF', 7, '["经理桌","主管台"]', '体量介于班台与职员桌之间，常带小侧柜'),
('six_dim_A', 'OF-培训折叠桌', '培训折叠桌', 'OF', 8, '["折叠桌","条桌","翻板桌","培训桌"]', '桌腿或台面可折叠/翻转，便于收纳拼接'),
('six_dim_A', 'OF-洽谈桌', '洽谈桌', 'OF', 9, '["洽谈台","小圆桌","谈判桌"]', '小尺度圆/方台面，供2~4人近距离对坐'),
('six_dim_A', 'OF-升降桌', '升降桌', 'OF', 10, '["电动升降桌","站立办公桌"]', '可见粗壮双立柱升降腿，台面高度可调'),
('six_dim_A', 'OF-其他', '其他', 'OF', 11, '["前台接待台","讲台"]', '不属于以上任何品类形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × OF 办公家具（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'OF-一字台面', '一字台面', 'OF', 1, '["平面台面","矩形台面","直台面"]', '工作面为单一矩形平面，无转角无附加板'),
('six_dim_B', 'OF-L型台面', 'L型台面', 'OF', 2, '["转角台面","L台","拐角台面"]', '工作面由两个方向台面垂直相接成L形'),
('six_dim_B', 'OF-弧形台面', '弧形台面', 'OF', 3, '["鸭嘴边","弧边台","异型台缘"]', '台面前缘呈外凸弧线或波浪形而非直线'),
('six_dim_B', 'OF-带挡板', '带挡板', 'OF', 4, '["前挡板","桌挡","屏风挡板"]', '台面前缘下方或上方有竖直挡板遮挡视线'),
('six_dim_B', 'OF-网布靠背', '网布靠背', 'OF', 5, '["网背","透气网背","全网"]', '椅背为绷紧的半透明网状织物，可透见网格纹理'),
('six_dim_B', 'OF-软包靠背', '软包靠背', 'OF', 6, '["皮背","布背","海绵靠背"]', '椅背为皮革/织物包裹海绵的实心软包面'),
('six_dim_B', 'OF-硬壳靠背', '硬壳靠背', 'OF', 7, '["塑壳背","曲木背","一体壳背"]', '椅背为硬质一体成型壳体，无软包无网面'),
('six_dim_B', 'OF-玻璃柜门', '玻璃柜门', 'OF', 8, '["玻璃门书柜","玻门柜"]', '柜体正面为透明/半透明玻璃门扇，可透视内部'),
('six_dim_B', 'OF-封闭柜门', '封闭柜门', 'OF', 9, '["掩门","铁门","木门","实门柜"]', '柜体正面为不透明实体门扇'),
('six_dim_B', 'OF-其他', '其他', 'OF', 10, NULL, '不属于以上任何工作面/背部形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × OF 办公家具（8 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'OF-落地侧板', '落地侧板', 'OF', 1, '["侧板","封板","台脚板"]', '桌体两侧为从台面直达地面的整板支撑面'),
('six_dim_C', 'OF-副柜侧柜', '副柜侧柜', 'OF', 2, '["副台","附柜","侧柜","活动侧柜"]', '主台侧面依附连接的低柜/窄台面单元'),
('six_dim_C', 'OF-屏风隔断', '屏风隔断', 'OF', 3, '["屏风板","隔断板","卡位屏风"]', '工位之间/边缘竖立的高于台面的分隔板'),
('six_dim_C', 'OF-扶手', '扶手', 'OF', 4, '["椅扶手","升降扶手","固定扶手"]', '椅座两侧供手臂搭放的杆状/面状结构'),
('six_dim_C', 'OF-椅背侧翼', '椅背侧翼', 'OF', 5, '["侧翼","护翼","包围翼"]', '椅背两侧向前包出的翼状凸起'),
('six_dim_C', 'OF-走线槽盒', '走线槽盒', 'OF', 6, '["线槽","线盒","过线盒","走线孔盖"]', '台面或桌下可见的矩形走线槽/翻盖线盒'),
('six_dim_C', 'OF-横梁拉杆', '横梁拉杆', 'OF', 7, '["桌下横撑","拉杆","连接梁"]', '桌腿之间可见的水平连接杆件'),
('six_dim_C', 'OF-其他', '其他', 'OF', 8, NULL, '不属于以上任何侧部/连接特征')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × OF 办公家具（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'OF-钢架桌腿', '钢架桌腿', 'OF', 1, '["金属桌架","钢架","口字架","钢木腿"]', '金属管材焊接成口字/框形桌架承托台面'),
('six_dim_D', 'OF-板式落地支撑', '板式落地支撑', 'OF', 2, '["落地侧板脚","全落地","板脚"]', '由竖直板材直达地面形成支撑，无独立细腿'),
('six_dim_D', 'OF-实木桌腿', '实木桌腿', 'OF', 3, '["木腿","实木框架脚"]', '木质方/圆腿或木框架支撑'),
('six_dim_D', 'OF-升降立柱腿', '升降立柱腿', 'OF', 4, '["升降柱","电动腿","双柱腿"]', '两条粗壮矩形立柱套筒式桌腿，区别于细管钢架'),
('six_dim_D', 'OF-五星脚底盘', '五星脚底盘', 'OF', 5, '["五爪脚","五星脚","转椅底盘"]', '中心气压柱下伸出五爪支脚并带滚轮'),
('six_dim_D', 'OF-弓形脚', '弓形脚', 'OF', 6, '["悬臂脚","弓字脚","弓架椅"]', '钢管弯成前悬后弓的C/弓形，无后腿'),
('six_dim_D', 'OF-四脚底座', '四脚底座', 'OF', 7, '["四腿","四脚椅腿"]', '四根独立椅腿/桌腿垂直支撑'),
('six_dim_D', 'OF-柜类落地底座', '柜类落地底座', 'OF', 8, '["柜脚","踢脚底","落地柜"]', '柜体直接落地或带矮踢脚/小柜脚'),
('six_dim_D', 'OF-其他', '其他', 'OF', 9, NULL, '不属于以上任何支撑形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × OF 办公家具（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'OF-无功能件', '无功能件', 'OF', 1, '["素体","无附件"]', '整桌/整椅无任何附加功能构件'),
('six_dim_F', 'OF-抽屉', '抽屉', 'OF', 2, '["吊抽","桌斗","斗抽"]', '台面下方或柜体正面可见抽屉面板与拉手'),
('six_dim_F', 'OF-升降机构', '升降机构', 'OF', 3, '["气压杆","升降柱","手摇升降"]', '可见气压杆手柄/电动柱/摇把等调高构件'),
('six_dim_F', 'OF-头枕', '头枕', 'OF', 4, '["头靠","颈枕","可调节头枕"]', '椅背顶部凸出的独立小枕面'),
('six_dim_F', 'OF-腰靠', '腰靠', 'OF', 5, '["腰托","腰枕","撑腰"]', '椅背腰部位置独立凸起或可调的支撑件'),
('six_dim_F', 'OF-脚踏', '脚踏', 'OF', 6, '["搁脚","脚托","躺舒宝"]', '椅座前方可抽出/翻出的腿部承托板'),
('six_dim_F', 'OF-活动柜', '活动柜', 'OF', 7, '["移动推柜","三抽柜","桌下柜"]', '桌下带轮可移动的独立小柜'),
('six_dim_F', 'OF-键盘架', '键盘架', 'OF', 8, '["键盘托","键盘抽"]', '台面下方可抽拉的窄托板'),
('six_dim_F', 'OF-软包坐垫', '软包坐垫', 'OF', 9, '["海绵坐垫","厚坐垫"]', '椅座为明显鼓起的软包垫层（与 B 软包靠背区分：一座一背）'),
('six_dim_F', 'OF-其他', '其他', 'OF', 10, '["杯架","书报架"]', '不属于以上任何功能件')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 A 维度 × LT 灯具（12 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_A', 'LT-球形', '球形', 'LT', 1, '["圆球灯","球泡灯","球体灯"]', '灯体主体为正圆球/半球形发光体'),
('six_dim_A', 'LT-圆盘形', '圆盘形', 'LT', 2, '["飞碟灯","碟形","平板灯","面板灯"]', '灯体为扁平圆盘/薄板状，厚度远小于直径'),
('six_dim_A', 'LT-长条形', '长条形', 'LT', 3, '["线性灯","一字灯","长条吊灯","灯管形"]', '灯体呈细长直线状，长度远大于宽度'),
('six_dim_A', 'LT-环形', '环形', 'LT', 4, '["圈圈灯","圆环灯","光环灯"]', '灯体为一个或多个同心发光圆环'),
('six_dim_A', 'LT-枝形', '枝形', 'LT', 5, '["枝形吊灯","多臂吊灯","烛台吊灯"]', '中心灯柱向外分出多个弯曲灯臂，每臂一端灯'),
('six_dim_A', 'LT-伞形', '伞形', 'LT', 6, '["斗笠灯","草帽灯","伞盖灯"]', '灯罩呈上小下大的伞盖/斗笠轮廓，光向下投'),
('six_dim_A', 'LT-蘑菇形', '蘑菇形', 'LT', 7, '["蘑菇灯"]', '半球伞盖+圆柱灯梗构成蘑菇剪影'),
('six_dim_A', 'LT-分子式', '分子式', 'LT', 8, '["分子灯","魔豆灯","多头球灯","卫星灯"]', '主杆上不规则伸展多支杆，各端带球形灯头'),
('six_dim_A', 'LT-柱形', '柱形', 'LT', 9, '["筒形灯","圆柱灯","直筒灯"]', '灯体为直立圆柱/方柱筒状'),
('six_dim_A', 'LT-笼形', '笼形', 'LT', 10, '["鸟笼灯","铁笼灯","笼式灯"]', '光源被金属条/藤条编织的笼状外壳包围'),
('six_dim_A', 'LT-仿生异形', '仿生异形', 'LT', 11, '["花形灯","云朵灯","艺术造型灯"]', '灯体模拟花朵/云/动植物或呈不规则艺术形态'),
('six_dim_A', 'LT-其他', '其他', 'LT', 12, NULL, '不属于以上任何灯体造型')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 B 维度 × LT 灯具（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_B', 'LT-玻璃罩', '玻璃罩', 'LT', 1, '["玻璃灯罩","磨砂玻璃","奶白玻璃罩"]', '透明/磨砂/乳白色玻璃罩体，可见玻璃质感与透光'),
('six_dim_B', 'LT-亚克力罩', '亚克力罩', 'LT', 2, '["亚克力灯罩","导光板","PS罩"]', '白色半透明塑料质感罩体，边缘可见均匀导光面'),
('six_dim_B', 'LT-金属罩', '金属罩', 'LT', 3, '["铁艺罩","金属灯罩","铝罩"]', '不透明金属罩壳，光仅从开口定向射出'),
('six_dim_B', 'LT-布艺罩', '布艺罩', 'LT', 4, '["布罩","麻布灯罩","棉麻罩"]', '织物绷面罩体，可见布纹肌理、边缘包边'),
('six_dim_B', 'LT-无罩裸光源', '无罩裸光源', 'LT', 5, '["裸灯泡","爱迪生灯泡","灯带一体","灯盘"]', '光源（灯泡/灯带/灯珠板）完全外露无罩体遮挡'),
('six_dim_B', 'LT-褶皱罩', '褶皱罩', 'LT', 6, '["百褶灯罩","折纸罩","风琴罩"]', '罩面呈规则放射状打褶的风琴式立体纹理'),
('six_dim_B', 'LT-藤竹编织罩', '藤竹编织罩', 'LT', 7, '["藤编灯罩","竹编罩","草编罩"]', '罩体由藤/竹/草条编织而成，可见编织孔洞与漏光'),
('six_dim_B', 'LT-纸质罩', '纸质罩', 'LT', 8, '["纸灯","和纸灯","羊皮纸罩"]', '纸质半透明罩体，质感轻盈哑光'),
('six_dim_B', 'LT-水晶罩串', '水晶罩串', 'LT', 9, '["水晶灯罩","水晶珠串罩","玻璃串珠"]', '出光部位由水晶/玻璃珠串或切割水晶件构成（看罩体本身）'),
('six_dim_B', 'LT-其他', '其他', 'LT', 10, '["陶瓷罩"]', '不属于以上任何罩体/出光形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 C 维度 × LT 灯具（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_C', 'LT-吊线吊杆', '吊线吊杆', 'LT', 1, '["吊线","吊杆","直吊","吊链杆"]', '灯体由单根/多根竖直线缆或刚性杆从顶面垂吊'),
('six_dim_C', 'LT-无臂直连', '无臂直连', 'LT', 2, '["直连","贴装","一体化"]', '灯体直接贴合安装面，中间无任何杆臂结构'),
('six_dim_C', 'LT-多枝灯臂', '多枝灯臂', 'LT', 3, '["分叉灯臂","曲臂","枝臂"]', '多根弯曲/伸展灯臂从中心向外连接各灯头'),
('six_dim_C', 'LT-直杆立杆', '直杆立杆', 'LT', 4, '["灯柱","直杆","落地杆"]', '底座向上延伸一根竖直长杆支撑顶部灯头'),
('six_dim_C', 'LT-弧形悬臂', '弧形悬臂', 'LT', 5, '["钓鱼竿","抛物线臂","钓鱼灯臂"]', '长臂呈大跨度抛物线弧，灯头悬垂于弧端'),
('six_dim_C', 'LT-折叠摇臂', '折叠摇臂', 'LT', 6, '["长臂折叠","摇臂","机械臂","弹簧臂"]', '由关节连接的两段以上杆件，可明显弯折调节'),
('six_dim_C', 'LT-链条悬挂', '链条悬挂', 'LT', 7, '["吊链","链吊"]', '灯体由可见金属链条垂挂'),
('six_dim_C', 'LT-轨道连接', '轨道连接', 'LT', 8, '["轨道","滑轨","磁吸连接"]', '灯头插接/吸附在条形轨道上，可沿轨移动'),
('six_dim_C', 'LT-鹅颈软管', '鹅颈软管', 'LT', 9, '["鹅颈管","万向软管","蛇管"]', '连接段为可任意弯曲定型的金属软管'),
('six_dim_C', 'LT-其他', '其他', 'LT', 10, NULL, '不属于以上任何连接形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 D 维度 × LT 灯具（9 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_D', 'LT-吊挂式', '吊挂式', 'LT', 1, '["吊装","悬吊","吊灯"]', '整体悬垂于天花板下方，与顶面间有明显空间'),
('six_dim_D', 'LT-吸顶式', '吸顶式', 'LT', 2, '["吸顶灯","贴顶","半吸顶"]', '灯体紧贴天花板安装，无悬垂间隙'),
('six_dim_D', 'LT-桌面台式', '桌面台式', 'LT', 3, '["台灯","桌灯","床头灯"]', '带自重底座放置于桌面，体量小可移动'),
('six_dim_D', 'LT-落地立式', '落地立式', 'LT', 4, '["落地灯","立式灯","地灯"]', '带落地底座（圆盘/大理石/三脚架），整体立于地面'),
('six_dim_D', 'LT-壁挂式', '壁挂式', 'LT', 5, '["壁灯","挂墙灯"]', '灯体固定于墙面，向墙外或上下出光'),
('six_dim_D', 'LT-嵌入式', '嵌入式', 'LT', 6, '["暗装","筒灯","射灯","嵌入灯"]', '灯体埋入吊顶/墙体，仅露出发光面或边框'),
('six_dim_D', 'LT-轨道磁吸式', '轨道磁吸式', 'LT', 7, '["轨道灯","磁吸轨道灯","滑轨灯","无主灯"]', '天花上可见条形轨道及轨道上多个灯头模组（磁吸与普通轨道肉眼难分，合并）'),
('six_dim_D', 'LT-夹持式', '夹持式', 'LT', 8, '["夹子灯","夹灯"]', '以弹簧夹/蟹钳夹固定在桌沿或床头'),
('six_dim_D', 'LT-其他', '其他', 'LT', 9, '["手提便携灯"]', '不属于以上任何安装形态')
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维 F 维度 × LT 灯具（10 项）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order, aliases, remark) VALUES
('six_dim_F', 'LT-无装饰', '无装饰', 'LT', 1, '["素面","极简","光身"]', '灯体表面无任何附加装饰构件'),
('six_dim_F', 'LT-水晶挂饰', '水晶挂饰', 'LT', 2, '["水晶吊坠","水晶条","珠帘"]', '灯架下垂挂水晶/玻璃坠子（看挂件，区别于 B 水晶罩串）'),
('six_dim_F', 'LT-金属饰件', '金属饰件', 'LT', 3, '["黄铜件","金属包边","铆钉"]', '灯体上有装饰性金属配件/包边/铆钉点缀'),
('six_dim_F', 'LT-镂空雕花', '镂空雕花', 'LT', 4, '["镂空花纹","雕花投影","冲花"]', '灯罩/灯体上有镂空花纹，透光形成图案投影'),
('six_dim_F', 'LT-流苏穗边', '流苏穗边', 'LT', 5, '["流苏","穗子","草裙摆"]', '罩体下缘垂挂成排流苏/穗条，形似草裙'),
('six_dim_F', 'LT-彩色玻璃', '彩色玻璃', 'LT', 6, '["蒂芙尼","彩玻","镶嵌玻璃"]', '出光面由多色玻璃拼嵌成图案'),
('six_dim_F', 'LT-绳编缠绕', '绳编缠绕', 'LT', 7, '["麻绳灯","绳艺","缠绳"]', '灯体/吊杆上有麻绳或织绳缠绕编织装饰'),
('six_dim_F', 'LT-羽毛装饰', '羽毛装饰', 'LT', 8, '["羽毛灯","羽饰"]', '灯体周圈环绕蓬松羽毛层'),
('six_dim_F', 'LT-木珠串饰', '木珠串饰', 'LT', 9, '["木珠灯","串珠"]', '灯体由木质圆珠成串垂挂构成装饰层'),
('six_dim_F', 'LT-其他', '其他', 'LT', 10, '["陶瓷花饰"]', '不属于以上任何装饰元素')
ON CONFLICT (dict_type, dict_code) DO NOTHING;
-- <<< SIX_DIM_DICT_SEED <<<

-- >>> SIX_DIM_SCHEMA_SEED (V30, do not edit manually) >>>
INSERT INTO six_dim_schema (category_code, dim_key, label, description, sort_order) VALUES
-- FS 座椅/沙发
('FS','A','轮廓形态','整体造型，如弧形、方盒形、蛋形、模块化组合',1),
('FS','B','靠背/背部特征','靠背高度、包裹性、编织镂空等',2),
('FS','C','扶手特征','扶手形态，如无扶手、环形扶手、实木扶手',3),
('FS','D','腿部/底座特征','腿部形态，如细腿、落地底座、金属框架',4),
('FS','E','表面材质','实木、皮革、布艺、金属等表面材质',5),
('FS','F','软包填充形态','软包饱满度、绗缝、拉扣等填充形态',6),
-- SF 沙发
('SF','A','轮廓形态','整体造型，如L型、弧形、一字型、模块化组合',1),
('SF','B','靠背/背部特征','靠背高度、倾斜角度、包裹性',2),
('SF','C','扶手特征','扶手形态，如无扶手、低扶手、宽厚扶手',3),
('SF','D','腿部/底座特征','落地式、细腿、金属脚、悬浮底座',4),
('SF','E','表面材质','皮革、布艺、羊羔绒、天鹅绒等',5),
('SF','F','软包填充形态','坐垫/靠背填充饱满度、绗缝、拉扣',6),
-- TB 茶几
('TB','A','整体造型/轮廓','茶几整体形态，如圆形、方形、异形、组合式',1),
('TB','B','台面形态','台面形状、厚度、悬浮/内嵌设计',2),
('TB','C','台面边缘/连接部','台面边缘处理、与支撑结构的连接方式',3),
('TB','D','桌腿/底座','桌腿形态，如细腿、敦实柱腿、金属框架、悬浮底座',4),
('TB','E','表面材质','大理石、玻璃、实木、金属等台面/框架材质',5),
('TB','F','收纳/功能件','抽屉、层板、旋转功能件等附加功能',6),
-- FC 柜类
('FC','A','整体造型/轮廓','柜体整体形态，如高柜、矮柜、组合柜、悬浮柜',1),
('FC','B','门板/抽屉特征','门板分割方式、抽屉排列、开放格/封闭格比例',2),
('FC','C','拉手/五金特征','拉手形态，如无拉手、明装拉手、隐藏拉手、金属拉手',3),
('FC','D','底座/支脚','落地式、高脚、金属支脚、悬浮挂墙',4),
('FC','E','表面材质','实木、板材、岩板、藤编、烤漆等表面材质',5),
('FC','F','内部结构/功能分区','内部隔层、抽屉、灯带、视听设备位等功能分区',6),
-- BS 吧椅
('BS','A','座面轮廓','座面形状，如圆形、方形、马蹄形',1),
('BS','B','靠背/背部特征','靠背高度、包裹性，无靠背/低靠背/高靠背',2),
('BS','C','扶手特征','扶手形态，如无扶手、小扶手、环形扶手',3),
('BS','D','底座/升降杆','固定底座、三脚/四脚底座、气压升降杆',4),
('BS','E','表面材质','皮革、金属、实木、塑料等',5),
('BS','F','软包填充形态','座面/靠背软包形态、厚度、绗缝',6),
-- OF 办公家具
('OF','A','整体造型/轮廓','家具整体形态，如班台、职员桌、会议桌、文件柜',1),
('OF','B','工作面/背部特征','台面/工作面形态，或柜类背板/门板特征',2),
('OF','C','侧部/连接部','侧板、挡板、线槽、扶手/侧翼结构',3),
('OF','D','支撑/底座','桌腿、桌架、柜脚、人体工学底盘',4),
('OF','E','表面材质','实木皮、板材、金属、网布、皮革等',5),
('OF','F','功能件/软包','抽屉、线槽、升降机构、坐垫软包等功能件',6),
-- DT 餐桌
('DT','A','造型','餐桌整体俯视轮廓，如长桌、圆桌、方桌、跑道形、岛台一体桌',1),
('DT','B','台面形态','台面厚度与构造，如平板薄面、厚台面、悬浮台面、转盘台面',2),
('DT','C','边缘/结构','台面边缘工艺与附属结构，如直边、马肚边、瀑布边、裙边/立水',3),
('DT','D','桌腿/底座','支撑形态，如四直腿、外八腿、喇叭/郁金香底座、落地箱式',4),
('DT','E','表面材质','岩板、实木、玻璃、大理石等台面/框架材质',5),
('DT','F','功能/展开方式','固定式、伸缩、折叠、旋转展开、升降、储物等',6),
-- BD 床
('BD','A','整体造型','床体整体形态，如齐边床、内嵌床、地台床、箱体床、上下床',1),
('BD','B','床头','床头/床屏形态（识别置信度最高维度），如软包大靠包、拉扣床头、平板薄床头、拱形床头',2),
('BD','C','床尾/床边','床尾屏板与床沿形态，如齐边无床尾、高床尾屏、宽边床沿',3),
('BD','D','床脚/底座','支脚与底座形态，如实木脚、金属脚、落地无脚、悬浮底座',4),
('BD','E','表面材质','真皮、布艺、实木、绒布等床体表面材质',5),
('BD','F','储物/功能','气压上掀储物、床体抽屉、床头功能区、床底灯带等',6),
-- LT 灯具
('LT','A','灯体造型','灯体整体剪影，如球形、长条形、枝形、分子式',1),
('LT','B','灯罩/出光','灯罩形态与出光方式，如玻璃罩、布艺罩、无罩裸光源',2),
('LT','C','灯臂/连接','灯体与安装面的连接结构，如吊线吊杆、弧形悬臂、折叠摇臂',3),
('LT','D','安装/底座','安装方式与底座形态，如吊挂式、吸顶式、落地立式、壁挂式',4),
('LT','E','表面材质','金属、玻璃、亚克力、藤竹、布艺等灯体材质',5),
('LT','F','装饰元素','附加装饰构件，如水晶挂饰、流苏穗边、彩色玻璃',6),
-- GENERIC 通用兜底
('GENERIC','A','整体造型/轮廓','产品整体外观形态',1),
('GENERIC','B','上部/背部特征','座椅靠背、柜类背板/门板、桌类台面',2),
('GENERIC','C','侧部/连接部','扶手、侧板、台面边缘、连接结构',3),
('GENERIC','D','支撑/底座','腿部、底座、支脚、底盘',4),
('GENERIC','E','表面材质','主要表面材质与纹理',5),
('GENERIC','F','功能/填充件','软包填充、抽屉、层板等功能件',6)
ON CONFLICT (category_code, dim_key) DO NOTHING;
-- <<< SIX_DIM_SCHEMA_SEED <<<

-- 工厂等级
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('factory_level', 'S', 'S级战略厂', 1),
('factory_level', 'A', 'A级核心厂', 2),
('factory_level', 'B', 'B级合作厂', 3),
('factory_level', 'C', 'C级备选厂', 4)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 复核状态
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('review_status', '待复核', '待复核', 1),
('review_status', '已确认', '已确认', 2),
('review_status', '存疑', '存疑', 3)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 报价置信度
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('quote_confidence', 'high', '高', 1),
('quote_confidence', 'mid', '中', 2),
('quote_confidence', 'low', '低', 3)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 尺寸码
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('size', 'S', '小号', 1),
('size', 'M', '中号', 2),
('size', 'L', '大号', 3),
('size', 'SINGLE', '单人位', 4),
('size', 'DOUBLE', '双人位', 5),
('size', 'TRIPLE', '三人位', 6)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 颜色码（精简为 15 个准确色系，覆盖风格百科高频颜色）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('color', 'CARAMEL', '焦糖棕', 1),
('color', 'BEIGE', '米白/奶油白/燕麦色', 2),
('color', 'CA', '驼色/奶咖色', 3),
('color', 'DB', '深棕/胡桃木色', 4),
('color', 'NATURAL', '原木色', 5),
('color', 'BLACK', '黑色', 6),
('color', 'GRAY', '灰色', 7),
('color', 'NAVY', '藏青/蓝色系', 8),
('color', 'GN', '绿色系', 9),
('color', 'PR', '紫色系', 10),
('color', 'RD', '红色系', 11),
('color', 'OR', '橙色系', 12),
('color', 'PK', '粉色系', 13),
('color', 'YE', '黄色系', 14),
('color', 'WT', '白色', 15)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 场景标签
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('scene', 'LIVING', '客厅', 1),
('scene', 'STUDY', '书房', 2),
('scene', 'BEDROOM', '卧室', 3),
('scene', 'CAFE', '咖啡厅', 4),
('scene', 'OFFICE', '办公室', 5),
('scene', 'HOTEL', '酒店', 6)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 设备类型
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('equipment_type', 'CNC', 'CNC五轴加工中心', 1),
('equipment_type', 'CUTTING', '自动裁皮机', 2),
('equipment_type', 'SEWING', '数控缝纫机', 3),
('equipment_type', 'HOT_PRESS', '热压机', 4),
('equipment_type', 'PAINTING', '喷漆房', 5),
('equipment_type', 'DRYING', '烘干房', 6),
('equipment_type', 'OTHER', '其他', 7)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 物流方式
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('logistics_method', 'SPECIAL', '专线物流', 1),
('logistics_method', 'DEPPON', '德邦', 2),
('logistics_method', 'SF', '顺丰', 3),
('logistics_method', 'SELF', '自提', 4)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 包装类型
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('packaging_type', 'CARTON', '纸箱', 1),
('packaging_type', 'WOODEN', '木架', 2),
('packaging_type', 'WOVEN', '编织袋', 3)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 木材类型
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('wood_type', 'PINE', '进口松木', 1),
('wood_type', 'OAK', '橡木', 2),
('wood_type', 'MIXED', '杂木', 3),
('wood_type', 'OTHER', '其他', 4)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 空间类型（风格数据库 case 用）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('room_type', 'LIVING_ROOM', '客厅', 1),
('room_type', 'BEDROOM', '卧室', 2),
('room_type', 'DINING_ROOM', '餐厅', 3),
('room_type', 'STUDY_ROOM', '书房', 4),
('room_type', 'OFFICE_EXECUTIVE', '总裁办公室', 5),
('room_type', 'OFFICE_STAFF', '职员办公区', 6),
('room_type', 'OFFICE_MEETING', '会议室', 7),
('room_type', 'CAFE', '咖啡厅', 8),
('room_type', 'HOTEL_ROOM', '酒店客房', 9),
('room_type', 'BAR', '酒吧', 10),
('room_type', 'OUTDOOR', '户外', 11)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- =================== V2 新增字典（工厂模块 + 导入增强） ===================

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
-- RBAC 与开发测试账号种子（同步自 database/V1__seed_data.sql）
-- 注意：缺少本段会导致重置后所有角色零权限，登录后全部接口 403。
-- ============================================================

-- 角色
INSERT INTO sys_role (role_code, role_name) VALUES
('ADMIN', '系统管理员'),
('EDITOR', '编辑员'),
('VIEWER', '浏览者'),
('FACTORY_ADMIN', '工厂管理员'),
('DESIGNER', '设计师'),
('USER', '普通用户')
ON CONFLICT (role_code) DO NOTHING;

-- 权限
INSERT INTO sys_permission (permission_code, permission_name) VALUES
('product:read', '查看产品'),
('product:create', '新品录入'),
('product:update', '编辑产品元数据'),
('product:delete', '删除产品'),
('product:review', '复核产品'),
('product:import', '批量导入产品'),
('factory:read', '查看工厂'),
('factory:create', '创建工厂'),
('factory:update', '编辑工厂'),
('factory:delete', '删除工厂'),
('rsku:read', '查看报价'),
('rsku:create', '新增报价'),
('rsku:update', '编辑报价'),
('rsku:delete', '删除报价'),
('rsku:import', '批量导入报价'),
('quote:read', '查看报价单'),
('quote:generate', '生成报价单'),
('quote:export', '导出报价单'),
('scheme:read', '查看搭配方案'),
('scheme:create', '创建搭配方案'),
('scheme:update', '编辑搭配方案'),
('scheme:delete', '删除搭配方案'),
('dict:create', '创建字典项'),
('dict:update', '编辑字典项'),
('user:read', '查看用户'),
('user:create', '创建用户'),
('user:update', '编辑用户'),
('user:delete', '删除用户'),
('user:reset-password', '重置密码'),
('admin:async-metrics', '查看异步线程池指标'),
('admin:vector-backfill', '向量回填'),
('collection:read', '查看产品集'),
('collection:create', '创建产品集'),
('collection:update', '编辑产品集'),
('collection:delete', '删除产品集'),
('capability:read', '查看工厂产品能力'),
('capability:create', '创建工厂产品能力'),
('capability:update', '编辑工厂产品能力'),
('capability:delete', '删除工厂产品能力'),
('designer:profile:read', '查看设计师画像'),
('designer:profile:update', '编辑设计师画像'),
('recommendation:score:config:read', '查看推荐打分配置'),
('recommendation:score:config:update', '编辑推荐打分配置'),
('scheme:candidate:read', '查看 AI 推荐候选'),
('scheme:candidate:create', '创建 AI 推荐候选'),
('scheme:candidate:update', '编辑 AI 推荐候选'),
('scheme:candidate:delete', '删除 AI 推荐候选')
ON CONFLICT (permission_code) DO NOTHING;

-- ADMIN 拥有所有权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'ADMIN'
ON CONFLICT DO NOTHING;

-- EDITOR：除用户管理和高级 admin 外的全部权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'EDITOR'
  AND p.permission_code NOT IN ('user:read', 'user:create', 'user:update', 'user:delete', 'user:reset-password', 'admin:async-metrics', 'admin:vector-backfill', 'recommendation:score:config:read', 'recommendation:score:config:update')
ON CONFLICT DO NOTHING;

-- VIEWER：只读
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'VIEWER'
  AND p.permission_code IN ('product:read', 'factory:read', 'rsku:read', 'quote:read', 'scheme:read', 'collection:read', 'capability:read')
ON CONFLICT DO NOTHING;

-- FACTORY_ADMIN：自己工厂产品 + 工厂资料维护 + 报价相关 + 只读
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'FACTORY_ADMIN'
  AND p.permission_code IN ('product:read', 'product:create', 'product:update', 'factory:read', 'factory:update', 'rsku:read', 'rsku:create', 'rsku:update', 'rsku:delete', 'rsku:import', 'capability:read')
ON CONFLICT DO NOTHING;

-- DESIGNER：方案/报价 + 只读
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'DESIGNER'
  AND p.permission_code IN ('product:read', 'factory:read', 'rsku:read', 'quote:read', 'quote:generate', 'quote:export', 'scheme:read', 'scheme:create', 'scheme:update', 'scheme:delete', 'collection:read', 'capability:read', 'designer:profile:read', 'designer:profile:update', 'scheme:candidate:read', 'scheme:candidate:create', 'scheme:candidate:update', 'scheme:candidate:delete')
ON CONFLICT DO NOTHING;

-- USER：只读
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'USER'
  AND p.permission_code IN ('product:read', 'factory:read', 'rsku:read', 'quote:read', 'scheme:read', 'collection:read', 'capability:read')
ON CONFLICT DO NOTHING;

-- 项目类型字典（V4 并入）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('project_type', 'whole_house', '全屋', 1),
('project_type', 'space', '单空间', 2),
('project_type', 'custom', '定制', 3)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 项目权限（V4 并入；ADMIN 全量与 EDITOR 排除式映射自动覆盖）
INSERT INTO sys_permission (permission_code, permission_name) VALUES
('project:read', '查看设计项目'),
('project:create', '创建设计项目'),
('project:update', '编辑设计项目'),
('project:delete', '删除设计项目')
ON CONFLICT (permission_code) DO NOTHING;

-- DESIGNER：项目全量权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'DESIGNER'
  AND p.permission_code LIKE 'project:%'
ON CONFLICT DO NOTHING;

-- ADMIN / EDITOR：项目全量权限（通用映射先于本权限插入执行，需显式补插）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code IN ('ADMIN', 'EDITOR')
  AND p.permission_code LIKE 'project:%'
ON CONFLICT DO NOTHING;

-- VIEWER / USER：项目只读
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code IN ('VIEWER', 'USER')
  AND p.permission_code = 'project:read'
ON CONFLICT DO NOTHING;

-- 订单全局折扣率（V5 并入）
INSERT INTO sys_config (config_key, config_value, remark) VALUES
('order.price_rate', '1', '订单全局折扣率')
ON CONFLICT (config_key) DO NOTHING;

-- 订单状态字典（V5 并入）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('design_order_status', 'PENDING', '待确认', 1),
('design_order_status', 'CONFIRMED', '已确认', 2),
('design_order_status', 'PRODUCING', '生产中', 3),
('design_order_status', 'COMPLETED', '已完成', 4),
('design_order_status', 'CANCELLED', '已取消', 5)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 订单权限（V5 并入；方案约定 ADMIN + DESIGNER 授予，显式补插）
INSERT INTO sys_permission (permission_code, permission_name) VALUES
('order:read', '查看订单'),
('order:create', '创建订单'),
('order:update', '编辑订单'),
('order:delete', '删除订单')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code IN ('ADMIN', 'DESIGNER')
  AND p.permission_code LIKE 'order:%'
ON CONFLICT DO NOTHING;

-- 收藏夹权限（V9 并入）：所有登录角色均可管理自己的收藏
INSERT INTO sys_permission (permission_code, permission_name) VALUES
('favorite:read', '查看我的收藏'),
('favorite:write', '管理我的收藏')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE p.permission_code IN ('favorite:read', 'favorite:write')
ON CONFLICT DO NOTHING;

-- =================== 开发测试账号（仅在开发/演示环境使用） ===================

-- 测试工厂
INSERT INTO factory_master (factory_code, factory_name, factory_level, region, status) VALUES
('TEST', '测试工厂', 'A', '广东', 'active')
ON CONFLICT (factory_code) DO NOTHING;

-- 开发/演示环境测试账号（密码均为：rsdp-dev-2026!）
-- 生产环境部署后应立即通过管理后台修改或删除这些账号。
-- DefaultAdminInitializer 仅在新库且无用户时生成随机密码。
-- 注意：ON CONFLICT 必须 DO NOTHING —— 重复执行时绝不能覆盖用户已修改的密码（认证回退风险）。
INSERT INTO sys_user (user_id, username, password_hash, nickname, company_name, group_name, status, view_full_catalog) VALUES
('USER-ADMIN-00000001', 'admin', '$2a$10$sxt6z8NitIDSWB7BJQS0VeZIP52b35tsDpL7RDWGMhqB42X85cp/6', '系统管理员', 'RSDP 平台', '平台运营组', 'active', true),
('USER-EDITOR-00000001', 'editor', '$2a$10$sxt6z8NitIDSWB7BJQS0VeZIP52b35tsDpL7RDWGMhqB42X85cp/6', '编辑员', 'RSDP 平台', '内容编辑组', 'active', true),
('USER-VIEWER-00000001', 'viewer', '$2a$10$sxt6z8NitIDSWB7BJQS0VeZIP52b35tsDpL7RDWGMhqB42X85cp/6', '浏览者', 'RSDP 平台', '平台运营组', 'active', false),
('USER-DESIGNER-00000001', 'designer', '$2a$10$sxt6z8NitIDSWB7BJQS0VeZIP52b35tsDpL7RDWGMhqB42X85cp/6', '设计师', '示例设计工作室', '方案一组', 'active', false),
('USER-FACTORY-00000001', 'factory', '$2a$10$sxt6z8NitIDSWB7BJQS0VeZIP52b35tsDpL7RDWGMhqB42X85cp/6', '工厂管理员', '测试家具工厂', '销售部', 'active', false),
('USER-USER-00000001', 'user', '$2a$10$sxt6z8NitIDSWB7BJQS0VeZIP52b35tsDpL7RDWGMhqB42X85cp/6', '普通用户', '示例设计工作室', '方案二组', 'active', false)
ON CONFLICT (username) DO NOTHING;

-- 测试用户角色关联
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.user_id, r.role_id
FROM sys_user u, sys_role r
WHERE u.username IN ('admin', 'editor', 'viewer', 'designer', 'factory', 'user')
  AND r.role_code = CASE u.username
    WHEN 'admin' THEN 'ADMIN'
    WHEN 'editor' THEN 'EDITOR'
    WHEN 'viewer' THEN 'VIEWER'
    WHEN 'designer' THEN 'DESIGNER'
    WHEN 'factory' THEN 'FACTORY_ADMIN'
    WHEN 'user' THEN 'USER'
  END
ON CONFLICT (user_id, role_id) DO NOTHING;

-- 工厂管理员绑定测试工厂
INSERT INTO sys_user_factory (user_id, factory_code)
SELECT u.user_id, 'TEST'
FROM sys_user u
WHERE u.username = 'factory'
ON CONFLICT (user_id, factory_code) DO NOTHING;

-- 企业实体迁移（V13 并入，幂等）：company_name/group_name 文本 → 实体（同名企业合并）
INSERT INTO company (company_id, company_name, owner_id)
SELECT 'COM-' || gen_random_uuid()::text,
       dc.company_name,
       (SELECT u.user_id FROM sys_user u
        WHERE u.company_name = dc.company_name
        ORDER BY u.created_at ASC NULLS LAST, u.user_id ASC
        LIMIT 1)
FROM (SELECT DISTINCT company_name FROM sys_user
      WHERE company_name IS NOT NULL AND btrim(company_name) <> '') dc
WHERE NOT EXISTS (SELECT 1 FROM company c WHERE c.company_name = dc.company_name);

UPDATE sys_user u SET company_id = c.company_id
FROM company c
WHERE u.company_id IS NULL AND u.company_name = c.company_name;

INSERT INTO member_group (group_id, company_id, group_name)
SELECT 'GRP-' || gen_random_uuid()::text, c.company_id, dg.group_name
FROM (SELECT DISTINCT company_name, group_name FROM sys_user
      WHERE company_name IS NOT NULL AND btrim(company_name) <> ''
        AND group_name IS NOT NULL AND btrim(group_name) <> '') dg
JOIN company c ON c.company_name = dg.company_name
WHERE NOT EXISTS (SELECT 1 FROM member_group g
                  WHERE g.company_id = c.company_id AND g.group_name = dg.group_name);

UPDATE sys_user u SET group_id = g.group_id
FROM company c
JOIN member_group g ON g.company_id = c.company_id
WHERE u.group_id IS NULL
  AND u.company_name = c.company_name
  AND u.group_name = g.group_name;


-- ============================================================
-- 自增序列对齐（接入自 database/V3__align_sequences.sql）
-- 背景：以下 8 张表的主键实体已改为 IdType.AUTO，由数据库自增序列生成主键。
--       本段保证重置后序列与当前 MAX(id) 一致，避免后续自增值与已有主键冲突。
--       幂等：可重复执行；空表对齐到 1，非空表对齐到 MAX(id)。
-- ============================================================
SELECT setval('sys_role_role_id_seq',                 COALESCE((SELECT MAX(role_id) FROM sys_role), 1),                 (SELECT MAX(role_id) IS NOT NULL FROM sys_role));
SELECT setval('sys_permission_permission_id_seq',     COALESCE((SELECT MAX(permission_id) FROM sys_permission), 1),     (SELECT MAX(permission_id) IS NOT NULL FROM sys_permission));
SELECT setval('sys_role_permission_id_seq',           COALESCE((SELECT MAX(id) FROM sys_role_permission), 1),           (SELECT MAX(id) IS NOT NULL FROM sys_role_permission));
SELECT setval('sys_user_role_id_seq',                 COALESCE((SELECT MAX(id) FROM sys_user_role), 1),                 (SELECT MAX(id) IS NOT NULL FROM sys_user_role));
SELECT setval('sys_user_factory_id_seq',              COALESCE((SELECT MAX(id) FROM sys_user_factory), 1),              (SELECT MAX(id) IS NOT NULL FROM sys_user_factory));
SELECT setval('rspu_factory_mapping_mapping_id_seq',  COALESCE((SELECT MAX(mapping_id) FROM rspu_factory_mapping), 1),  (SELECT MAX(mapping_id) IS NOT NULL FROM rspu_factory_mapping));
SELECT setval('factory_lead_time_rule_rule_id_seq',   COALESCE((SELECT MAX(rule_id) FROM factory_lead_time_rule), 1),   (SELECT MAX(rule_id) IS NOT NULL FROM factory_lead_time_rule));
SELECT setval('excel_import_row_row_id_seq',          COALESCE((SELECT MAX(row_id) FROM excel_import_row), 1),          (SELECT MAX(row_id) IS NOT NULL FROM excel_import_row));
SELECT setval('dict_alias_id_seq',                    COALESCE((SELECT MAX(id) FROM dict_alias), 1),                    (SELECT MAX(id) IS NOT NULL FROM dict_alias));
SELECT setval('dict_unresolved_value_id_seq',         COALESCE((SELECT MAX(id) FROM dict_unresolved_value), 1),         (SELECT MAX(id) IS NOT NULL FROM dict_unresolved_value));

-- ============================================================
-- 收藏夹文件夹/模板标签迁移（V14 并入，幂等；重置后一般为空库，迁移为 no-op）
-- ============================================================
INSERT INTO favorite_folder (folder_id, user_id, folder_name)
SELECT 'FAVD-' || gen_random_uuid()::text, d.user_id, d.group_name
FROM (SELECT DISTINCT user_id, group_name FROM user_favorite
      WHERE group_name IS NOT NULL AND btrim(group_name) <> '') d
WHERE NOT EXISTS (SELECT 1 FROM favorite_folder f
                  WHERE f.user_id = d.user_id AND f.folder_name = d.group_name);

UPDATE user_favorite uf SET folder_id = f.folder_id
FROM favorite_folder f
WHERE uf.folder_id IS NULL
  AND uf.user_id = f.user_id
  AND uf.group_name = f.folder_name;

INSERT INTO template_tag (tag_id, tag_name)
SELECT 'TAG-' || gen_random_uuid()::text, t.tag_name
FROM (
    SELECT DISTINCT jsonb_array_elements_text(s.template_tags::jsonb) AS tag_name
    FROM scheme s
    WHERE s.template_tags IS NOT NULL AND s.template_tags ~ '^\s*\['
) t
WHERE btrim(t.tag_name) <> ''
ON CONFLICT (tag_name) DO NOTHING;

-- ============================================================
-- 官网 CMS（V15 并入）：Banner / 落地案例 / 内容配置 / 自定义字典 / 产品定制
-- ============================================================
CREATE TABLE IF NOT EXISTS platform_banner (
    banner_id   VARCHAR(64) PRIMARY KEY,
    position    VARCHAR(32) NOT NULL DEFAULT 'home_top',
    title       VARCHAR(128),
    image_id    VARCHAR(64) NOT NULL,
    link_type   VARCHAR(16) NOT NULL DEFAULT 'none',
    link_value  VARCHAR(512),
    sort_order  INT NOT NULL DEFAULT 0,
    status      VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_platform_banner_position ON platform_banner(position, status, sort_order);

CREATE TABLE IF NOT EXISTS platform_case (
    case_id        VARCHAR(64) PRIMARY KEY,
    title          VARCHAR(128) NOT NULL,
    cover_image_id VARCHAR(64),
    content        TEXT,
    sort_order     INT NOT NULL DEFAULT 0,
    status         VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_platform_case_status ON platform_case(status, sort_order);

CREATE TABLE IF NOT EXISTS platform_content (
    content_id   VARCHAR(64) PRIMARY KEY,
    code         VARCHAR(64) NOT NULL UNIQUE,
    title        VARCHAR(128),
    content_type VARCHAR(16) NOT NULL DEFAULT 'rich_text',
    content      TEXT,
    status       VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS platform_custom_dict (
    dict_id    VARCHAR(64) PRIMARY KEY,
    dict_name  VARCHAR(64) NOT NULL,
    dict_type  VARCHAR(32) NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (dict_type, dict_name)
);

CREATE TABLE IF NOT EXISTS platform_customized (
    customized_id  VARCHAR(64) PRIMARY KEY,
    title          VARCHAR(128) NOT NULL,
    cover_image_id VARCHAR(64),
    description    VARCHAR(512),
    link_value     VARCHAR(512),
    sort_order     INT NOT NULL DEFAULT 0,
    status         VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_platform_customized_status ON platform_customized(status, sort_order);

-- 官网内容种子（V15 并入）：服务协议 + 客服咨询（占位文案，运营可在管理端修改）
INSERT INTO platform_content (content_id, code, title, content_type, content) VALUES
('CONT-USER-AGREEMENT', 'platform_user_agreement', '服务协议', 'rich_text',
 '<h3>RSDP 家居全案平台服务协议</h3><p>欢迎使用 RSDP 家居全案平台。请您在使用本平台前仔细阅读本协议。</p><p>1. 本平台提供的产品信息、价格信息仅供参考，实际以双方确认的订单为准。</p><p>2. 您应当妥善保管账号信息，因账号保管不善造成的损失由您自行承担。</p><p>3. 未经许可，不得将平台数据用于任何商业用途。</p><p>（本内容为占位文案，请在管理端「官网内容-内容管理」中替换为正式协议。）</p>'),
('CONT-CONSULTING-SERVICE', 'platform_consulting_service', '客服咨询', 'rich_text',
 '<h3>联系客服</h3><p>如需产品咨询、报价或售后服务，请通过以下方式联系我们：</p><p>工作时间：周一至周五 9:00 - 18:00</p><p>（本内容为占位文案，请在管理端「官网内容-内容管理」中配置真实联系方式。）</p>')
ON CONFLICT (code) DO NOTHING;
