-- V21：RSPU 商品名称
-- 产品库列表「商品信息」列需要展示商品名称；rspu_master 此前无名称字段。
-- 各录入链路按来源填充：AI 识别取 OCR productName、AI Excel 取品名列、手工/工厂录入表单填写、Excel 模板可选「商品名称」列。

ALTER TABLE rspu_master ADD COLUMN IF NOT EXISTS product_name VARCHAR(256);

COMMENT ON COLUMN rspu_master.product_name IS '商品名称（AI OCR/录入表单/Excel导入填充，可空）';
