-- ============================================================
-- V7：订单全局折扣率数据库层约束
-- ============================================================
-- 与应用层 ConfigService.validateOrderPriceRate 互补，防止任何旁路写入非法折扣率。
-- 仅当表已存在时执行，兼容新环境（V1/V5 已含 CHECK）与旧环境升级。

ALTER TABLE design_order
    DROP CONSTRAINT IF EXISTS chk_design_order_price_rate;

ALTER TABLE design_order
    ADD CONSTRAINT chk_design_order_price_rate
        CHECK (price_rate >= 0 AND price_rate <= 1);
