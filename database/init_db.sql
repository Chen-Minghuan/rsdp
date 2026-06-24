-- RSDP 多模态录入数据库初始化脚本（PostgreSQL 版本）
-- 基于 docs/RSDP-多模态录入数据库设计.md
-- 建议：JSON 字段可使用 JSONB 类型以获得更好的查询性能和索引支持

-- RSPU 设计原型主表
CREATE TABLE IF NOT EXISTS rspu_master (
    rspu_id VARCHAR(64) PRIMARY KEY,
    category_code VARCHAR(16) NOT NULL,
    category_path TEXT NOT NULL,
    positioning_label VARCHAR(64) NOT NULL,
    six_dim_tags TEXT,                       -- 建议使用 JSONB 类型以获得更好性能
    style_vector TEXT,                       -- 512维向量备份：由 SpringBoot 调用 Ollama /api/embeddings 生成
    color_primary_name VARCHAR(64),
    color_primary_hsv TEXT,
    color_secondary TEXT,
    material_tags TEXT,
    scene_tags TEXT,
    product_dimensions TEXT,
    occupancy_static TEXT,
    occupancy_dynamic TEXT,
    reference_price_band VARCHAR(16),
    budget_range TEXT,
    warranty_years INTEGER,
    key_specs TEXT,
    status VARCHAR(16) DEFAULT 'active',
    review_status VARCHAR(16) DEFAULT '待复核',
    aesthetics_confidence VARCHAR(16),
    source_agent_version VARCHAR(64),        -- Ollama 模型版本，如 qwen2.5-vl:7b / nomic-embed-text
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP
);

-- 工厂档案表
CREATE TABLE IF NOT EXISTS factory_master (
    factory_code VARCHAR(16) PRIMARY KEY,
    factory_name VARCHAR(128) NOT NULL,
    factory_level VARCHAR(8) NOT NULL,
    home_commercial_tag VARCHAR(16),
    certification TEXT,
    engineering_cases TEXT,
    region VARCHAR(64),
    address TEXT,
    contact_person VARCHAR(64),
    contact_phone VARCHAR(32),
    first_audit_date DATE,
    next_visit_date DATE,
    notes TEXT,
    status VARCHAR(16) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

-- RSKU 供应单元子表
CREATE TABLE IF NOT EXISTS rsku_supply (
    rsku_id VARCHAR(64) PRIMARY KEY,
    rspu_id VARCHAR(64) NOT NULL,
    factory_code VARCHAR(16) NOT NULL,
    material_code VARCHAR(16) NOT NULL,
    factory_sku VARCHAR(64),
    factory_price DECIMAL(18, 2),            -- 建议 AES 加密存储
    price_band VARCHAR(16),
    material_description TEXT,
    lead_time_days INTEGER,
    moq INTEGER,
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
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code)
);

-- 图片资源表
CREATE TABLE IF NOT EXISTS image_assets (
    image_id VARCHAR(64) PRIMARY KEY,
    rspu_id VARCHAR(64),
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
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (rsku_id) REFERENCES rsku_supply(rsku_id)
);

-- AI 识别记录表
CREATE TABLE IF NOT EXISTS ai_recognition (
    recognition_id VARCHAR(64) PRIMARY KEY,
    image_id VARCHAR(64),
    rspu_id VARCHAR(64),
    task_id VARCHAR(64),
    model_name VARCHAR(64),                  -- Ollama 模型名，如 qwen2.5-vl:7b / nomic-embed-text
    recognition_type VARCHAR(16),            -- encode / label / judge
    endpoint TEXT,                           -- 调用端点，如 http://localhost:11434/api/generate
    input_data TEXT,                         -- Ollama 请求参数摘要
    output_data TEXT,                        -- Ollama 原始响应
    parsed_style VARCHAR(64),
    parsed_six_dim TEXT,
    parsed_color_hsv TEXT,
    parsed_scene_tags TEXT,
    parsed_ocr TEXT,
    confidence VARCHAR(16),
    processing_time_ms INTEGER,
    status VARCHAR(16),
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (image_id) REFERENCES image_assets(image_id),
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id)
);

-- 异步任务表
CREATE TABLE IF NOT EXISTS async_task (
    task_id VARCHAR(64) PRIMARY KEY,
    task_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) DEFAULT 'pending',
    progress INTEGER DEFAULT 0,
    input_data TEXT,
    result_data TEXT,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

-- 审计日志表
CREATE TABLE IF NOT EXISTS audit_log (
    id SERIAL PRIMARY KEY,
    table_name VARCHAR(64) NOT NULL,
    record_id VARCHAR(64) NOT NULL,
    action VARCHAR(16) NOT NULL,
    old_value TEXT,
    new_value TEXT,
    operator VARCHAR(64),
    ip_address VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 字典表
CREATE TABLE IF NOT EXISTS category_dict (
    dict_type VARCHAR(32) NOT NULL,
    dict_code VARCHAR(32) NOT NULL,
    dict_name VARCHAR(64) NOT NULL,
    dict_name_en VARCHAR(64),
    parent_code VARCHAR(32),
    sort_order INTEGER,
    status VARCHAR(16) DEFAULT 'active',
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

-- 如果 JSON 字段改为 JSONB 类型，可以创建 GIN 索引加速 JSON 查询
-- CREATE INDEX idx_rspu_six_dim_gin ON rspu_master USING GIN (six_dim_tags jsonb_path_ops);
