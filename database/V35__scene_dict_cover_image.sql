-- V35：官网空间探索场景封面图可手配（category_dict.image_id）
-- 用途：运营在管理端为 dict_type='scene' 的字典项手配封面图（存 image_assets.image_id）；
--       公开接口 /api/v1/public/scenes 手配优先，无手配时回退"该场景下最新在售产品主图"。
-- 说明：参照 platform_banner.image_id 既有写法——仅 VARCHAR(64) 存图片 ID，不加外键与索引；
--       存量行 image_id 为 NULL（走兜底逻辑）。
-- 幂等：ADD COLUMN IF NOT EXISTS，可重复执行。

ALTER TABLE category_dict ADD COLUMN IF NOT EXISTS image_id VARCHAR(64);

COMMENT ON COLUMN category_dict.image_id IS '场景封面图（image_assets.image_id），仅 dict_type=scene 使用；为空时走产品主图兜底';
