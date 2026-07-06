-- RSDP 多模态录入数据库初始化脚本（PostgreSQL 版本）
-- 基于 docs/02-architecture/数据库设计.md
-- 说明：JSON 字段使用 JSONB 类型以获得更好的查询性能和索引支持

-- 字典表（先创建，后续表的外键依赖它）
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

-- RSPU 设计原型主表（款式概念，不含工厂/价格/SKU 信息）
CREATE TABLE IF NOT EXISTS rspu_master (
    rspu_id VARCHAR(64) PRIMARY KEY,
    external_code VARCHAR(64),                     -- 业务侧外部编码/产品编码，用于 Excel 批量导入匹配
    category_code VARCHAR(16) NOT NULL,
    category_path TEXT NOT NULL,
    positioning_label VARCHAR(64) NOT NULL,        -- 主风格/主职级，如 中古风 / 总裁级
    six_dim_tags JSONB,                            -- 六维标签 JSON：{"A":"A字架形","B":"编织镂空",...}
    style_vector JSONB,                            -- 512维向量备份：由 SpringBoot 调用 Ollama /api/embeddings 生成
    color_primary_name VARCHAR(64),                -- AI识别主色名称
    color_primary_hsv JSONB,                       -- AI识别主色HSV值 [H,S,V]
    color_secondary VARCHAR(64),                   -- AI识别辅色名称
    material_tags JSONB,                           -- AI识别材质标签
    scene_tags JSONB,                              -- AI识别适用场景标签
    reference_price_band VARCHAR(16),              -- 参考价格带 low/mid/high
    product_level VARCHAR(8),                      -- 产品等级 S/A/B/C，继承自 factory_level 字典
    budget_range JSONB,                            -- 预算区间 [800, 3500]
    warranty_years INTEGER,                        -- 款式级典型质保年限
    key_specs JSONB,                               -- 关键规格：框架材质、海绵密度等
    status VARCHAR(16) DEFAULT 'active',           -- active/discontinued/draft
    review_status VARCHAR(16) DEFAULT '待复核',     -- 待复核/已确认/存疑
    review_comment TEXT,                           -- 复核备注
    aesthetics_confidence VARCHAR(16),             -- high/mid/low
    source_agent_version VARCHAR(64),              -- Ollama 模型版本
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP
);

-- RSPU 多风格关联表（一个款式可属于多个风格）
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

-- RSPU 多场景关联表（一个款式可适用于多个场景）
CREATE TABLE IF NOT EXISTS rspu_scene (
    rspu_id VARCHAR(64) NOT NULL,
    dict_type VARCHAR(32) NOT NULL DEFAULT 'scene',
    scene_code VARCHAR(32) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (rspu_id, scene_code),
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (dict_type, scene_code) REFERENCES category_dict(dict_type, dict_code)
);

