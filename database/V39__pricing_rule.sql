-- ============================================================================
-- V39：价格体系改造 P3（品类级加价 + 定价试算）
-- 1) pricing_rule：品类级加价倍率（售价解析链：retail_price → 成本×品类倍率 → 成本×全局倍率）
-- 2) pricing:update 权限点（定价规则管理仅 ADMIN）
-- 注意同步：V1__init_db.sql / V1__seed_data.sql / reset_db.sql 三处已同步更新
-- ============================================================================

CREATE TABLE IF NOT EXISTS pricing_rule (
    rule_id           VARCHAR(64) PRIMARY KEY,
    category_code     VARCHAR(16) NOT NULL,
    markup_multiplier NUMERIC(6,3) NOT NULL CHECK (markup_multiplier > 0),
    remark            VARCHAR(255),
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE pricing_rule IS '品类级加价倍率：标准售价 = 成本 × 品类倍率（RSPU 已录入建议销售价 retail_price 时优先；无品类规则回退全局 pricing.markup.global）';
COMMENT ON COLUMN pricing_rule.category_code IS '品类编码（category_dict dict_type=category）';

-- 品类唯一索引
CREATE UNIQUE INDEX IF NOT EXISTS uk_pricing_rule_category ON pricing_rule(category_code);

-- 定价规则管理权限（仅 ADMIN；EDITOR 授权排除清单已同步排除）
INSERT INTO sys_permission (permission_code, permission_name) VALUES
('pricing:update', '定价规则管理')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'ADMIN'
  AND p.permission_code = 'pricing:update'
ON CONFLICT DO NOTHING;
