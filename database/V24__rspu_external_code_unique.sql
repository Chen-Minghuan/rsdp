-- ============================================================================
-- V17 迁移：rspu_master.external_code 部分唯一索引
-- ============================================================================
-- 背景：rspu_master.external_code 此前只有普通索引 idx_rspu_external_code，
--       并发 Excel 导入（人工导入 ProductImportService / AI 导入 ExcelAiImportService）
--       可能产生重复外部编码，导致同一外部编码命中多条产品、更新目标不确定。
-- 方案：新增部分唯一索引 uk_rspu_external_code，仅约束未软删除且外部编码非空的记录。
-- 幂等：
--   1. 先 DO 块检查存量数据是否存在重复外部编码——存在重复时 RAISE WARNING 并跳过
--      建索引（需人工清理重复后重新执行本脚本），避免迁移直接失败；
--   2. 无重复时 CREATE UNIQUE INDEX IF NOT EXISTS，可重复执行。
-- 同步约定：V1__init_db.sql 与 reset_db.sql 已并入同一索引（全新初始化直接创建）。
-- ============================================================================

DO $$
BEGIN
    IF EXISTS (
        SELECT external_code
        FROM rspu_master
        WHERE deleted_at IS NULL AND external_code IS NOT NULL
        GROUP BY external_code
        HAVING COUNT(*) > 1
    ) THEN
        RAISE WARNING 'rspu_master 存在重复 external_code（未软删除），跳过创建 uk_rspu_external_code；请先清理重复数据后重新执行 V17 迁移';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS uk_rspu_external_code
            ON rspu_master(external_code)
            WHERE deleted_at IS NULL AND external_code IS NOT NULL;
    END IF;
END $$;
