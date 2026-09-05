-- V42：方案级分享（独立公开链接 + 有效期限制）
-- 背景：方案（scheme）支持独立分享，免登录公开只读访问 /api/v1/public/schemes/{schemeId}；
-- 项目分享页内的方案也可经 /api/v1/public/projects/{projectId}/schemes/{schemeId} 公开访问。
-- scheme.share_enabled：方案分享开关（仿 project.share_enabled 语义）。
-- scheme.share_expire_at：分享过期时间（NULL=永久有效；关闭分享时清空）。
-- 存量数据 share_enabled=false，行为与现状完全一致，零迁移成本。

ALTER TABLE scheme ADD COLUMN IF NOT EXISTS share_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE scheme ADD COLUMN IF NOT EXISTS share_expire_at TIMESTAMP;

COMMENT ON COLUMN scheme.share_enabled IS '方案分享开关（V42）：开启后公开只读视图 /api/v1/public/schemes/{schemeId} 可访问';
COMMENT ON COLUMN scheme.share_expire_at IS '方案分享过期时间（V42）：NULL=永久有效；关闭分享时清空';
