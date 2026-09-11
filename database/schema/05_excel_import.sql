-- ============================================================
-- RSDP 基线 DDL · 05 导入域（05_excel_import.sql）
-- 包含表：excel_import_batch, excel_import_row, document_import_batch（PDF 文档导入批次，V2 并入）
-- 执行顺序：schema/ 目录按文件名 01 → 12 → 99 依次执行（编号即执行顺序，基线由原 V1__init_db.sql 按域拆分而来）
-- 同步约定：新增/修改本域表结构时须同步 ops/reset_db.sql，约定详见 database/README.md
-- ============================================================
-- Excel AI 辅助导入批次表
CREATE TABLE IF NOT EXISTS excel_import_batch (
    batch_id VARCHAR(64) PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    storage_path VARCHAR(512),                      -- 原始 Excel 文件存储路径
    status VARCHAR(20) DEFAULT 'pending',
    total_rows INT DEFAULT 0,
    success_count INT DEFAULT 0,
    failed_count INT DEFAULT 0,
    column_mapping JSONB,
    preview_rows JSONB,
    price_columns JSONB,
    failures JSONB,
    -- 工厂关联、发货地、交期/MOQ、导入元数据（V2 并入）
    factory_code VARCHAR(16),
    factory_name VARCHAR(128),
    shipping_warehouse_id VARCHAR(64),
    shipping_from VARCHAR(128),
    default_lead_time_days INTEGER,
    default_moq INTEGER,
    category_hint VARCHAR(16),
    header_row_count INTEGER DEFAULT 2,
    data_start_row INTEGER DEFAULT 3,
    import_note TEXT,
    sheet_index INT NOT NULL DEFAULT 0,                 -- 多 Sheet 文件批次解析的工作表索引（V18 并入）
    processed_at TIMESTAMPTZ,
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ
    -- 外键在 sys_user 表创建后通过 ALTER TABLE 添加
);
CREATE INDEX IF NOT EXISTS idx_excel_import_batch_status ON excel_import_batch(status);
-- 僵死批次收割索引（V43）：reapStaleImporting WHERE status='importing' AND (updated_at IS NULL OR updated_at < ?)
CREATE INDEX IF NOT EXISTS idx_import_batch_stale ON excel_import_batch(updated_at) WHERE status = 'importing';
CREATE INDEX IF NOT EXISTS idx_excel_import_batch_created_by ON excel_import_batch(created_by);
CREATE INDEX IF NOT EXISTS idx_excel_import_batch_factory ON excel_import_batch(factory_code);

-- Excel 行级导入记录表（V2 并入）
CREATE TABLE IF NOT EXISTS excel_import_row (
    row_id BIGSERIAL PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
    excel_row_number INTEGER NOT NULL,             -- Excel中的原始行号
    row_type VARCHAR(32) NOT NULL,                 -- product/module/header/unknown/preview_placeholder
    parent_row_id BIGINT,                          -- 模块行关联到产品型号行
    raw_data JSONB NOT NULL,                       -- 原始数据快照
    mapped_fields JSONB,                           -- AI映射后的字段
    selected_price_columns JSONB,                  -- 识别的价格列
    status VARCHAR(16) DEFAULT 'pending',          -- pending/processing/success/failed/skipped
    processing_stage VARCHAR(32),                  -- 当前处理阶段
    generated_rspu_id VARCHAR(64),                 -- 生成的RSPU ID
    generated_variant_id VARCHAR(64),              -- 生成的变体ID
    generated_rsku_ids JSONB,                      -- ["RSKU-001", "RSKU-002"]
    failure_reason TEXT,                           -- 失败原因描述
    failure_stage VARCHAR(32),                     -- 在哪个阶段失败
    extracted_image_count INTEGER DEFAULT 0,       -- 提取到的图片数量
    image_asset_ids JSONB,                         -- ["IMG-001", "IMG-002"]
    override_image_asset_ids JSONB,                -- 用户在数据清洗页编辑后的图片 asset ID 列表（V33）
    ai_task_id VARCHAR(64),                        -- 关联的异步AI识别任务
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    FOREIGN KEY (batch_id) REFERENCES excel_import_batch(batch_id),
    FOREIGN KEY (parent_row_id) REFERENCES excel_import_row(row_id),
    FOREIGN KEY (generated_rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (generated_variant_id) REFERENCES rspu_variant(variant_id),
    UNIQUE (batch_id, excel_row_number)
);
CREATE INDEX IF NOT EXISTS idx_import_row_batch ON excel_import_row(batch_id, status);
CREATE INDEX IF NOT EXISTS idx_import_row_type ON excel_import_row(batch_id, row_type);
CREATE INDEX IF NOT EXISTS idx_import_row_rspu ON excel_import_row(generated_rspu_id);
CREATE INDEX IF NOT EXISTS idx_import_row_parent ON excel_import_row(parent_row_id);

-- 文档（PDF）导入批次表（阶段 3.1：PDF 导入异步批次化）
-- 提交即返回 batchId，批处理复用 async_task 体系（task_type=document_import）异步执行；
-- 行级/页级明细收敛在 JSONB 字段（failures/page_results），不新建行表
CREATE TABLE IF NOT EXISTS document_import_batch (
    batch_id VARCHAR(64) PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    storage_path VARCHAR(512),                       -- 原始 PDF 文件存储路径（MinIO/本地磁盘）
    status VARCHAR(20) DEFAULT 'pending',            -- pending/processing/done/partial_success/failed
    total_pages INT DEFAULT 0,
    processed_pages INT DEFAULT 0,                   -- 已处理页数（前端进度轮询）
    product_pages INT DEFAULT 0,
    detected_products INT DEFAULT 0,
    success_count INT DEFAULT 0,
    fail_count INT DEFAULT 0,
    skip_count INT DEFAULT 0,                        -- 图片 contentHash 查重命中跳过建档数
    failures JSONB,                                  -- [{pageIndex, reason}]，含"已存在跳过"明细
    page_results JSONB,                              -- [{pageIndex, pageType, productCount, status}]
    task_ids JSONB,                                  -- 建档 product_entry 任务 ID（与 rspu_ids 一一对应，供前端继续轮询）
    rspu_ids JSONB,
    category_hint VARCHAR(16),
    error_message TEXT,
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
    -- created_by 外键在 cross_domain_fk.sql 后置（sys_user 在 09 域创建）
);
CREATE INDEX IF NOT EXISTS idx_document_import_batch_status ON document_import_batch(status);
CREATE INDEX IF NOT EXISTS idx_document_import_batch_created_by ON document_import_batch(created_by);


