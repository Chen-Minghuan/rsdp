-- RSDP 数据库重置脚本（PostgreSQL）
-- 用途：清空当前 rsdp 数据库并重新初始化表结构 + 种子数据
-- 执行方式：在 IDEA 数据库插件的 rsdp@localhost Console 中全选执行

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
DROP TABLE IF EXISTS factory_variant_capacity CASCADE;
DROP TABLE IF EXISTS factory_warehouse CASCADE;
DROP TABLE IF EXISTS rspu_variant CASCADE;
DROP TABLE IF EXISTS factory_master CASCADE;
DROP TABLE IF EXISTS rspu_scene CASCADE;
DROP TABLE IF EXISTS rspu_style CASCADE;
DROP TABLE IF EXISTS rspu_master CASCADE;
DROP TABLE IF EXISTS async_task CASCADE;
DROP TABLE IF EXISTS audit_log CASCADE;
DROP TABLE IF EXISTS user_operator CASCADE;
DROP TABLE IF EXISTS category_dict CASCADE;
DROP TABLE IF EXISTS matching_feedback CASCADE;
DROP TABLE IF EXISTS product_style_match CASCADE;
DROP TABLE IF EXISTS style_matching_formula CASCADE;
DROP TABLE IF EXISTS style_element CASCADE;
DROP TABLE IF EXISTS style_case CASCADE;
DROP TABLE IF EXISTS scheme_item CASCADE;
DROP TABLE IF EXISTS scheme CASCADE;
DROP TABLE IF EXISTS scheme_candidate CASCADE;
DROP TABLE IF EXISTS recommendation_score_config CASCADE;
DROP TABLE IF EXISTS designer_profile CASCADE;
DROP TABLE IF EXISTS product_collection_item CASCADE;
DROP TABLE IF EXISTS product_collection CASCADE;
DROP TABLE IF EXISTS factory_product_capability CASCADE;
DROP TABLE IF EXISTS sys_user_factory CASCADE;
DROP TABLE IF EXISTS sys_user_role CASCADE;
DROP TABLE IF EXISTS sys_role_permission CASCADE;
DROP TABLE IF EXISTS sys_permission CASCADE;
DROP TABLE IF EXISTS sys_role CASCADE;
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
    PRIMARY KEY (dict_type, dict_code)
);

-- =================== 3. 创建业务表 ===================

-- RSPU 设计原型主表（款式概念）
CREATE TABLE IF NOT EXISTS rspu_master (
    rspu_id VARCHAR(64) PRIMARY KEY,
    external_code VARCHAR(64),                       -- 外部编码（Excel/ERP 导入用）
    category_code VARCHAR(16) NOT NULL,
    category_path TEXT NOT NULL,
    positioning_label VARCHAR(64) NOT NULL,
    six_dim_tags JSONB,
    style_vector JSONB,
    color_primary_name VARCHAR(64),
    color_primary_hsv JSONB,
    color_secondary VARCHAR(64),
    material_tags JSONB,
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
    dimensions JSONB,
    color_code VARCHAR(32),
    material_code VARCHAR(32),
    material_mix JSONB,
    reference_price_band VARCHAR(16),
    status VARCHAR(16) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
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
    status VARCHAR(16) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP
);

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

-- RSKU 供应单元子表
CREATE TABLE IF NOT EXISTS rsku_supply (
    rsku_id VARCHAR(64) PRIMARY KEY,
    rspu_id VARCHAR(64) NOT NULL,
    variant_id VARCHAR(64),
    factory_code VARCHAR(16) NOT NULL,
    factory_sku VARCHAR(64),
    factory_price DECIMAL(18, 2),
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
    old_price DECIMAL(18, 2),
    new_price DECIMAL(18, 2),
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
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (variant_id) REFERENCES rspu_variant(variant_id),
    FOREIGN KEY (rsku_id) REFERENCES rsku_supply(rsku_id)
);

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

-- 异步任务表
CREATE TABLE IF NOT EXISTS async_task (
    task_id VARCHAR(64) PRIMARY KEY,
    task_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) DEFAULT 'pending',
    progress INTEGER DEFAULT 0,
    input_data JSONB,
    result_data JSONB,
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

CREATE INDEX IF NOT EXISTS idx_rspu_style ON rspu_style(style_code);
CREATE INDEX IF NOT EXISTS idx_rspu_scene ON rspu_scene(scene_code);

CREATE INDEX IF NOT EXISTS idx_variant_rspu ON rspu_variant(rspu_id, status);
CREATE INDEX IF NOT EXISTS idx_variant_color ON rspu_variant(color_code);
CREATE INDEX IF NOT EXISTS idx_variant_material ON rspu_variant(material_code);
CREATE INDEX IF NOT EXISTS idx_variant_size ON rspu_variant(size_code);

CREATE INDEX IF NOT EXISTS idx_factory_warehouse_factory ON factory_warehouse(factory_code, status);
CREATE INDEX IF NOT EXISTS idx_capacity_variant ON factory_variant_capacity(variant_id);
CREATE INDEX IF NOT EXISTS idx_capacity_factory ON factory_variant_capacity(factory_code);

CREATE INDEX IF NOT EXISTS idx_rsku_rspu ON rsku_supply(rspu_id);
CREATE INDEX IF NOT EXISTS idx_rsku_variant ON rsku_supply(variant_id);
CREATE INDEX IF NOT EXISTS idx_rsku_factory ON rsku_supply(factory_code);
CREATE INDEX IF NOT EXISTS idx_rsku_warehouse ON rsku_supply(shipping_warehouse_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_rsku_unique ON rsku_supply(rspu_id, variant_id, factory_code);

CREATE INDEX IF NOT EXISTS idx_price_history ON price_history(rsku_id, created_at);

CREATE INDEX IF NOT EXISTS idx_image_rspu ON image_assets(rspu_id, image_type);
CREATE INDEX IF NOT EXISTS idx_image_variant ON image_assets(variant_id, image_type);
CREATE INDEX IF NOT EXISTS idx_image_primary ON image_assets(rspu_id, is_primary);

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
    status VARCHAR(16) DEFAULT 'active',
    token_version INT DEFAULT 0,
    last_login_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sys_user_username ON sys_user(username);

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
    category_code VARCHAR(16) NOT NULL,
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

-- =================== 5. 插入种子数据 ===================

-- 产品类别
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('category', 'FS', '座椅', 1),
('category', 'SF', '沙发', 2),
('category', 'TB', '茶几', 3),
('category', 'FC', '柜类', 4),
('category', 'BS', '吧椅', 5),
('category', 'OF', '办公家具', 6)
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

-- 六维标签 - 休闲椅 A 维度（轮廓）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order) VALUES
('six_dim_A', 'A字架形', 'A字架形', 'FS', 1),
('six_dim_A', '蛋形', '蛋形', 'FS', 2),
('six_dim_A', '方盒形', '方盒形', 'FS', 3)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维标签 - 休闲椅 B 维度（靠背）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order) VALUES
('six_dim_B', '编织镂空', '编织镂空', 'FS', 1),
('six_dim_B', '高背包裹', '高背包裹', 'FS', 2),
('six_dim_B', '无靠背', '无靠背', 'FS', 3)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

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
