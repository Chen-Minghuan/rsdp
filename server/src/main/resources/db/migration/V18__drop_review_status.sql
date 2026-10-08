-- V18：移除产品二次复核机制
-- 背景：Excel 数据清洗环节即人工审核确认，执行导入直接入库；复核状态（待复核/已确认/存疑）
-- 不再承载任何业务流程，统一下线相关字段与索引。
-- 幂等：IF EXISTS 守卫，可重复执行。

ALTER TABLE rspu_master DROP COLUMN IF EXISTS review_status;
ALTER TABLE rspu_master DROP COLUMN IF EXISTS review_comment;
DROP INDEX IF EXISTS idx_rspu_review;

ALTER TABLE rsku_supply DROP COLUMN IF EXISTS review_status;

ALTER TABLE style_case DROP COLUMN IF EXISTS review_status;
