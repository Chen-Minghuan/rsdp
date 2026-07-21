-- =============================================================
-- V10：拓宽业务主键列宽
-- 背景：业务主键由 8 位截断 UUID 升级为完整 UUID（IdGenerator），
--       格式为 PREFIX-<36 字符 UUID>，最长 42 字符（如 BATCH-/EXCEL-）。
--       原 VARCHAR(32)/VARCHAR(40) 列无法容纳，统一拓宽至 VARCHAR(64)。
-- 执行：psql -d rsdp -f V10__widen_id_columns.sql
-- =============================================================

BEGIN;

-- Excel AI 导入批次相关（原 VARCHAR(32)）
ALTER TABLE excel_import_batch ALTER COLUMN batch_id TYPE VARCHAR(64);
ALTER TABLE excel_import_row ALTER COLUMN batch_id TYPE VARCHAR(64);
ALTER TABLE rspu_price_column_mapping ALTER COLUMN batch_id TYPE VARCHAR(64);
ALTER TABLE excel_import_price_column ALTER COLUMN batch_id TYPE VARCHAR(64);

-- 收藏夹（原 VARCHAR(40)）
ALTER TABLE user_favorite ALTER COLUMN favorite_id TYPE VARCHAR(64);
ALTER TABLE user_favorite ALTER COLUMN rspu_id TYPE VARCHAR(64);

-- 设计项目（原 VARCHAR(40)）
ALTER TABLE project ALTER COLUMN project_id TYPE VARCHAR(64);
ALTER TABLE scheme ALTER COLUMN project_id TYPE VARCHAR(64);

-- 设计订单（原 VARCHAR(40)）
ALTER TABLE design_order ALTER COLUMN order_id TYPE VARCHAR(64);
ALTER TABLE design_order ALTER COLUMN project_id TYPE VARCHAR(64);
ALTER TABLE design_order_item ALTER COLUMN order_id TYPE VARCHAR(64);
ALTER TABLE design_order_item ALTER COLUMN rspu_id TYPE VARCHAR(64);
ALTER TABLE design_order_item ALTER COLUMN rsku_id TYPE VARCHAR(64);
ALTER TABLE design_order_item ALTER COLUMN variant_id TYPE VARCHAR(64);
ALTER TABLE design_order_item ALTER COLUMN image_id TYPE VARCHAR(64);

COMMIT;
