-- =============================================================
-- V12：rspu_variant 尺寸/颜色/材质组合部分唯一索引
-- 背景：服务层已校验同 RSPU 下 (size_code, color_code, material_code) 不重复，
--       数据库补唯一索引兜底并发创建/导入产生的重复变体。
--       NULLS NOT DISTINCT（PG15+）使 NULL 参与唯一性判定；
--       部分索引仅约束未软删除记录。
-- 注意：若存量数据已存在重复组合，需先清理重复再执行本脚本。
-- 执行：psql -d rsdp -f V12__variant_unique_dims.sql
-- =============================================================

BEGIN;

CREATE UNIQUE INDEX IF NOT EXISTS idx_variant_unique_dims
    ON rspu_variant(rspu_id, size_code, color_code, material_code)
    NULLS NOT DISTINCT
    WHERE deleted_at IS NULL;

COMMIT;
