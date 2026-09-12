-- ============================================================
-- V4 RSPU 疑似同款配对表（合并工具 M2：同款检测命中结构化落库，决策点②共享主档模型配套）
-- 与 database/schema/02_product.sql 中的 rspu_duplicate_suspect 段保持一致（幂等写法）
-- ============================================================

CREATE TABLE IF NOT EXISTS rspu_duplicate_suspect (
    suspect_id BIGSERIAL PRIMARY KEY,
    rspu_id VARCHAR(64) NOT NULL,                    -- 被标存疑的新品
    matched_rspu_id VARCHAR(64) NOT NULL,            -- 召回命中的疑似同款
    similarity NUMERIC(5,4) NOT NULL,                -- 向量相似度（0~1）
    status VARCHAR(16) DEFAULT 'pending',            -- pending / merged / dismissed
    resolved_by VARCHAR(64),                         -- 处理人（合并/排除操作人 username）
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (rspu_id, matched_rspu_id),
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (matched_rspu_id) REFERENCES rspu_master(rspu_id)
);
CREATE INDEX IF NOT EXISTS idx_rspu_dup_suspect_status ON rspu_duplicate_suspect(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_rspu_dup_suspect_matched ON rspu_duplicate_suspect(matched_rspu_id);
