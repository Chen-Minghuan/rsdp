-- ═══════════════════════════════════════════════════════════
-- V3：对齐 BIGSERIAL / SERIAL 自增序列
-- ═══════════════════════════════════════════════════════════
-- 背景：以下 8 张表的主键实体由 MyBatis-Plus 默认雪花 ID 改为 IdType.AUTO，
--       即改由数据库自增序列生成主键。若历史数据是雪花 ID（19 位）写入，
--       自增序列可能仍停在低位，需把序列推进到当前 MAX(id)，避免后续
--       自增值与已有主键冲突。
--
-- 执行时机：实体切换到 IdType.AUTO 之后、首次启动新业务写入之前执行一次。
-- 幂等性：可重复执行；空表对齐到 1（下次 nextval 返回 1），
--         非空表对齐到 MAX(id)（下次 nextval 返回 MAX(id)+1）。
-- ═══════════════════════════════════════════════════════════

SELECT setval('sys_role_role_id_seq',
              COALESCE((SELECT MAX(role_id) FROM sys_role), 1),
              (SELECT MAX(role_id) IS NOT NULL FROM sys_role));

SELECT setval('sys_permission_permission_id_seq',
              COALESCE((SELECT MAX(permission_id) FROM sys_permission), 1),
              (SELECT MAX(permission_id) IS NOT NULL FROM sys_permission));

SELECT setval('sys_role_permission_id_seq',
              COALESCE((SELECT MAX(id) FROM sys_role_permission), 1),
              (SELECT MAX(id) IS NOT NULL FROM sys_role_permission));

SELECT setval('sys_user_role_id_seq',
              COALESCE((SELECT MAX(id) FROM sys_user_role), 1),
              (SELECT MAX(id) IS NOT NULL FROM sys_user_role));

SELECT setval('sys_user_factory_id_seq',
              COALESCE((SELECT MAX(id) FROM sys_user_factory), 1),
              (SELECT MAX(id) IS NOT NULL FROM sys_user_factory));

SELECT setval('rspu_factory_mapping_mapping_id_seq',
              COALESCE((SELECT MAX(mapping_id) FROM rspu_factory_mapping), 1),
              (SELECT MAX(mapping_id) IS NOT NULL FROM rspu_factory_mapping));

SELECT setval('factory_lead_time_rule_rule_id_seq',
              COALESCE((SELECT MAX(rule_id) FROM factory_lead_time_rule), 1),
              (SELECT MAX(rule_id) IS NOT NULL FROM factory_lead_time_rule));

SELECT setval('excel_import_row_row_id_seq',
              COALESCE((SELECT MAX(row_id) FROM excel_import_row), 1),
              (SELECT MAX(row_id) IS NOT NULL FROM excel_import_row));
