-- V32: 拓宽 excel_import_row.row_type，容纳数据清洗阶段的占位行类型
-- 背景：preview_placeholder（18 字符）超出原 VARCHAR(16) 限制，导致粘贴/上传覆盖图时插入失败。
-- 幂等：IF EXISTS / IF NOT EXISTS

ALTER TABLE excel_import_row
    ALTER COLUMN row_type TYPE VARCHAR(32);

COMMENT ON COLUMN excel_import_row.row_type IS '行类型：product/module/header/unknown/preview_placeholder 等';
