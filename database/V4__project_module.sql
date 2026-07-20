-- ============================================================
-- V4 迁移：设计项目模块 + 收藏夹
-- 对应文档：docs/08-roadmap/RSDP-rooom整合实现方案.md
-- 本脚本幂等，可重复执行。
-- ============================================================

-- 收藏夹：用户级产品收藏，支持分组
CREATE TABLE IF NOT EXISTS user_favorite (
    favorite_id VARCHAR(40) PRIMARY KEY,
    user_id     VARCHAR(64) NOT NULL REFERENCES sys_user (user_id),
    rspu_id     VARCHAR(40) NOT NULL REFERENCES rspu_master (rspu_id),
    group_name  VARCHAR(64),
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, rspu_id)
);

CREATE INDEX IF NOT EXISTS idx_favorite_user ON user_favorite (user_id, created_at DESC);


-- ============================================================
-- 阶段 2：设计项目 Project + 方案模板化
-- ============================================================

-- 设计项目表（owner_id 与 sys_user.user_id 类型一致，使用 VARCHAR(64)）
CREATE TABLE IF NOT EXISTS project (
    project_id    VARCHAR(40) PRIMARY KEY,
    project_name  VARCHAR(128) NOT NULL,
    project_type  VARCHAR(32),
    company_name  VARCHAR(128),
    owner_id      VARCHAR(64) NOT NULL REFERENCES sys_user (user_id),
    status        VARCHAR(20) NOT NULL DEFAULT 'active',
    remark        VARCHAR(512),
    deleted_at    TIMESTAMP,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_project_owner ON project (owner_id) WHERE deleted_at IS NULL;

-- 方案表扩展：项目归属 + 模板化（template_tags 存 JSON 字符串，与项目 TEXT 约定一致）
ALTER TABLE scheme ADD COLUMN IF NOT EXISTS project_id VARCHAR(40) REFERENCES project (project_id);
ALTER TABLE scheme ADD COLUMN IF NOT EXISTS is_template BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE scheme ADD COLUMN IF NOT EXISTS template_tags TEXT;
CREATE INDEX IF NOT EXISTS idx_scheme_project ON scheme (project_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_scheme_template ON scheme (is_template) WHERE is_template = true AND deleted_at IS NULL;

-- 项目类型字典
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('project_type', 'whole_house', '全屋', 1),
('project_type', 'space', '单空间', 2),
('project_type', 'custom', '定制', 3)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 项目权限（ADMIN 全量自动覆盖，EDITOR 排除式自动覆盖）
INSERT INTO sys_permission (permission_code, permission_name) VALUES
('project:read', '查看设计项目'),
('project:create', '创建设计项目'),
('project:update', '编辑设计项目'),
('project:delete', '删除设计项目')
ON CONFLICT (permission_code) DO NOTHING;

-- DESIGNER：项目全量权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'DESIGNER'
  AND p.permission_code LIKE 'project:%'
ON CONFLICT DO NOTHING;

-- ADMIN / EDITOR：项目全量权限（通用映射先于本权限插入执行，需显式补插）
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code IN ('ADMIN', 'EDITOR')
  AND p.permission_code LIKE 'project:%'
ON CONFLICT DO NOTHING;

-- VIEWER / USER：项目只读
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code IN ('VIEWER', 'USER')
  AND p.permission_code = 'project:read'
ON CONFLICT DO NOTHING;
