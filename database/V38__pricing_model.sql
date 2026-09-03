-- ============================================================================
-- V38：价格体系改造 P1（标准售价 + 订单按售价计价）
-- 1) design_order_item.list_price：标准售价快照（NUMERIC 明文）
-- 2) sys_config 种子 pricing.markup.global = 2.5（全局加价倍率）
-- 标准售价解析优先级：rspu_master.retail_price（有则以此为准）
--   → 成本 × 全局加价倍率 → 都空则该 RSKU 无法定价（订单/报价拦截报错"未定价"）
-- 折扣率语义切换：order.price_rate / company.price_ratio 从"乘出厂价"改为"乘标准售价"
-- 注意同步：V1__init_db.sql / V1__seed_data.sql / reset_db.sql 三处已同步更新
-- ============================================================================

ALTER TABLE design_order_item ADD COLUMN IF NOT EXISTS list_price NUMERIC(12,2);
COMMENT ON COLUMN design_order_item.list_price IS '标准售价快照（明文 NUMERIC：售价对客户可见不敏感，且便于 SQL 分析；刻意区别于 original_price/final_price/adjust_price 三列 TEXT 存 AES 密文）';

-- 全局加价倍率（幂等）
INSERT INTO sys_config (config_key, config_value, remark) VALUES
('pricing.markup.global', '2.5', '全局加价倍率：标准售价 = 成本 × 倍率（RSPU 已录入建议销售价 retail_price 时优先）；仅影响新订单/新报价')
ON CONFLICT (config_key) DO NOTHING;
