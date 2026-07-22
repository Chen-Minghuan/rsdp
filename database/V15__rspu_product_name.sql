-- RSDP V15 数据库迁移：rspu_master 增加产品名称字段
-- 背景：AI 识别（VisionService）已在 prompt 中提取 ocr.productName，
--       但 rspu_master 无产品名称列，识别结果从未消费。
--       本迁移补充 product_name 列，AI 识别落库与 Excel AI 导入品名统一写入。
-- 幂等：ADD COLUMN IF NOT EXISTS，可重复执行。

ALTER TABLE rspu_master
    ADD COLUMN IF NOT EXISTS product_name VARCHAR(256);

COMMENT ON COLUMN rspu_master.product_name IS '产品名称（AI OCR 提取 / Excel 导入品名；纯图无文字时为空）';
