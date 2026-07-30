-- V23：字典管理权限（dict:update）
-- 字典管理中心后端接口（编辑/启停用）的权限点，授予 ADMIN 与 EDITOR（与 dict:create 一致）

INSERT INTO sys_permission (permission_code, permission_name) VALUES
('dict:update', '编辑字典项')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code IN ('ADMIN', 'EDITOR')
  AND p.permission_code = 'dict:update'
ON CONFLICT DO NOTHING;
