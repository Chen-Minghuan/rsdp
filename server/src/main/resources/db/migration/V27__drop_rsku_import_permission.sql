-- V27：移除 rsku:import 权限（2026-11-03，RSKU 报价导入模块整体下线）
--
-- 背景：RSKU 报价批量导入（/api/v1/rsku/import + 导入模板下载）已并入 Excel AI 导入
-- （更新模式重导报价册即覆盖报价 upsert 场景），前后端实现与安全配置同步删除。
-- 本迁移清理权限种子与角色授权（FACTORY_ADMIN 曾持有）；幂等可重复执行。
--
-- 同步约定：database/schema/zz_seed.sql 与 ops/reset_db.sql 已同步移除该权限及授权。

DELETE FROM sys_role_permission
WHERE permission_id IN (SELECT permission_id FROM sys_permission WHERE permission_code = 'rsku:import');

DELETE FROM sys_permission WHERE permission_code = 'rsku:import';
