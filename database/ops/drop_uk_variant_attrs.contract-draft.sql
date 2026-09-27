-- ============================================================
-- 【已作废，禁止执行】P0-C Contract Cutover 迁移脚本（草案存档）
-- ============================================================
-- 作废原因（2026-09-24 评审决策）：本草案的全部前提——variant_fingerprint 表
-- 双写、Fingerprint Backfill、两个 Fingerprint 唯一索引替代 uk_variant_attrs
-- 承担写入门禁、rsdp.variant-identity feature flag——已随 variant_fingerprint
-- 表整体摘除而不复存在。当前 uk_variant_attrs 是变体判重的唯一写入门禁，
-- 删除它将失去防重保护。下方内容仅作设计存档，勿移入 migration 目录。
-- ============================================================
-- 正式文件名（届时移入 server/src/main/resources/db/migration/）：
--     V<下一个可用号>__drop_uk_variant_attrs.sql（v3.3 合并迁移已占用 V14，届时顺延）
--
-- 设计依据（v3.3 设计文档）：
--   §74.3 Contract Cutover（短维护窗口）：
--     1. 停止 Variant 和 Excel Import 相关写入；
--     2. 重跑四项校验（本脚本以 DO 块内建，任一不过即 RAISE 中断，迁移整体回滚）：
--        - fingerprint zero-missing（未软删 Variant 全部有 v1 Fingerprint）
--        - fingerprint zero-conflict（无同 RSPU 同 v1 指纹的多存活 Variant 组）
--        - one-variant-one-fingerprint-per-version（由 uk_variant_fingerprint_variant_version
--          在 Expand 期已保证，此处核验索引存在且有效）
--        - RSPU/Variant composite integrity（无指向错配 RSPU 的 Fingerprint 行）
--     3. 确认两个 Fingerprint Unique 已存在且有效（pg_index.indisvalid / indisready）；
--     4. 只删除旧 uk_variant_attrs（不 DROP 列、不删其他索引、不用 CONCURRENTLY）；
--     5. 提交 Migration；
--     6. 打开 fingerprint identity feature flag
--        （rsdp.variant-identity.fingerprint-read-enabled，默认已开启；
--        Cutover 后旧口径回退保留作为双保险，旧索引已删后由两个 Fingerprint 唯一索引承担写入门禁）；
--     7. 恢复写入。
--   §74 Cutover 不再 CREATE 第三个 uk_variant_fingerprint——两个目标唯一索引已在
--   Expand（V20）创建；不使用 CONCURRENTLY（§74：短维护窗口 + 普通 Flyway Migration）。
--   §74.4 回滚：Flyway 事务内失败整体回滚，旧 uk_variant_attrs 仍在；提交后应用回滚
--   须先关闭 feature flag，确认无旧规则无法表达的新 Variant，再执行预演过的反向 Migration。
--
-- 前置条件（执行前逐项核验，参考 P0-A Phase 5 纪律）：
--   1. variant_fingerprint Backfill（VariantFingerprintBackfillService）已在目标库执行，
--      报告 conflicted = 0；
--   2. 双写挂钩（VariantFingerprintInterceptor）已上线，新增 Variant 全部双路维护；
--   3. 维护窗口内 Variant / Excel Import 写入已停止；
--   4. 已完成备份确认。
--
-- 本脚本尚未在任何库执行；移入 migration 目录前请再次核对版本号与前置条件。
-- ============================================================

-- ---------- 维护窗口内校验（任一不过即中断，事务整体回滚，uk_variant_attrs 保留） ----------
DO $$
DECLARE
    missing_count BIGINT;
    conflict_count BIGINT;
    mismatch_count BIGINT;
BEGIN
    -- 1. fingerprint zero-missing：未软删 Variant 必须全部持有 v1 Fingerprint
    SELECT COUNT(*) INTO missing_count
    FROM rspu_variant v
    WHERE v.deleted_at IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM variant_fingerprint f
          WHERE f.variant_id = v.variant_id
            AND f.fingerprint_version = 1
      );
    IF missing_count > 0 THEN
        RAISE EXCEPTION 'P0-C Contract 校验失败：% 个未软删 Variant 缺 v1 Fingerprint（先重跑 Backfill）', missing_count;
    END IF;

    -- 2. fingerprint zero-conflict：同 RSPU 同 v1 指纹不得对应多个未软删 Variant
    SELECT COUNT(*) INTO conflict_count
    FROM (
        SELECT v.rspu_id,
               COALESCE(v.size_code, v.size_text, ''),
               COALESCE(v.color_code, v.color_text, ''),
               COALESCE(v.material_code, v.material_text, '')
        FROM rspu_variant v
        WHERE v.deleted_at IS NULL
        GROUP BY 1, 2, 3, 4
        HAVING COUNT(*) > 1
    ) dup;
    IF conflict_count > 0 THEN
        RAISE EXCEPTION 'P0-C Contract 校验失败：% 组同指纹多存活 Variant 冲突未治理', conflict_count;
    END IF;

    -- 3. composite integrity：Fingerprint 行必须与 Variant 同 RSPU（组合 FK 之外的显式核验）
    SELECT COUNT(*) INTO mismatch_count
    FROM variant_fingerprint f
    JOIN rspu_variant v ON v.variant_id = f.variant_id
    WHERE v.rspu_id <> f.rspu_id;
    IF mismatch_count > 0 THEN
        RAISE EXCEPTION 'P0-C Contract 校验失败：% 行 Fingerprint 与 Variant 的 RSPU 错配', mismatch_count;
    END IF;

    -- 4. 两个目标唯一索引必须已存在且有效（Expand 期创建；one-variant-one-fingerprint-per-version
    --    由 uk_variant_fingerprint_variant_version 承载）
    IF NOT EXISTS (
        SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
        WHERE c.relname = 'uk_variant_fingerprint_value' AND i.indisvalid AND i.indisready
    ) OR NOT EXISTS (
        SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
        WHERE c.relname = 'uk_variant_fingerprint_variant_version' AND i.indisvalid AND i.indisready
    ) THEN
        RAISE EXCEPTION 'P0-C Contract 校验失败：两个目标 Fingerprint 唯一索引不存在或无效';
    END IF;
END $$;

-- ---------- 只删除旧索引（不 DROP 列、不用 CONCURRENTLY、不动其他对象） ----------
DROP INDEX uk_variant_attrs;

-- 同步义务（正式执行时）：database/schema/02_product.sql 与 database/ops/reset_db.sql
-- 移除 uk_variant_attrs 定义（对齐 P0-A V16 先例），并跑 make check-migration-sync。
