-- ============================================================
-- RSDP 基线 DDL · 08 订单与定价域（08_order_pricing.sql）
-- 包含表：design_order, design_order_item, order_no_counter, sys_config, pricing_rule, recommendation_score_config
-- 执行顺序：schema/ 目录按文件名 01 → 12 → 99 依次执行（编号即执行顺序，基线由原 V1__init_db.sql 按域拆分而来）
-- 同步约定：新增/修改本域表结构时须同步 ops/reset_db.sql，约定详见 database/README.md
-- ============================================================
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
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ
    -- created_by 外键跨域（09_user_team 在 08 之后执行），在 cross_domain_fk.sql 补加
);
-- idx_recommendation_config_key 已于 V43 删除：被 config_key 行内 UNIQUE 覆盖，冗余
CREATE INDEX IF NOT EXISTS idx_recommendation_config_default ON recommendation_score_config(is_default, is_active);

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
    invite_expire_at TIMESTAMPTZ,
    invite_confirmed_at TIMESTAMPTZ,
    contract_file_id VARCHAR(64),
    idempotency_key VARCHAR(64),
    created_by VARCHAR(64) NOT NULL,                       -- sys_user 外键跨域（09 在 08 之后执行），在 cross_domain_fk.sql 补加
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
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
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
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
    list_price NUMERIC(12,2),                        -- 标准售价快照（明文，对客户可见；区别于上面三列 TEXT 存 AES 密文，V38 并入）
    space_tag VARCHAR(32),                           -- 空间快照（由方案复制冻结，V40 并入）
    factory_code VARCHAR(16),
    snapshot_json JSONB,                             -- 订单明细快照（V43 由 TEXT 改 JSONB，实体用 JsonbTypeHandler）
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_design_order_item_quantity CHECK (quantity >= 1)
);
COMMENT ON COLUMN design_order_item.list_price IS '标准售价快照（明文 NUMERIC：售价对客户可见不敏感，且便于 SQL 分析；刻意区别于 original_price/final_price/adjust_price 三列 TEXT 存 AES 密文）';
COMMENT ON COLUMN design_order_item.space_tag IS '空间快照（由方案生成订单时从 scheme_item.space_tag 复制冻结；为空=未指定空间/存量订单，邀请页平铺展示兜底）';
CREATE INDEX IF NOT EXISTS idx_order_item_order ON design_order_item(order_id);
CREATE INDEX IF NOT EXISTS idx_order_item_rspu ON design_order_item(rspu_id);
CREATE INDEX IF NOT EXISTS idx_order_item_factory ON design_order_item(factory_code);

-- 轻量配置表（V5 并入）
CREATE TABLE IF NOT EXISTS sys_config (
    config_key VARCHAR(64) PRIMARY KEY,
    config_value TEXT,
    remark VARCHAR(256),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 品类级加价倍率（V39 并入）：标准售价 = 成本 × 品类倍率（retail_price 优先；无规则回退全局 pricing.markup.global）
CREATE TABLE IF NOT EXISTS pricing_rule (
    rule_id VARCHAR(64) PRIMARY KEY,
    category_code VARCHAR(16) NOT NULL,
    markup_multiplier NUMERIC(6,3) NOT NULL CHECK (markup_multiplier > 0),
    remark VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_pricing_rule_category ON pricing_rule(category_code);

