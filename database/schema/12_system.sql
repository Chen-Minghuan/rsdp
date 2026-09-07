-- ============================================================
-- RSDP 基线 DDL · 12 系统域（12_system.sql）
-- 包含表：audit_log（另含跨域自增序列对齐 setval 段，须在所有域表创建后执行）
-- 执行顺序：schema/ 目录按文件名 01 → 12 → 99 依次执行（编号即执行顺序，基线由原 V1__init_db.sql 按域拆分而来）
-- 同步约定：新增/修改本域表结构时须同步 ops/reset_db.sql，约定详见 database/README.md
-- ============================================================
-- 审计日志表
CREATE TABLE IF NOT EXISTS audit_log (
    id BIGSERIAL PRIMARY KEY,
    table_name VARCHAR(64) NOT NULL,
    record_id VARCHAR(64) NOT NULL,
    action VARCHAR(16) NOT NULL,
    old_value JSONB,
    new_value JSONB,
    operator VARCHAR(64),
    ip_address VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- 审计日志索引
CREATE INDEX IF NOT EXISTS idx_audit_record ON audit_log(table_name, record_id, created_at);

-- ============================================================
-- 自增序列对齐（V3 并入，原随 V2 迁移下发）
-- 背景：以下 8 张表的主键实体使用 IdType.AUTO，由数据库自增序列生成主键。
--       本段保证初始化后序列与当前 MAX(id) 一致，避免后续自增值与已有主键冲突。
--       幂等：可重复执行；空表对齐到 1，非空表对齐到 MAX(id)。
-- ============================================================
SELECT setval('sys_role_role_id_seq',                 COALESCE((SELECT MAX(role_id) FROM sys_role), 1),                 (SELECT MAX(role_id) IS NOT NULL FROM sys_role));
SELECT setval('sys_permission_permission_id_seq',     COALESCE((SELECT MAX(permission_id) FROM sys_permission), 1),     (SELECT MAX(permission_id) IS NOT NULL FROM sys_permission));
SELECT setval('sys_role_permission_id_seq',           COALESCE((SELECT MAX(id) FROM sys_role_permission), 1),           (SELECT MAX(id) IS NOT NULL FROM sys_role_permission));
SELECT setval('sys_user_role_id_seq',                 COALESCE((SELECT MAX(id) FROM sys_user_role), 1),                 (SELECT MAX(id) IS NOT NULL FROM sys_user_role));
SELECT setval('sys_user_factory_id_seq',              COALESCE((SELECT MAX(id) FROM sys_user_factory), 1),              (SELECT MAX(id) IS NOT NULL FROM sys_user_factory));
SELECT setval('rspu_factory_mapping_mapping_id_seq',  COALESCE((SELECT MAX(mapping_id) FROM rspu_factory_mapping), 1),  (SELECT MAX(mapping_id) IS NOT NULL FROM rspu_factory_mapping));
SELECT setval('factory_lead_time_rule_rule_id_seq',   COALESCE((SELECT MAX(rule_id) FROM factory_lead_time_rule), 1),   (SELECT MAX(rule_id) IS NOT NULL FROM factory_lead_time_rule));
SELECT setval('excel_import_row_row_id_seq',          COALESCE((SELECT MAX(row_id) FROM excel_import_row), 1),          (SELECT MAX(row_id) IS NOT NULL FROM excel_import_row));
SELECT setval('dict_alias_id_seq',                    COALESCE((SELECT MAX(id) FROM dict_alias), 1),                    (SELECT MAX(id) IS NOT NULL FROM dict_alias));
SELECT setval('dict_unresolved_value_id_seq',         COALESCE((SELECT MAX(id) FROM dict_unresolved_value), 1),         (SELECT MAX(id) IS NOT NULL FROM dict_unresolved_value));

