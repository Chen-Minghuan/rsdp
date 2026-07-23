-- RSDP 多模态录入数据库初始化脚本（PostgreSQL 版本）
-- 基于 docs/RSDP-多模态录入数据库设计.md
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
    external_code VARCHAR(64),                       -- 外部编码（Excel/ERP 导入用）
    product_name VARCHAR(256),                       -- 产品名称（AI OCR 提取 / Excel 导入品名；纯图无文字时为空）
    description TEXT,                                -- 长文本描述原文（Excel 导入材质解析/功能配置等，V18 并入）
    retail_price NUMERIC(14,2),                      -- 零售参考价（销售价/含税价，不加密，V18 并入）
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
    product_level VARCHAR(16),                     -- 产品档次：经济型/中端/高端/轻奢/豪华
    budget_range JSONB,                            -- 预算区间 [800, 3500]
    warranty_years INTEGER,                        -- 款式级典型质保年限
    key_specs JSONB,                               -- 关键规格：框架材质、海绵密度等
    status VARCHAR(16) DEFAULT 'active',           -- active/discontinued/draft
    review_status VARCHAR(16) DEFAULT '待复核',     -- 待复核/已确认/存疑
    review_comment TEXT,                           -- 复核备注/说明
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

-- RSPU 编码流水计数器（按品类+风格维度生成流水号）
CREATE TABLE IF NOT EXISTS rspu_code_counter (
    category_code VARCHAR(16) NOT NULL,
    style_code VARCHAR(16) NOT NULL,
    sequence_value BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (category_code, style_code)
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

-- RSPU 变体表（尺寸 × 颜色 × 材质 的具体组合）
-- 建议变体编码使用无业务含义顺序号，如 {rspu_id}-V001，避免尺寸/材质变化导致编码变更
-- 可读名称存入 display_name 字段，尺寸/材质等业务属性存入对应字段
CREATE TABLE IF NOT EXISTS rspu_variant (
    variant_id VARCHAR(64) PRIMARY KEY,            -- 建议格式：{rspu_id}-V001/V002，不嵌入尺寸/材质
    rspu_id VARCHAR(64) NOT NULL,
    display_name VARCHAR(128),                     -- 变体显示名称，如"兰卡沙发 2450mm A级布"
    variant_code VARCHAR(32),                      -- 业务变体编码，如 单人位/S/M/L
    size_code VARCHAR(32),                         -- 尺寸码（可空；归一化索引字段，非身份字段）
    size_text VARCHAR(64),                         -- 尺寸/规格原文（工厂方言，如 "贵妃A位"）
    dimensions JSONB,                              -- 具体尺寸 {"w":560,"d":580,"h":780,"unit":"mm"}
    color_code VARCHAR(32),                        -- 颜色码（可空；归一化索引字段）
    color_text VARCHAR(64),                        -- 颜色原文（工厂方言）
    material_code VARCHAR(32),                     -- 主材质码（可空；归一化索引字段）
    material_text VARCHAR(128),                    -- 材质原文（工厂方言，如 "A级布" "半皮"）
    material_mix JSONB,                            -- 多种材质组合 ["实木框架","布艺座包"]
    reference_price_band VARCHAR(16),              -- 该变体的参考价格带
    product_level VARCHAR(8),                      -- 产品等级，继承/覆盖 RSPU 等级
    status VARCHAR(16) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id)
);

-- 变体编码流水计数器（按 RSPU 维度生成变体顺序号）
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
    -- 产能评估与来源（V2 并入）
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

-- 工厂能力等级表（记录工厂可承接的所有等级，其中主评级标记为 is_primary = true）
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
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code),
    UNIQUE (factory_code, category_code, material_grade_code, process_type)
);
CREATE INDEX IF NOT EXISTS idx_lead_time_rule_factory ON factory_lead_time_rule(factory_code, status);
CREATE INDEX IF NOT EXISTS idx_lead_time_rule_match ON factory_lead_time_rule(factory_code, category_code, material_grade_code, process_type);

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
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code)
);
CREATE INDEX IF NOT EXISTS idx_assessment_factory ON factory_capacity_assessment(factory_code, assessment_period);
CREATE INDEX IF NOT EXISTS idx_assessment_period ON factory_capacity_assessment(assessment_period);

