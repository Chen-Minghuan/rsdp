-- ============================================================
-- V3 rspu_master 加录入人列（阶段 4.2：RSPU 归属溯源，决策点②共享主档模型配套）
-- 与 database/schema/02_product.sql + cross_domain_fk.sql 中的 rspu_master.created_by 段保持一致（幂等写法）
-- 口径：存 sys_user.user_id（FK 派，同 excel_import_batch.created_by）；历史未知行留 NULL，由启动期
--       RspuCreatedByBackfillMigration 从 audit_log 尽力回填（数据修正不走 Flyway）
-- ============================================================

ALTER TABLE rspu_master ADD COLUMN IF NOT EXISTS created_by VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_rspu_created_by ON rspu_master(created_by);

ALTER TABLE rspu_master
    DROP CONSTRAINT IF EXISTS fk_rspu_master_created_by;
ALTER TABLE rspu_master
    ADD CONSTRAINT fk_rspu_master_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(user_id);
