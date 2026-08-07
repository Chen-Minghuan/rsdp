-- V31：图片内容哈希（image_assets.content_hash）
-- 用途：录入时按 SHA-256 精确查重同一图片文件，防止重复导入产生重复产品。
-- 说明：存量行 content_hash 为 NULL（不参与匹配），新导入图片写入哈希。
-- 幂等：ADD COLUMN IF NOT EXISTS + CREATE INDEX IF NOT EXISTS，可重复执行。

ALTER TABLE image_assets ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);

COMMENT ON COLUMN image_assets.content_hash IS '图片内容 SHA-256 哈希（录入查重用）';

CREATE INDEX IF NOT EXISTS idx_image_content_hash ON image_assets(content_hash);
