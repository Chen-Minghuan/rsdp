-- ============================================================
-- V2 文档（PDF）导入批次表（阶段 3.1：PDF 导入异步批次化）
-- 与 database/schema/05_excel_import.sql + cross_domain_fk.sql 中的 document_import_batch 段保持一致（幂等写法）
-- ============================================================

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
);
CREATE INDEX IF NOT EXISTS idx_document_import_batch_status ON document_import_batch(status);
CREATE INDEX IF NOT EXISTS idx_document_import_batch_created_by ON document_import_batch(created_by);

ALTER TABLE document_import_batch
    DROP CONSTRAINT IF EXISTS fk_document_import_batch_created_by;
ALTER TABLE document_import_batch
    ADD CONSTRAINT fk_document_import_batch_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(user_id);
