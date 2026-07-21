-- 收藏夹权限迁移（2026-07-20）
-- 为已部署的数据库补录 favorite:read / favorite:write 权限，并授予所有角色

INSERT INTO sys_permission (permission_code, permission_name) VALUES
('favorite:read', '查看我的收藏'),
('favorite:write', '管理我的收藏')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE p.permission_code IN ('favorite:read', 'favorite:write')
ON CONFLICT DO NOTHING;
