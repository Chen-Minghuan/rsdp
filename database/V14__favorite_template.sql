-- RSDP V14 数据库迁移：收藏夹两级模型 + 模板标签实体（RSDP × rooom 全量复现阶段 6）
-- 基于 docs/08-roadmap/RSDP-rooom全量复现与AI整合方案.md 阶段 6
-- 收藏夹：group_name 文本升级为 favorite_folder 文件夹实体（user_favorite.folder_id）；
-- 模板：scheme.template_tags 自由文本标签迁移为 template_tag 受控字典实体。
-- 说明：group_name / template_tags 原列保留不删（DDL 只增不删，向后兼容）。
-- 幂等：全部使用 IF NOT EXISTS / NOT EXISTS / ON CONFLICT 守卫，可重复执行。

-- ============================================================
-- 一、收藏夹文件夹表
-- ============================================================
CREATE TABLE IF NOT EXISTS favorite_folder (
    folder_id   VARCHAR(64) PRIMARY KEY,             -- FAVD-<完整 UUID>
    user_id     VARCHAR(64) NOT NULL REFERENCES sys_user(user_id),
    folder_name VARCHAR(64) NOT NULL,
    sort_order  INT NOT NULL DEFAULT 0,
    deleted_at  TIMESTAMP,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_favorite_folder_user ON favorite_folder(user_id) WHERE deleted_at IS NULL;

COMMENT ON TABLE favorite_folder IS '收藏夹文件夹（两级模型：文件夹 + 收藏条目）';

-- ============================================================
-- 二、user_favorite 扩展文件夹归属
-- ============================================================
ALTER TABLE user_favorite
    ADD COLUMN IF NOT EXISTS folder_id VARCHAR(64);

ALTER TABLE user_favorite DROP CONSTRAINT IF EXISTS fk_user_favorite_folder;
ALTER TABLE user_favorite
    ADD CONSTRAINT fk_user_favorite_folder FOREIGN KEY (folder_id) REFERENCES favorite_folder(folder_id);

CREATE INDEX IF NOT EXISTS idx_user_favorite_folder ON user_favorite(folder_id);

COMMENT ON COLUMN user_favorite.folder_id IS '所属收藏夹文件夹（favorite_folder.folder_id），NULL=未归档';

-- ============================================================
-- 三、模板标签表（受控字典）
-- ============================================================
CREATE TABLE IF NOT EXISTS template_tag (
    tag_id     VARCHAR(64) PRIMARY KEY,              -- TAG-<完整 UUID>
    tag_name   VARCHAR(64) NOT NULL UNIQUE,
    sort_order INT NOT NULL DEFAULT 0,
    enabled    BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE template_tag IS '方案模板标签（受控字典；scheme.template_tags 存名称 JSON，以名称为业务键）';

-- ============================================================
-- 四、历史数据迁移（幂等）
-- ============================================================
-- 4.1 收藏分组文本 → 文件夹实体（按用户+名称合并，已存在同名文件夹则跳过）
INSERT INTO favorite_folder (folder_id, user_id, folder_name)
SELECT 'FAVD-' || gen_random_uuid()::text, d.user_id, d.group_name
FROM (SELECT DISTINCT user_id, group_name FROM user_favorite
      WHERE group_name IS NOT NULL AND btrim(group_name) <> '') d
WHERE NOT EXISTS (SELECT 1 FROM favorite_folder f
                  WHERE f.user_id = d.user_id AND f.folder_name = d.group_name);

-- 4.2 回填收藏条目的文件夹归属
UPDATE user_favorite uf SET folder_id = f.folder_id
FROM favorite_folder f
WHERE uf.folder_id IS NULL
  AND uf.user_id = f.user_id
  AND uf.group_name = f.folder_name;

-- 4.3 存量模板标签（scheme.template_tags JSON 数组）→ 标签实体
INSERT INTO template_tag (tag_id, tag_name)
SELECT 'TAG-' || gen_random_uuid()::text, t.tag_name
FROM (
    SELECT DISTINCT jsonb_array_elements_text(s.template_tags::jsonb) AS tag_name
    FROM scheme s
    WHERE s.template_tags IS NOT NULL AND s.template_tags ~ '^\s*\['
) t
WHERE btrim(t.tag_name) <> ''
ON CONFLICT (tag_name) DO NOTHING;
