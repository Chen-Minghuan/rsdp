-- RSDP V6 数据库迁移：企业团队轻量版（RSDP × rooom 整合阶段 4）
-- 基于 docs/08-roadmap/RSDP-rooom整合实现方案.md 4.4.2 节
-- 轻量版仅为 sys_user 增加企业/分组两个文本字段，不建企业实体

-- ============================================================
-- 一、扩展 sys_user：企业名称、团队分组
-- ============================================================

ALTER TABLE sys_user
    ADD COLUMN IF NOT EXISTS company_name VARCHAR(128),
    ADD COLUMN IF NOT EXISTS group_name VARCHAR(64);

COMMENT ON COLUMN sys_user.company_name IS '用户所属企业名称（轻量文本字段，项目创建时 companyName 留空默认取此值）';
COMMENT ON COLUMN sys_user.group_name IS '用户所属团队分组名称（轻量文本字段）';
