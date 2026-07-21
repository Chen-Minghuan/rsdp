-- ============================================================================
-- V7 迁移：变体表并发唯一索引
-- ============================================================================
-- 背景：产品人工批量导入并发创建变体时，可能产生 (rspu_id, 尺寸, 颜色, 材质)
-- 完全相同的重复变体。增加部分唯一索引兜底，冲突时由应用层捕获
-- DataIntegrityViolationException 处理。
-- 说明：
--   1. COALESCE 将 NULL 归一为空串，避免 PostgreSQL 唯一索引将 NULL 视为互不相同；
--   2. 部分索引仅约束未软删除记录（deleted_at IS NULL）。
-- 幂等：CREATE UNIQUE INDEX IF NOT EXISTS 可重复执行。
-- 注意：若存量数据已存在重复变体，需先清理重复再执行本脚本。
-- ============================================================================

CREATE UNIQUE INDEX IF NOT EXISTS uk_variant_attrs
    ON rspu_variant (
        rspu_id,
        COALESCE(size_code, ''),
        COALESCE(color_code, ''),
        COALESCE(material_code, '')
    )
    WHERE deleted_at IS NULL;
