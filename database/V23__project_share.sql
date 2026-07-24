-- RSDP V18 数据库迁移：项目画布分享（RSDP × rooom 全量复现阶段 9）
-- 基于 docs/08-roadmap/RSDP-rooom全量复现与AI整合方案.md 阶段 9
-- project 加分享开关与过期时间，公开只读视图 /s/{projectId} 据此校验。
-- 幂等：IF NOT EXISTS 守卫，可重复执行。

ALTER TABLE project
    ADD COLUMN IF NOT EXISTS share_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS share_expire_at TIMESTAMP;

COMMENT ON COLUMN project.share_enabled IS '画布分享开关（开启后公开只读视图可访问）';
COMMENT ON COLUMN project.share_expire_at IS '分享过期时间（NULL=永久有效）';