-- RSPU 产品间关系表（原厂搭配 / AI 确认搭配 / 互斥排除）
CREATE TABLE IF NOT EXISTS rspu_relation (
    relation_id VARCHAR(64) PRIMARY KEY,
    anchor_rspu_id VARCHAR(64) NOT NULL,           -- 锚点产品
    related_rspu_id VARCHAR(64) NOT NULL,          -- 搭配产品
    relation_type VARCHAR(32) NOT NULL DEFAULT 'official', -- official / ai_verified / exclude
    reason TEXT,                                   -- 搭配说明，如"同厂配套床垫"
    sort_order INTEGER DEFAULT 0,
    status VARCHAR(16) DEFAULT 'active',           -- active / inactive
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    FOREIGN KEY (anchor_rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (related_rspu_id) REFERENCES rspu_master(rspu_id)
);

-- 部分唯一索引：仅在未删除的记录上保证 (anchor, related) 唯一，
-- 软删除的关系可以重新建立。
CREATE UNIQUE INDEX IF NOT EXISTS idx_rspu_relation_unique_active
    ON rspu_relation(anchor_rspu_id, related_rspu_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_rspu_relation_anchor ON rspu_relation(anchor_rspu_id, status, sort_order);
CREATE INDEX IF NOT EXISTS idx_rspu_relation_related ON rspu_relation(related_rspu_id, status);

-- RSPU 变体表（尺寸 × 颜色 × 材质 的具体组合）
-- 建议变体编码使用无业务含义顺序号，如 {rspu_id}-V001，避免尺寸/材质变化导致编码变更
-- 可读名称存入 display_name 字段，尺寸/材质等业务属性存入对应字段
CREATE TABLE IF NOT EXISTS rspu_variant (
    variant_id VARCHAR(64) PRIMARY KEY,            -- 建议格式：{rspu_id}-V001/V002，不嵌入尺寸/材质
    rspu_id VARCHAR(64) NOT NULL,
    display_name VARCHAR(128),                     -- 变体显示名称，如"兰卡沙发 2450mm A级布"
    variant_code VARCHAR(32),                      -- 业务变体编码，如 单人位/S/M/L
    size_code VARCHAR(32),                         -- 尺寸码
    dimensions JSONB,                              -- 具体尺寸 {"w":560,"d":580,"h":780,"unit":"mm"}
    color_code VARCHAR(32),                        -- 颜色码
    material_code VARCHAR(32),                     -- 主材质码
    material_mix JSONB,                            -- 多种材质组合 ["实木框架","布艺座包"]
    reference_price_band VARCHAR(16),              -- 该变体的参考价格带
    product_level VARCHAR(8),                      -- 产品等级覆盖，为空时继承 rspu_master.product_level
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

-- 每个工厂只能有一个主评级
CREATE UNIQUE INDEX IF NOT EXISTS idx_factory_level_primary_unique
    ON factory_level_capability(factory_code)
    WHERE is_primary = TRUE;

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
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
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
    updated_at TIMESTAMP,
    PRIMARY KEY (factory_code, variant_id),
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code),
    FOREIGN KEY (variant_id) REFERENCES rspu_variant(variant_id)
);

-- RSKU 供应单元子表（工厂对某变体的报价）
CREATE TABLE IF NOT EXISTS rsku_supply (
    rsku_id VARCHAR(64) PRIMARY KEY,
    rspu_id VARCHAR(64) NOT NULL,
    variant_id VARCHAR(64),                        -- 关联具体变体
    factory_code VARCHAR(16) NOT NULL,
    factory_sku VARCHAR(64),                       -- 工厂原始编码
    factory_price TEXT,                            -- 出厂价（AES-256-GCM 加密存储，Base64 密文）
    price_band VARCHAR(16),                        -- low/mid/high
    product_level VARCHAR(8),                      -- 产品等级，继承自 RSPU/变体
    material_code VARCHAR(8),                      -- 材质版本码
    material_description TEXT,                     -- 工厂提供的详细材质说明
    lead_time_days INTEGER,                        -- 交期
    moq INTEGER,                                   -- 最小起订量
    warranty_years INTEGER,                        -- 工厂对该变体的质保年限
    shipping_from VARCHAR(128),                    -- 发货地（省/市，快速展示用）
    shipping_warehouse_id VARCHAR(64),             -- 关联 factory_warehouse
    structure_strength_rating VARCHAR(32),         -- 家用结构/商用结构/需验证
    flame_retardant_capability VARCHAR(32),        -- 可做有案例/可做无案例/不可做
    factory_photo_path TEXT,                       -- 该厂实拍图路径
    factory_credit_score INTEGER,                  -- 履约评分 0-100
    on_time_rate DECIMAL(5, 4),                    -- 准时率
    quality_return_rate DECIMAL(5, 4),             -- 退货率
    diff_notes TEXT,                               -- 差异备注：旋转底座、加宽 2cm 等
    quote_confidence VARCHAR(16),                  -- high/mid/low
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

-- 部分唯一索引：处理 variant_id 可 NULL 的情况，并排除已软删除记录
CREATE UNIQUE INDEX IF NOT EXISTS idx_rsku_unique_null_variant
    ON rsku_supply(rspu_id, factory_code)
    WHERE variant_id IS NULL AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_rsku_unique_non_null_variant
    ON rsku_supply(rspu_id, variant_id, factory_code)
    WHERE variant_id IS NOT NULL AND deleted_at IS NULL;

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
    variant_id VARCHAR(64),                        -- 变体专属图
    rsku_id VARCHAR(64),                           -- 工厂实拍图
    image_type VARCHAR(32) NOT NULL,               -- white_bg/factory_photo/detail/scene/original
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
    recognition_type VARCHAR(16),                  -- encode/label/judge
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

-- RSPU 业务编码流水号计数器（按品类+风格组合）
CREATE TABLE IF NOT EXISTS rspu_code_counter (
    category_code VARCHAR(16) NOT NULL,
    style_code VARCHAR(16) NOT NULL,
    sequence_value BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (category_code, style_code)
);

-- 变体编码流水号计数器（按 RSPU 维度，保证变体 ID 并发唯一）
CREATE TABLE IF NOT EXISTS variant_code_counter (
    rspu_id VARCHAR(64) PRIMARY KEY,
    sequence_value BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
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

-- =================== 索引 ===================

-- RSPU 索引
CREATE INDEX IF NOT EXISTS idx_rspu_category ON rspu_master(category_code, status);
CREATE INDEX IF NOT EXISTS idx_rspu_positioning ON rspu_master(positioning_label, category_code);
CREATE INDEX IF NOT EXISTS idx_rspu_review ON rspu_master(review_status);
CREATE INDEX IF NOT EXISTS idx_rspu_meta ON rspu_master(category_code, positioning_label, status) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_rspu_external_code
    ON rspu_master(external_code) WHERE deleted_at IS NULL;

-- 多值标签索引
CREATE INDEX IF NOT EXISTS idx_rspu_style ON rspu_style(style_code);
CREATE INDEX IF NOT EXISTS idx_rspu_scene ON rspu_scene(scene_code);

-- 变体表索引
CREATE INDEX IF NOT EXISTS idx_variant_rspu ON rspu_variant(rspu_id, status);
CREATE INDEX IF NOT EXISTS idx_variant_color ON rspu_variant(color_code);
CREATE INDEX IF NOT EXISTS idx_variant_material ON rspu_variant(material_code);
CREATE INDEX IF NOT EXISTS idx_variant_size ON rspu_variant(size_code);

-- 工厂仓库索引
CREATE INDEX IF NOT EXISTS idx_factory_warehouse_factory ON factory_warehouse(factory_code, status);

-- 工厂产能索引
CREATE INDEX IF NOT EXISTS idx_capacity_variant ON factory_variant_capacity(variant_id);
CREATE INDEX IF NOT EXISTS idx_capacity_factory ON factory_variant_capacity(factory_code);

-- RSKU 索引
CREATE INDEX IF NOT EXISTS idx_rsku_rspu ON rsku_supply(rspu_id);
CREATE INDEX IF NOT EXISTS idx_rsku_variant ON rsku_supply(variant_id);
CREATE INDEX IF NOT EXISTS idx_rsku_factory ON rsku_supply(factory_code);
CREATE INDEX IF NOT EXISTS idx_rsku_warehouse ON rsku_supply(shipping_warehouse_id);
-- 价格历史索引
CREATE INDEX IF NOT EXISTS idx_price_history ON price_history(rsku_id, created_at);

-- 图片索引
CREATE INDEX IF NOT EXISTS idx_image_rspu ON image_assets(rspu_id, image_type);
CREATE INDEX IF NOT EXISTS idx_image_variant ON image_assets(variant_id, image_type);
CREATE INDEX IF NOT EXISTS idx_image_primary ON image_assets(rspu_id, is_primary);

-- AI 识别索引
CREATE INDEX IF NOT EXISTS idx_ai_image ON ai_recognition(image_id, recognition_type);
CREATE INDEX IF NOT EXISTS idx_ai_rspu ON ai_recognition(rspu_id, created_at);

-- 审计日志索引
CREATE INDEX IF NOT EXISTS idx_audit_record ON audit_log(table_name, record_id, created_at);
CREATE INDEX IF NOT EXISTS idx_task_status ON async_task(status, created_at);

-- JSONB GIN 索引（material_tags 用于精确包含查询）
CREATE INDEX IF NOT EXISTS idx_rspu_material_tags_gin ON rspu_master USING GIN (material_tags jsonb_path_ops);
-- CREATE INDEX IF NOT EXISTS idx_rspu_six_dim_gin ON rspu_master USING GIN (six_dim_tags jsonb_path_ops);
-- CREATE INDEX IF NOT EXISTS idx_variant_dimensions_gin ON rspu_variant USING GIN (dimensions jsonb_path_ops);

-- 搭配方案主表
CREATE TABLE IF NOT EXISTS scheme (
    scheme_id VARCHAR(64) PRIMARY KEY,
    scheme_name VARCHAR(128) NOT NULL,
    room_type VARCHAR(32),
    budget_limit DECIMAL(18, 2),
    total_price DECIMAL(18, 2) NOT NULL DEFAULT 0,
    factory_count INTEGER NOT NULL DEFAULT 0,
    max_lead_time_days INTEGER NOT NULL DEFAULT 0,
    item_count INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(16) DEFAULT 'active',
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP
);

-- 搭配方案项表
CREATE TABLE IF NOT EXISTS scheme_item (
    scheme_item_id BIGSERIAL PRIMARY KEY,
    scheme_id VARCHAR(64) NOT NULL,
    rspu_id VARCHAR(64) NOT NULL,
    rsku_id VARCHAR(64) NOT NULL,
    factory_code VARCHAR(16),
    factory_price TEXT,                            -- 出厂价（AES-256-GCM 加密存储，Base64 密文）
    lead_time_days INTEGER,
    moq INTEGER,
    quantity INTEGER DEFAULT 1 NOT NULL,           -- 产品数量（默认 1）
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (scheme_id) REFERENCES scheme(scheme_id),
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (rsku_id) REFERENCES rsku_supply(rsku_id),
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code)
);

-- 方案索引
CREATE INDEX IF NOT EXISTS idx_scheme_status ON scheme(status, created_at);
CREATE INDEX IF NOT EXISTS idx_scheme_item_scheme ON scheme_item(scheme_id);

-- =================== 风格数据库扩展表 ===================

-- 案例库：成功/失败的设计案例
CREATE TABLE IF NOT EXISTS style_case (
    case_id VARCHAR(64) PRIMARY KEY,
    case_name VARCHAR(128) NOT NULL,
    style_code VARCHAR(32) NOT NULL,
    room_type VARCHAR(32),
    is_success BOOLEAN NOT NULL DEFAULT TRUE,
    source_type VARCHAR(32),
    source_url TEXT,
    description TEXT,
    image_url TEXT,
    ai_raw_output JSONB,
    review_status VARCHAR(16) DEFAULT '待复核',
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
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
    FOREIGN KEY (case_id) REFERENCES style_case(case_id)
);

-- 搭配公式库：可解释的搭配规则
CREATE TABLE IF NOT EXISTS style_matching_formula (
    formula_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(256) NOT NULL,
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
    updated_at TIMESTAMP
);

-- 产品-风格匹配结果：产品录入后自动计算
CREATE TABLE IF NOT EXISTS product_style_match (
    match_id SERIAL PRIMARY KEY,
    rspu_id VARCHAR(64) NOT NULL,
    style_code VARCHAR(32) NOT NULL,
    element_match JSONB,
    formula_scores JSONB,
    overall_score DECIMAL(5,4),
    confidence VARCHAR(16),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    UNIQUE (rspu_id, style_code)
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
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 风格数据库索引
CREATE INDEX IF NOT EXISTS idx_style_case_style ON style_case(style_code, is_success);
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
