-- ============================================================
-- V7 RSPU 主供唯一 DB 级保障（阶段 5 扫尾）：
-- rspu_factory_mapping 每 RSPU 最多一条 is_primary = true——应用层
-- clearOtherPrimary 是先清后写的 check-then-act，并发下可能双主供，
-- 用部分唯一索引做 DB 兜底（仅约束 is_primary = true 的行）
-- 与 database/schema/03_factory.sql + ops/reset_db.sql 对应段保持一致（幂等写法）
-- ============================================================

DROP INDEX IF EXISTS uk_rspu_factory_mapping_primary;
CREATE UNIQUE INDEX IF NOT EXISTS uk_rspu_factory_mapping_primary
    ON rspu_factory_mapping(rspu_id) WHERE is_primary = true;
