-- 为 RSPU 变体表增加数量/件数字段，支持 Excel AI 导入时携带数量信息
ALTER TABLE rspu_variant ADD COLUMN IF NOT EXISTS quantity INTEGER;

COMMENT ON COLUMN rspu_variant.quantity IS '数量/件数（Excel 导入时携带，可选）';
