-- V24：精简 rsku_supply / rspu_master 冗余字段（产品录入链路改造 P1a，2026-10-14）
--
-- 剔除依据（全代码库引用扫描，Java/前端/SQL 三方核对）：
--   rsku_supply 六个死字段（零读写）：structure_strength_rating / flame_retardant_capability /
--     factory_photo_path / factory_credit_score / on_time_rate / quality_return_rate；
--   rsku_supply 仅 RskuService 写入、无任何消费方：price_unit / moq_unit；
--   rspu_master budget_range：仅 Excel 导入写入 + 详情页原样展示，无筛选/计算消费。
--
-- 明确保留：currency_code / tax_included / quote_date（报价语义，评审确认保留），
--   effective_from / effective_to（「可用出厂报价」有效期口径的真实消费方：
--   RskuAvailability 与 RskuSupplyMapper.xml availableFactoryQuote，两列不为空即参与选价门禁）。
--
-- 同步约定：database/schema/02_product.sql 与 database/ops/reset_db.sql 已同步删除对应列。

ALTER TABLE rsku_supply DROP COLUMN IF EXISTS structure_strength_rating;
ALTER TABLE rsku_supply DROP COLUMN IF EXISTS flame_retardant_capability;
ALTER TABLE rsku_supply DROP COLUMN IF EXISTS factory_photo_path;
ALTER TABLE rsku_supply DROP COLUMN IF EXISTS factory_credit_score;
ALTER TABLE rsku_supply DROP COLUMN IF EXISTS on_time_rate;
ALTER TABLE rsku_supply DROP COLUMN IF EXISTS quality_return_rate;
ALTER TABLE rsku_supply DROP COLUMN IF EXISTS price_unit;
ALTER TABLE rsku_supply DROP COLUMN IF EXISTS moq_unit;

ALTER TABLE rspu_master DROP COLUMN IF EXISTS budget_range;
