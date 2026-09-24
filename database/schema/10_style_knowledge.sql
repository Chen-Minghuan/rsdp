-- ============================================================
-- RSDP 基线 DDL · 10 风格知识库域（10_style_knowledge.sql）
-- 包含表：knowledge_product_type, knowledge_product_attribute, style_case, style_element, style_matching_formula, product_style_match, matching_feedback
-- 执行顺序：schema/ 目录按文件名 01 → 12 → 99 依次执行（编号即执行顺序，基线由原 V1__init_db.sql 按域拆分而来）
-- 同步约定：新增/修改本域表结构时须同步 ops/reset_db.sql，约定详见 database/README.md
-- ============================================================
-- 二级产品类型知识层：保持与 RSPU/业务编码解耦，本期仅供 Shadow Mode 与后续知识能力读取
CREATE TABLE IF NOT EXISTS knowledge_product_type (
    type_code VARCHAR(64) PRIMARY KEY,
    type_name VARCHAR(64) NOT NULL,
    business_category_code VARCHAR(16) NOT NULL,
    schema_category_code VARCHAR(16) NOT NULL,
    parent_type_code VARCHAR(64),
    aliases JSONB NOT NULL DEFAULT '[]'::jsonb,
    room_tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    description TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    knowledge_version VARCHAR(16) NOT NULL DEFAULT '1.0',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_type_parent FOREIGN KEY (parent_type_code) REFERENCES knowledge_product_type(type_code)
);
CREATE INDEX IF NOT EXISTS idx_product_type_business_category
    ON knowledge_product_type(business_category_code, status, sort_order);
CREATE INDEX IF NOT EXISTS idx_product_type_schema_category
    ON knowledge_product_type(schema_category_code, status);

-- 产品属性定义知识层：只定义语义、值类型及 RSPU/VARIANT/RSKU 归属，不保存产品属性值
CREATE TABLE IF NOT EXISTS knowledge_product_attribute (
    attribute_id VARCHAR(96) PRIMARY KEY,
    attribute_code VARCHAR(64) NOT NULL,
    attribute_name VARCHAR(64) NOT NULL,
    category_dict_type VARCHAR(32) NOT NULL DEFAULT 'category',
    category_code VARCHAR(16),
    product_type_code VARCHAR(64),
    value_layer VARCHAR(16) NOT NULL,
    value_type VARCHAR(16) NOT NULL,
    unit VARCHAR(16),
    enum_options JSONB NOT NULL DEFAULT '[]'::jsonb,
    aliases JSONB NOT NULL DEFAULT '[]'::jsonb,
    description TEXT NOT NULL,
    ai_extractable BOOLEAN NOT NULL DEFAULT FALSE,
    required_level VARCHAR(16) NOT NULL DEFAULT 'optional',
    sort_order INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    knowledge_version VARCHAR(16) NOT NULL DEFAULT '1.0',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_product_attribute_product_type
        FOREIGN KEY (product_type_code) REFERENCES knowledge_product_type(type_code),
    CONSTRAINT chk_product_attribute_scope
        CHECK (category_code IS NULL OR product_type_code IS NULL),
    CONSTRAINT chk_product_attribute_layer
        CHECK (value_layer IN ('RSPU', 'VARIANT', 'RSKU')),
    CONSTRAINT chk_product_attribute_value_type
        CHECK (value_type IN ('text', 'integer', 'decimal', 'boolean', 'enum', 'multi_enum')),
    CONSTRAINT chk_product_attribute_required_level
        CHECK (required_level IN ('optional', 'recommended', 'required'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_product_attribute_scope_code
    ON knowledge_product_attribute (
        COALESCE(category_code, ''),
        COALESCE(product_type_code, ''),
        attribute_code
    );
CREATE INDEX IF NOT EXISTS idx_product_attribute_category
    ON knowledge_product_attribute(category_code, status, sort_order);
CREATE INDEX IF NOT EXISTS idx_product_attribute_product_type
    ON knowledge_product_attribute(product_type_code, status, sort_order);
CREATE INDEX IF NOT EXISTS idx_product_attribute_layer
    ON knowledge_product_attribute(value_layer, status);

-- =================== 风格数据库 Skill 表 ===================

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
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
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
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
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
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    FOREIGN KEY (dict_type, style_code) REFERENCES category_dict(dict_type, dict_code)
);

-- 产品-风格匹配结果：产品录入后自动计算
CREATE TABLE IF NOT EXISTS product_style_match (
    match_id BIGSERIAL PRIMARY KEY,
    rspu_id VARCHAR(64) NOT NULL,
    dict_type VARCHAR(32) NOT NULL DEFAULT 'style',
    style_code VARCHAR(32) NOT NULL,
    element_match JSONB,
    formula_scores JSONB,
    overall_score DECIMAL(5,4),
    confidence VARCHAR(16),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    UNIQUE (rspu_id, style_code),
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (dict_type, style_code) REFERENCES category_dict(dict_type, dict_code)
);

-- 推荐反馈：用于后续优化公式
CREATE TABLE IF NOT EXISTS matching_feedback (
    feedback_id BIGSERIAL PRIMARY KEY,
    rspu_id VARCHAR(64) NOT NULL,
    recommended_rspu_id VARCHAR(64) NOT NULL,
    formula_id VARCHAR(64),
    score DECIMAL(5,4),
    feedback VARCHAR(16),
    reason TEXT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (recommended_rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (formula_id) REFERENCES style_matching_formula(formula_id)
);

-- 风格数据库索引
CREATE INDEX IF NOT EXISTS idx_style_case_style ON style_case(style_code, is_success);
CREATE INDEX IF NOT EXISTS idx_style_case_room ON style_case(room_type, is_success);
CREATE INDEX IF NOT EXISTS idx_style_element_case ON style_element(case_id);
CREATE INDEX IF NOT EXISTS idx_style_element_type ON style_element(element_type, normalized_code);
CREATE INDEX IF NOT EXISTS idx_formula_style_room ON style_matching_formula(style_code, room_type, status);
CREATE INDEX IF NOT EXISTS idx_product_match_rspu ON product_style_match(rspu_id);
CREATE INDEX IF NOT EXISTS idx_product_match_score ON product_style_match(overall_score DESC);
-- 推荐反馈清理索引（V43）：产品删除时按 rspu_id 清理 matching_feedback
CREATE INDEX IF NOT EXISTS idx_matching_feedback_rspu ON matching_feedback(rspu_id);

