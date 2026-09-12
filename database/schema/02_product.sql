-- ============================================================
-- RSDP 基线 DDL · 02 产品域（02_product.sql）
-- 包含表：rspu_master, rspu_style, rspu_scene, rspu_variant, rspu_relation, rspu_code_counter, rsku_code_counter, variant_code_counter, rsku_supply, price_history, rspu_price_summary
-- 执行顺序：schema/ 目录按文件名 01 → 12 → 99 依次执行（编号即执行顺序，基线由原 V1__init_db.sql 按域拆分而来）
-- 同步约定：新增/修改本域表结构时须同步 ops/reset_db.sql，约定详见 database/README.md
-- ============================================================
-- RSPU 设计原型主表（款式概念，不含工厂/价格/SKU 信息）
CREATE TABLE IF NOT EXISTS rspu_master (
    rspu_id VARCHAR(64) PRIMARY KEY,
    external_code VARCHAR(64),                       -- 外部编码（Excel/ERP 导入用）
    rspu_code VARCHAR(32) UNIQUE,                    -- 业务编码，如 FS-MC-001-M
    category_code VARCHAR(16) NOT NULL,
    category_path TEXT NOT NULL,
    positioning_label VARCHAR(64) NOT NULL,        -- 主风格/主职级，如 中古风 / 总裁级
    product_name VARCHAR(256),                     -- 商品名称（AI OCR/录入表单/Excel导入填充，可空）
    description TEXT,                                -- 长文本描述原文（Excel 导入材质解析/功能配置等，V18 并入）
    retail_price NUMERIC(14,2),                      -- 零售参考价（销售价/含税价，不加密，V18 并入）
    six_dim_tags JSONB,                            -- 六维标签 JSON：{"A":"A字架形","B":"编织镂空",...}
    style_vector JSONB,                            -- 512维向量备份：由 SpringBoot 调用 Ollama /api/embeddings 生成
    color_primary_name VARCHAR(64),                -- AI识别主色名称
    color_primary_hsv JSONB,                       -- AI识别主色HSV值 [H,S,V]
    color_secondary VARCHAR(64),                   -- AI识别辅色名称
    material_tags JSONB,                           -- AI识别材质标签
    fabric_tags JSONB DEFAULT '[]',                -- 面料标签字典码 JSON 数组（V22），如 ["LI","KJ"]
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
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    created_by VARCHAR(64)                         -- 录入人（sys_user.user_id，V3 增量并入；历史未知行留 NULL）
);

-- RSPU 多风格关联表（一个款式可属于多个风格）
CREATE TABLE IF NOT EXISTS rspu_style (
    rspu_id VARCHAR(64) NOT NULL,
    dict_type VARCHAR(32) NOT NULL DEFAULT 'style',
    style_code VARCHAR(32) NOT NULL,
    is_primary BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (rspu_id, style_code),
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (dict_type, style_code) REFERENCES category_dict(dict_type, dict_code)
);

-- RSPU 编码流水计数器（按品类+风格维度生成流水号）
CREATE TABLE IF NOT EXISTS rspu_code_counter (
    category_code VARCHAR(16) NOT NULL,
    style_code VARCHAR(16) NOT NULL,
    sequence_value BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (category_code, style_code)
);

-- RSKU 编码流水计数器（按 RSPU 业务编码+工厂+材质维度生成流水号）
CREATE TABLE IF NOT EXISTS rsku_code_counter (
    rspu_code VARCHAR(32) NOT NULL,
    factory_code VARCHAR(16) NOT NULL,
    material_code VARCHAR(16) NOT NULL,
    sequence_value BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (rspu_code, factory_code, material_code)
);

-- RSPU 多场景关联表（一个款式可适用于多个场景）
CREATE TABLE IF NOT EXISTS rspu_scene (
    rspu_id VARCHAR(64) NOT NULL,
    dict_type VARCHAR(32) NOT NULL DEFAULT 'scene',
    scene_code VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
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
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id)
);

-- 变体编码流水计数器（按 RSPU 维度生成变体顺序号）
CREATE TABLE IF NOT EXISTS variant_code_counter (
    rspu_id VARCHAR(64) NOT NULL,
    sequence_value BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (rspu_id),
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id)
);

-- RSKU 供应单元子表（工厂对某变体的报价）
CREATE TABLE IF NOT EXISTS rsku_supply (
    rsku_id VARCHAR(64) PRIMARY KEY,
    rsku_code VARCHAR(64) UNIQUE,                    -- 业务编码，如 FS-MC-001-M-A004-PE-001
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
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (variant_id) REFERENCES rspu_variant(variant_id)
    -- factory_code / shipping_warehouse_id 外键跨域（03_factory 在 02 之后执行），在 cross_domain_fk.sql 补加
);

