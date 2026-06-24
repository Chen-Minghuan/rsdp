-- RSDP 多模态录入数据库初始化脚本
-- 基于 docs/RSDP-多模态录入数据库设计.md

-- RSPU 设计原型主表
CREATE TABLE IF NOT EXISTS rspu_master (
    rspu_id TEXT PRIMARY KEY,
    category_code TEXT NOT NULL,
    category_path TEXT NOT NULL,
    positioning_label TEXT NOT NULL,
    six_dim_tags TEXT,
    style_vector TEXT,
    color_primary_name TEXT,
    color_primary_hsv TEXT,
    color_secondary TEXT,
    material_tags TEXT,
    scene_tags TEXT,
    product_dimensions TEXT,
    occupancy_static TEXT,
    occupancy_dynamic TEXT,
    reference_price_band TEXT,
    budget_range TEXT,
    warranty_years INTEGER,
    key_specs TEXT,
    status TEXT DEFAULT 'active',
    review_status TEXT DEFAULT '待复核',
    aesthetics_confidence TEXT,
    source_agent_version TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP
);

-- RSKU 供应单元子表
CREATE TABLE IF NOT EXISTS rsku_supply (
    rsku_id TEXT PRIMARY KEY,
    rspu_id TEXT NOT NULL,
    factory_code TEXT NOT NULL,
    material_code TEXT NOT NULL,
    factory_sku TEXT,
    factory_price REAL,
    price_band TEXT,
    material_description TEXT,
    lead_time_days INTEGER,
    moq INTEGER,
    structure_strength_rating TEXT,
    flame_retardant_capability TEXT,
    factory_photo_path TEXT,
    factory_credit_score INTEGER,
    on_time_rate REAL,
    quality_return_rate REAL,
    diff_notes TEXT,
    quote_confidence TEXT,
    review_status TEXT DEFAULT '待复核',
    price_updated DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code)
);

-- 工厂档案表
CREATE TABLE IF NOT EXISTS factory_master (
    factory_code TEXT PRIMARY KEY,
    factory_name TEXT NOT NULL,
    factory_level TEXT NOT NULL,
    home_commercial_tag TEXT,
    certification TEXT,
    engineering_cases TEXT,
    region TEXT,
    address TEXT,
    contact_person TEXT,
    contact_phone TEXT,
    first_audit_date DATE,
    next_visit_date DATE,
    notes TEXT,
    status TEXT DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

-- 图片资源表
CREATE TABLE IF NOT EXISTS image_assets (
    image_id TEXT PRIMARY KEY,
    rspu_id TEXT,
    rsku_id TEXT,
    image_type TEXT NOT NULL,
    storage_path TEXT NOT NULL,
    storage_url TEXT,
    file_size INTEGER,
    width INTEGER,
    height INTEGER,
    format TEXT,
    is_primary BOOLEAN DEFAULT 0,
    ai_processed BOOLEAN DEFAULT 0,
    quality_score REAL,
    uploaded_by TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (rsku_id) REFERENCES rsku_supply(rsku_id)
);

-- AI 识别记录表
CREATE TABLE IF NOT EXISTS ai_recognition (
    recognition_id TEXT PRIMARY KEY,
    image_id TEXT,
    rspu_id TEXT,
    task_id TEXT,
    model_name TEXT,
    recognition_type TEXT,
    endpoint TEXT,
    input_data TEXT,
    output_data TEXT,
    parsed_style TEXT,
    parsed_six_dim TEXT,
    parsed_color_hsv TEXT,
    parsed_scene_tags TEXT,
    parsed_ocr TEXT,
    confidence TEXT,
    processing_time_ms INTEGER,
    status TEXT,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (image_id) REFERENCES image_assets(image_id),
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id)
);

-- 异步任务表
CREATE TABLE IF NOT EXISTS async_task (
    task_id TEXT PRIMARY KEY,
    task_type TEXT NOT NULL,
    status TEXT DEFAULT 'pending',
    progress INTEGER DEFAULT 0,
    input_data TEXT,
    result_data TEXT,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

-- 审计日志表
CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    table_name TEXT NOT NULL,
    record_id TEXT NOT NULL,
    action TEXT NOT NULL,
    old_value TEXT,
    new_value TEXT,
    operator TEXT,
    ip_address TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 字典表
CREATE TABLE IF NOT EXISTS category_dict (
    dict_type TEXT NOT NULL,
    dict_code TEXT NOT NULL,
    dict_name TEXT NOT NULL,
    dict_name_en TEXT,
    parent_code TEXT,
    sort_order INTEGER,
    status TEXT DEFAULT 'active',
    PRIMARY KEY (dict_type, dict_code)
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_rspu_category ON rspu_master(category_code, status);
CREATE INDEX IF NOT EXISTS idx_rspu_positioning ON rspu_master(positioning_label, category_code);
CREATE INDEX IF NOT EXISTS idx_rspu_review ON rspu_master(review_status);
CREATE INDEX IF NOT EXISTS idx_rspu_meta ON rspu_master(category_code, positioning_label, status) WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_rsku_rspu ON rsku_supply(rspu_id);
CREATE INDEX IF NOT EXISTS idx_rsku_factory ON rsku_supply(factory_code);
CREATE INDEX IF NOT EXISTS idx_rsku_material ON rsku_supply(material_code);
CREATE UNIQUE INDEX IF NOT EXISTS idx_rsku_unique ON rsku_supply(rspu_id, factory_code, material_code);

CREATE INDEX IF NOT EXISTS idx_image_rspu ON image_assets(rspu_id, image_type);
CREATE INDEX IF NOT EXISTS idx_image_primary ON image_assets(rspu_id, is_primary);

CREATE INDEX IF NOT EXISTS idx_ai_image ON ai_recognition(image_id, recognition_type);
CREATE INDEX IF NOT EXISTS idx_ai_rspu ON ai_recognition(rspu_id, created_at);

CREATE INDEX IF NOT EXISTS idx_audit_record ON audit_log(table_name, record_id, created_at);
CREATE INDEX IF NOT EXISTS idx_task_status ON async_task(status, created_at);
