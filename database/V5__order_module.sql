-- ============================================================
-- V5 迁移：订单模块（报价单订单化 + 邀请确认）
-- 对应文档：docs/08-roadmap/RSDP-rooom整合实现方案.md 4.3 节
-- 本脚本幂等，可重复执行。
-- 注：created_by 与 sys_user.user_id 类型一致使用 VARCHAR(64)；
--     scheme_id 与 scheme.scheme_id 类型一致使用 VARCHAR(64)；
--     snapshot_json 与项目 JSON 约定一致使用 TEXT。
-- ============================================================

-- 订单主表（价格字段与 factory_price 同策略，AES 加密 TypeHandler 读写）
CREATE TABLE IF NOT EXISTS design_order (
    order_id              VARCHAR(40) PRIMARY KEY,        -- ORD-XXXXXXXX
    order_no              VARCHAR(32) NOT NULL UNIQUE,    -- DO-20260713-001
    project_id            VARCHAR(40) REFERENCES project (project_id),
    scheme_id             VARCHAR(64) REFERENCES scheme (scheme_id),
    receiver_name         VARCHAR(64),
    receiver_phone        VARCHAR(32),
    receiver_area         VARCHAR(128),
    receiver_address      VARCHAR(256),
    original_total_price  TEXT,                            -- AES 加密
    price_rate            NUMERIC(5, 4) NOT NULL DEFAULT 1 CHECK (price_rate >= 0 AND price_rate <= 1),-- 折扣率
    final_total_price     TEXT,                            -- AES 加密
    item_count            INT NOT NULL DEFAULT 0,
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    expected_lead_time    INT,                             -- 预计交期（天，取明细最大值）
    remark                VARCHAR(512),
    invite_token_hash     VARCHAR(128),                    -- 邀请 token 的 SHA-256（不存明文）
    invite_expire_at      TIMESTAMP,
    invite_confirmed_at   TIMESTAMP,
    created_by            VARCHAR(64) NOT NULL REFERENCES sys_user (user_id),
    deleted_at            TIMESTAMP,
    created_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_order_creator ON design_order (created_by) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_order_status ON design_order (status) WHERE deleted_at IS NULL;

-- 订单明细（商品快照）
CREATE TABLE IF NOT EXISTS design_order_item (
    id              BIGSERIAL PRIMARY KEY,
    order_id        VARCHAR(40) NOT NULL REFERENCES design_order (order_id),
    rspu_id         VARCHAR(40) NOT NULL,
    rsku_id         VARCHAR(40),
    variant_id      VARCHAR(40),
    product_name    VARCHAR(256),
    model           VARCHAR(128),
    image_id        VARCHAR(40),
    quantity        INT NOT NULL DEFAULT 1,
    original_price  TEXT,                    -- AES 加密单价快照
    final_price     TEXT,                    -- AES 加密到手单价快照
    factory_code    VARCHAR(16),             -- 供应商维度统计用
    snapshot_json   TEXT,                    -- 完整商品快照（规格/材质/颜色等）
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_order_item_order ON design_order_item (order_id);
CREATE INDEX IF NOT EXISTS idx_order_item_rspu ON design_order_item (rspu_id);
CREATE INDEX IF NOT EXISTS idx_order_item_factory ON design_order_item (factory_code);

-- 轻量配置表（订单全局折扣率等）
CREATE TABLE IF NOT EXISTS sys_config (
    config_key   VARCHAR(64) PRIMARY KEY,
    config_value TEXT,
    remark       VARCHAR(256),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW()
);
INSERT INTO sys_config (config_key, config_value, remark) VALUES
('order.price_rate', '1', '订单全局折扣率')
ON CONFLICT (config_key) DO NOTHING;

-- 订单状态字典
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('design_order_status', 'PENDING', '待确认', 1),
('design_order_status', 'CONFIRMED', '已确认', 2),
('design_order_status', 'PRODUCING', '生产中', 3),
('design_order_status', 'COMPLETED', '已完成', 4),
('design_order_status', 'CANCELLED', '已取消', 5)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 订单权限（方案约定：ADMIN + DESIGNER 授予，通用映射先于本权限插入执行，需显式补插）
INSERT INTO sys_permission (permission_code, permission_name) VALUES
('order:read', '查看订单'),
('order:create', '创建订单'),
('order:update', '编辑订单'),
('order:delete', '删除订单')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code IN ('ADMIN', 'DESIGNER')
  AND p.permission_code LIKE 'order:%'
ON CONFLICT DO NOTHING;
