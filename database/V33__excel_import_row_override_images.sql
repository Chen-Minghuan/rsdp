-- V33: Excel 导入行级用户覆盖图片
-- 背景：数据清洗页需要支持用户补充/替换/删除某行的产品图片，
-- 这些编辑结果通过 override_image_asset_ids 保存，导入时优先使用。
-- 幂等：IF NOT EXISTS

ALTER TABLE excel_import_row
    ADD COLUMN IF NOT EXISTS override_image_asset_ids JSONB;

COMMENT ON COLUMN excel_import_row.override_image_asset_ids IS '用户在数据清洗页编辑后的图片 asset ID 列表，导入时优先于 Excel 内嵌图片';
