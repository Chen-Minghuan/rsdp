-- V26：Excel 导入映射模板（标准模板直导模式，2026-10-14，录入链路改造 P1）
--
-- 背景：各工厂报价表统一为标准录入模板后，表头结构固定，每次都调 AI 识别映射是浪费。
-- 本迁移引入映射模板表：以「清洗后表头有序列表的 SHA-256」为模板指纹，
-- 上传文件的表头指纹命中模板时跳过 AI 映射调用，直接使用模板映射与价格列角色。
--
-- 同步约定：database/schema/05_excel_import.sql 与 ops/reset_db.sql 已同步。

CREATE TABLE IF NOT EXISTS excel_import_mapping_template (
    template_id VARCHAR(64) PRIMARY KEY,
    template_name VARCHAR(128) NOT NULL,
    header_signature VARCHAR(64) NOT NULL,          -- 清洗后表头有序列表 SHA-256（模板指纹）
    column_mapping JSONB NOT NULL,                  -- 表头 → 标准字段（与批次 column_mapping 同构）
    price_column_roles JSONB,                       -- 价格列表头 → 角色（factory/sales/none），可空
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_import_mapping_template_signature
    ON excel_import_mapping_template(header_signature);
COMMENT ON TABLE excel_import_mapping_template IS 'Excel 导入字段映射模板（标准模板直导）：表头指纹命中时跳过 AI 映射';

-- 批次记录模板指纹：confirm 时「保存为模板」直接取用，避免从 preview_rows 反推表头
ALTER TABLE excel_import_batch ADD COLUMN IF NOT EXISTS header_signature VARCHAR(64);