-- RSKU 供应单元子表（工厂对某变体的报价）
CREATE TABLE IF NOT EXISTS rsku_supply (
    rsku_id VARCHAR(64) PRIMARY KEY,
    rspu_id VARCHAR(64) NOT NULL,
    variant_id VARCHAR(64),                        -- 关联具体变体
    factory_code VARCHAR(16) NOT NULL,
    factory_sku VARCHAR(64),                       -- 工厂原始编码
    factory_price TEXT,                              -- 出厂价（AES 加密存储）
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

-- 价格历史表
CREATE TABLE IF NOT EXISTS price_history (
    history_id SERIAL PRIMARY KEY,
    rsku_id VARCHAR(64) NOT NULL,
    old_price TEXT,                                  -- AES 加密后密文
    new_price TEXT,                                  -- AES 加密后密文
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
    deleted_at TIMESTAMP,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (variant_id) REFERENCES rspu_variant(variant_id),
    FOREIGN KEY (rsku_id) REFERENCES rsku_supply(rsku_id)
);

-- RSPU 关系表（原厂搭配 / AI 确认搭配 / 互斥排除）
CREATE TABLE IF NOT EXISTS rspu_relation (
    relation_id VARCHAR(64) PRIMARY KEY,
    anchor_rspu_id VARCHAR(64) NOT NULL,
    related_rspu_id VARCHAR(64) NOT NULL,
    relation_type VARCHAR(16) NOT NULL,              -- official / ai_verified / exclude
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

-- 搭配方案主表
CREATE TABLE IF NOT EXISTS scheme (
    scheme_id VARCHAR(64) PRIMARY KEY,
    scheme_name VARCHAR(128) NOT NULL,
    room_type VARCHAR(32),                           -- 空间类型字典码
    budget_limit DECIMAL(18, 2),                     -- 预算上限
    total_price DECIMAL(18, 2),                      -- 方案总价
    factory_count INTEGER,                           -- 涉及工厂数
    max_lead_time_days INTEGER,                      -- 最长交期
    item_count INTEGER,                              -- 方案项数
    status VARCHAR(16) DEFAULT 'active',
    project_id VARCHAR(64),                          -- 所属设计项目（V4 并入）
    is_template BOOLEAN NOT NULL DEFAULT false,      -- 是否为方案模板（V4 并入）
    template_tags TEXT,                              -- 模板标签 JSON 数组（V4 并入）
    created_by VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_scheme_created_by ON scheme(created_by, status);
CREATE INDEX IF NOT EXISTS idx_scheme_project ON scheme(project_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_scheme_template ON scheme(is_template) WHERE is_template = true AND deleted_at IS NULL;

-- 搭配方案项表
CREATE TABLE IF NOT EXISTS scheme_item (
    scheme_item_id BIGSERIAL PRIMARY KEY,
    scheme_id VARCHAR(64) NOT NULL,
    rspu_id VARCHAR(64) NOT NULL,
    rsku_id VARCHAR(64) NOT NULL,
    factory_code VARCHAR(16) NOT NULL,
    factory_price TEXT,                              -- 加密存储
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
    -- 工厂关联、发货地、交期/MOQ、导入元数据（V2 并入）
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

-- Excel 行级导入记录表（V2 并入）
CREATE TABLE IF NOT EXISTS excel_import_row (
    row_id BIGSERIAL PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
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

-- RSPU 价格列映射记录表（V2 并入）
CREATE TABLE IF NOT EXISTS rspu_price_column_mapping (
    mapping_id BIGSERIAL PRIMARY KEY,
    rspu_id VARCHAR(64) NOT NULL,
    batch_id VARCHAR(64) NOT NULL,
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

-- 批次价格列识别表（V2 并入）
CREATE TABLE IF NOT EXISTS excel_import_price_column (
    column_id BIGSERIAL PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
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
CREATE INDEX IF NOT EXISTS idx_rspu_external_code ON rspu_master(external_code) WHERE deleted_at IS NULL;
-- 外部编码部分唯一索引（V17 并入）：防并发导入产生重复外部编码，仅约束未软删除且非空记录
CREATE UNIQUE INDEX IF NOT EXISTS uk_rspu_external_code ON rspu_master(external_code) WHERE deleted_at IS NULL AND external_code IS NOT NULL;

-- 多值标签索引
CREATE INDEX IF NOT EXISTS idx_rspu_style ON rspu_style(style_code);
CREATE INDEX IF NOT EXISTS idx_rspu_scene ON rspu_scene(scene_code);
CREATE INDEX IF NOT EXISTS idx_rspu_style_rspu ON rspu_style(rspu_id, style_code);

-- 变体表索引
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
CREATE UNIQUE INDEX IF NOT EXISTS idx_rsku_unique ON rsku_supply(rspu_id, variant_id, factory_code) WHERE deleted_at IS NULL;

-- 价格历史索引
CREATE INDEX IF NOT EXISTS idx_price_history ON price_history(rsku_id, created_at);

-- 图片索引
CREATE INDEX IF NOT EXISTS idx_image_rspu ON image_assets(rspu_id, image_type);
CREATE INDEX IF NOT EXISTS idx_image_variant ON image_assets(variant_id, image_type);
CREATE INDEX IF NOT EXISTS idx_image_primary ON image_assets(rspu_id, is_primary);
CREATE INDEX IF NOT EXISTS idx_image_rsku ON image_assets(rsku_id);

-- AI 识别索引
CREATE INDEX IF NOT EXISTS idx_ai_image ON ai_recognition(image_id, recognition_type);
CREATE INDEX IF NOT EXISTS idx_ai_rspu ON ai_recognition(rspu_id, created_at);

-- 审计日志索引
CREATE INDEX IF NOT EXISTS idx_audit_record ON audit_log(table_name, record_id, created_at);
CREATE INDEX IF NOT EXISTS idx_task_status ON async_task(status, created_at);

-- JSONB GIN 索引（按需启用，可加速标签查询）
CREATE INDEX IF NOT EXISTS idx_rspu_six_dim_gin ON rspu_master USING GIN (six_dim_tags jsonb_path_ops);
CREATE INDEX IF NOT EXISTS idx_variant_dimensions_gin ON rspu_variant USING GIN (dimensions jsonb_path_ops);

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
    deleted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_project_owner ON project(owner_id) WHERE deleted_at IS NULL;


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
    created_by VARCHAR(64) NOT NULL REFERENCES sys_user(user_id),
    deleted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_order_creator ON design_order(created_by) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_order_status ON design_order(status) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_design_order_project ON design_order(project_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_design_order_scheme ON design_order(scheme_id) WHERE deleted_at IS NULL;

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

-- 未归一值采集表（V19 并入）：导入时字典解析未命中的工厂原文自动计数采集，
-- 供运营在治理页面归并（写 dict_alias 自学习）或忽略
CREATE TABLE IF NOT EXISTS dict_unresolved_value (
    id BIGSERIAL PRIMARY KEY,
    dict_type VARCHAR(32) NOT NULL,                 -- 字典类型：size/color/material（可扩展 style 等）
    raw_value VARCHAR(128) NOT NULL,                -- 未归一的工厂原文
    occurrence_count INT NOT NULL DEFAULT 1,        -- 累计出现次数
    first_seen_at TIMESTAMP NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMP NOT NULL DEFAULT now(),
    last_batch_id VARCHAR(64),                      -- 最近出现的导入批次
    last_username VARCHAR(64),                      -- 最近操作人
    status VARCHAR(16) NOT NULL DEFAULT 'pending',  -- pending/resolved/ignored
    resolved_code VARCHAR(16),                      -- 归并后的字典码（resolved 时填写）
    resolved_by VARCHAR(64),
    resolved_at TIMESTAMP,
    CONSTRAINT uk_dict_unresolved UNIQUE (dict_type, raw_value)
);
CREATE INDEX IF NOT EXISTS idx_dict_unresolved_status ON dict_unresolved_value(status, dict_type);

-- ============================================================
-- 自增序列对齐（V3 并入，原随 V2 迁移下发）
-- 背景：以下 8 张表的主键实体使用 IdType.AUTO，由数据库自增序列生成主键。
--       本段保证初始化后序列与当前 MAX(id) 一致，避免后续自增值与已有主键冲突。
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
