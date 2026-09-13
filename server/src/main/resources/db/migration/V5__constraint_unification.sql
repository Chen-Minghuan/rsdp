-- ============================================================
-- V5 约束策略统一（阶段 4.4）：
-- ① idx_rsku_unique 重建 NULLS NOT DISTINCT（PG 15+）——variant_id IS NULL 的行此前不参与判重
--    （NULL 互不相等），同 (rspu_id, NULL, factory_code) 可重复；与 lead_time_rule 既有先例对齐
-- ② rspu_code/rsku_code 行内 UNIQUE 改部分唯一索引（仅约束未软删行）——与软删兼容，
--    对齐 external_code 的既有部分唯一先例
-- 与 database/schema/02_product.sql + ops/reset_db.sql 对应段保持一致（幂等写法）
-- ============================================================

-- ① idx_rsku_unique 重建（NULL 参与判重）
DROP INDEX IF EXISTS idx_rsku_unique;
CREATE UNIQUE INDEX IF NOT EXISTS idx_rsku_unique
    ON rsku_supply(rspu_id, variant_id, factory_code) NULLS NOT DISTINCT WHERE deleted_at IS NULL;

-- ② rspu_code 部分唯一化（替换行内 UNIQUE 约束）
ALTER TABLE rspu_master DROP CONSTRAINT IF EXISTS rspu_master_rspu_code_key;
DROP INDEX IF EXISTS uk_rspu_code_alive;
CREATE UNIQUE INDEX IF NOT EXISTS uk_rspu_code_alive
    ON rspu_master(rspu_code) WHERE deleted_at IS NULL AND rspu_code IS NOT NULL;

-- ② rsku_code 部分唯一化（替换行内 UNIQUE 约束）
ALTER TABLE rsku_supply DROP CONSTRAINT IF EXISTS rsku_supply_rsku_code_key;
DROP INDEX IF EXISTS uk_rsku_code_alive;
CREATE UNIQUE INDEX IF NOT EXISTS uk_rsku_code_alive
    ON rsku_supply(rsku_code) WHERE deleted_at IS NULL AND rsku_code IS NOT NULL;
