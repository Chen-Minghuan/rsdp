-- 订单创建幂等控制：增加幂等键字段与唯一索引

ALTER TABLE design_order ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);

DROP INDEX IF EXISTS uk_design_order_idempotency;
CREATE UNIQUE INDEX uk_design_order_idempotency
    ON design_order(created_by, idempotency_key) WHERE deleted_at IS NULL;