-- 价格历史表
CREATE TABLE IF NOT EXISTS price_history (
    history_id BIGSERIAL PRIMARY KEY,
    rsku_id VARCHAR(64) NOT NULL,
    old_price TEXT,                                  -- AES 加密后密文
    new_price TEXT,                                  -- AES 加密后密文
    changed_by VARCHAR(64),
    change_reason TEXT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
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
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    FOREIGN KEY (anchor_rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (related_rspu_id) REFERENCES rspu_master(rspu_id)
);
CREATE INDEX IF NOT EXISTS idx_rspu_relation_anchor ON rspu_relation(anchor_rspu_id, relation_type, status);
CREATE INDEX IF NOT EXISTS idx_rspu_relation_related ON rspu_relation(related_rspu_id, relation_type, status);

-- =================== 索引 ===================

-- RSPU 索引
CREATE INDEX IF NOT EXISTS idx_rspu_category ON rspu_master(category_code, status);
-- 核心分页索引（V43）：产品列表 orderByDesc created_at，常见过滤 status / category_code
CREATE INDEX IF NOT EXISTS idx_rspu_status_created ON rspu_master(status, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_rspu_category_status_created ON rspu_master(category_code, status, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_rspu_positioning ON rspu_master(positioning_label, category_code);
CREATE INDEX IF NOT EXISTS idx_rspu_review ON rspu_master(review_status);
CREATE INDEX IF NOT EXISTS idx_rspu_meta ON rspu_master(category_code, positioning_label, status) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_rspu_external_code ON rspu_master(external_code) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_rspu_created_by ON rspu_master(created_by);
-- 外部编码部分唯一索引（V17 并入）：防并发导入产生重复外部编码，仅约束未软删除且非空记录
CREATE UNIQUE INDEX IF NOT EXISTS uk_rspu_external_code ON rspu_master(external_code) WHERE deleted_at IS NULL AND external_code IS NOT NULL;

-- 多值标签索引
CREATE INDEX IF NOT EXISTS idx_rspu_style ON rspu_style(style_code);
CREATE INDEX IF NOT EXISTS idx_rspu_scene ON rspu_scene(scene_code);
-- idx_rspu_style_rspu 已于 V43 删除：与 rspu_style 复合 PK (rspu_id, style_code) 完全同列，冗余

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

-- RSKU 索引
CREATE INDEX IF NOT EXISTS idx_rsku_rspu ON rsku_supply(rspu_id);
CREATE INDEX IF NOT EXISTS idx_rsku_variant ON rsku_supply(variant_id);
CREATE INDEX IF NOT EXISTS idx_rsku_factory ON rsku_supply(factory_code);
CREATE INDEX IF NOT EXISTS idx_rsku_warehouse ON rsku_supply(shipping_warehouse_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_rsku_unique ON rsku_supply(rspu_id, variant_id, factory_code) WHERE deleted_at IS NULL;

-- 价格历史索引
CREATE INDEX IF NOT EXISTS idx_price_history ON price_history(rsku_id, created_at);

-- JSONB GIN 索引（按需启用，可加速标签查询）
CREATE INDEX IF NOT EXISTS idx_rspu_six_dim_gin ON rspu_master USING GIN (six_dim_tags jsonb_path_ops);
-- 材质标签 GIN（V43）：ProductQueryService / PublicCatalogService 均用 material_tags @> {0}::jsonb
CREATE INDEX IF NOT EXISTS idx_rspu_material_tags_gin ON rspu_master USING GIN (material_tags jsonb_path_ops);
CREATE INDEX IF NOT EXISTS idx_variant_dimensions_gin ON rspu_variant USING GIN (dimensions jsonb_path_ops);

-- RSPU 价格投影汇总表（V44 并入）：每 RSPU 一行，只存业务允许暴露的聚合指标
-- （min/max 出厂价、有效 RSKU 数）。安全口径：产品列表对工厂管理员/平台人员本就展示
-- RSPU 级最低出厂价，该聚合值属业务允许暴露；单 RSKU 精确报价仍只存于
-- rsku_supply.factory_price 加密列。由 RSKU 写路径 Java 侧重算 upsert 维护，
-- 存量由 PriceSummaryBackfillMigration 回填。
CREATE TABLE IF NOT EXISTS rspu_price_summary (
    rspu_id            VARCHAR(64) PRIMARY KEY,
    min_factory_price  NUMERIC(14, 2),              -- 有效 RSKU 最低出厂价（明文聚合值）
    max_factory_price  NUMERIC(14, 2),              -- 有效 RSKU 最高出厂价
    active_rsku_count  INTEGER NOT NULL DEFAULT 0,  -- 有效（未软删）RSKU 数
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id)
);
CREATE INDEX IF NOT EXISTS idx_rspu_price_summary_min ON rspu_price_summary(min_factory_price);
