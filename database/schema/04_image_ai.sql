-- ============================================================
-- RSDP 基线 DDL · 04 图片与AI识别域（04_image_ai.sql）
-- 包含表：image_assets, product_image_embedding, ai_recognition, async_task
-- 执行顺序：schema/ 目录按文件名 01 → 12 → 99 依次执行（编号即执行顺序，基线由原 V1__init_db.sql 按域拆分而来）
-- 同步约定：新增/修改本域表结构时须同步 ops/reset_db.sql，约定详见 database/README.md
-- ============================================================
-- pgvector 扩展：图片向量存储（P0 向量库收口，随本域最先建表前安装）
CREATE EXTENSION IF NOT EXISTS vector;

-- 图片资源表
CREATE TABLE IF NOT EXISTS image_assets (
    image_id VARCHAR(64) PRIMARY KEY,
    rspu_id VARCHAR(64),
    variant_id VARCHAR(64),                        -- 变体专属图
    rsku_id VARCHAR(64),                           -- 工厂实拍图
    image_type VARCHAR(32) NOT NULL,               -- white_bg/factory_photo/detail/original
    storage_path TEXT NOT NULL,
    storage_url TEXT,
    file_size BIGINT,
    width INTEGER,
    height INTEGER,
    format VARCHAR(16),
    is_primary BOOLEAN DEFAULT FALSE,
    ai_processed BOOLEAN DEFAULT FALSE,
    quality_score DECIMAL(5, 4),
    content_hash VARCHAR(64),                        -- 图片内容 SHA-256（录入查重，V31）
    content_revision BIGINT NOT NULL DEFAULT 1,      -- 图片内容版本：替换/裁剪覆盖时递增，向量按版本防旧写（P0）
    uploaded_by VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (variant_id) REFERENCES rspu_variant(variant_id),
    FOREIGN KEY (rsku_id) REFERENCES rsku_supply(rsku_id)
);

-- 图片向量表（P0）：一张图片一条当前向量，编码配置标识见 ProductVectorProfile（模型+维度+预处理+距离）
-- 商品归属经 image_assets 关联，不冗余可变业务事实；图片物理删除级联清向量
CREATE TABLE IF NOT EXISTS product_image_embedding (
    image_id VARCHAR(64) PRIMARY KEY REFERENCES image_assets(image_id) ON DELETE CASCADE,
    profile_id VARCHAR(64) NOT NULL,
    source_revision BIGINT NOT NULL CHECK (source_revision > 0),
    input_hash VARCHAR(64) NOT NULL,                 -- 实际送入 embedding API 的字节 SHA-256（缩放后）
    embedding vector(1024) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_product_image_embedding_hnsw
    ON product_image_embedding USING hnsw (embedding vector_cosine_ops);

-- AI 识别记录表
CREATE TABLE IF NOT EXISTS ai_recognition (
    recognition_id VARCHAR(64) PRIMARY KEY,
    image_id VARCHAR(64),
    rspu_id VARCHAR(64),
    task_id VARCHAR(64),
    model_name VARCHAR(64),
    model_version VARCHAR(64),                   -- 识别模型版本（V43）
    prompt_version VARCHAR(64),                  -- 提示词模板版本（V43）
    recognition_type VARCHAR(16),                  -- encode/label/judge
    endpoint TEXT,
    input_data JSONB,
    output_data JSONB,
    parsed_style VARCHAR(64),
    parsed_six_dim JSONB,
    parsed_color_hsv JSONB,
    parsed_scene_tags JSONB,
    parsed_ocr JSONB,
    confidence VARCHAR(16),
    processing_time_ms INTEGER,
    status VARCHAR(16),
    error_message TEXT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (image_id) REFERENCES image_assets(image_id),
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id)
);

-- 异步任务表
CREATE TABLE IF NOT EXISTS async_task (
    task_id VARCHAR(64) PRIMARY KEY,
    task_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) DEFAULT 'pending',
    progress INTEGER DEFAULT 0,
    input_data JSONB,
    result_data JSONB,
    error_message TEXT,
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);

-- 图片索引
CREATE INDEX IF NOT EXISTS idx_image_rspu ON image_assets(rspu_id, image_type);
CREATE INDEX IF NOT EXISTS idx_image_variant ON image_assets(variant_id, image_type);
CREATE INDEX IF NOT EXISTS idx_image_primary ON image_assets(rspu_id, is_primary);
CREATE INDEX IF NOT EXISTS idx_image_rsku ON image_assets(rsku_id);
-- 内容哈希部分索引（V43 收窄）：唯一查询入口按 content_hash = ? AND deleted_at IS NULL 查重，存量 NULL 行无查询价值
CREATE INDEX IF NOT EXISTS idx_image_content_hash ON image_assets(content_hash) WHERE deleted_at IS NULL AND content_hash IS NOT NULL;

-- AI 识别索引
CREATE INDEX IF NOT EXISTS idx_ai_image ON ai_recognition(image_id, recognition_type);
CREATE INDEX IF NOT EXISTS idx_ai_rspu ON ai_recognition(rspu_id, created_at);
CREATE INDEX IF NOT EXISTS idx_ai_recognition_task ON ai_recognition(task_id);

CREATE INDEX IF NOT EXISTS idx_task_status ON async_task(status, created_at);
