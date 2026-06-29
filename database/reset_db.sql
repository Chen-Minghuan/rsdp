-- RSDP 数据库重置脚本（PostgreSQL）
-- 用途：清空当前 rsdp 数据库并重新初始化表结构 + 种子数据
-- 执行方式：在 IDEA 数据库插件的 rsdp@localhost Console 中全选执行

-- =================== 1. 清理旧表 ===================
DROP TABLE IF EXISTS ai_recognition CASCADE;
DROP TABLE IF EXISTS image_assets CASCADE;
DROP TABLE IF EXISTS price_history CASCADE;
DROP TABLE IF EXISTS rsku_supply CASCADE;
DROP TABLE IF EXISTS factory_level_capability CASCADE;
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
    product_level VARCHAR(8),
    budget_range JSONB,
    warranty_years INTEGER,
    key_specs JSONB,
    status VARCHAR(16) DEFAULT 'active',
    review_status VARCHAR(16) DEFAULT '待复核',
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
    product_level VARCHAR(8),
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

-- 工厂能力等级表（记录工厂可承接的所有等级，主评级 is_primary=true）
CREATE TABLE IF NOT EXISTS factory_level_capability (
    id           BIGSERIAL PRIMARY KEY,
    factory_code VARCHAR(16) NOT NULL,
    level_code   VARCHAR(8)  NOT NULL,
    is_primary   BOOLEAN DEFAULT FALSE,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (factory_code, level_code),
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code)
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

-- 家装风格
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('style', 'MC', '中古风', 1),
('style', 'BA', '包豪斯', 2),
('style', 'IT', '意式', 3),
('style', 'FR', '法式', 4),
('style', 'WJ', '侘寂', 5),
('style', 'NC', '新中式', 6),
('style', 'CR', '奶油风', 7),
('style', 'IN', '工业风', 8),
('style', 'MP', '孟菲斯', 9)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 办公家具职级
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('grade', 'EX', '总裁级', 1),
('grade', 'MG', '经理级', 2),
('grade', 'ST', '职员级', 3),
('grade', 'PU', '公共区', 4),
('grade', 'CO', '会议区', 5)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 材质版本码
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('material', 'TN', '真藤', 1),
('material', 'PE', 'PE仿藤', 2),
('material', 'LE', '皮革', 3),
('material', 'NP', '纳帕皮', 4),
('material', 'SU', '磨砂皮', 5),
('material', 'MA', '马鞍皮', 6),
('material', 'LI', '亚麻', 7),
('material', 'SF', '羊羔绒', 8),
('material', 'VE', '天鹅绒', 9),
('material', 'WO', '实木', 10),
('material', 'RK', '藤编+实木混血', 11),
('material', 'MT', '金属', 12)
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

-- 颜色码
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('color', 'CARAMEL', '焦糖棕', 1),
('color', 'BEIGE', '米白', 2),
('color', 'NATURAL', '原木色', 3),
('color', 'BLACK', '黑色', 4),
('color', 'GRAY', '灰色', 5),
('color', 'NAVY', '藏青', 6)
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
