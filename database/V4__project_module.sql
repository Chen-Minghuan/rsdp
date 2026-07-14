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
