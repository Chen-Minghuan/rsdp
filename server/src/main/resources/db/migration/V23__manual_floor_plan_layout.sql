-- 无图纸手画布局 V1：允许无底图分析，保存布局草稿，并为人工几何保留组成块与主布置区。
ALTER TABLE floor_plan_analysis
    ALTER COLUMN image_id DROP NOT NULL;

ALTER TABLE floor_plan_analysis
    ADD COLUMN IF NOT EXISTS input_mode VARCHAR(16) NOT NULL DEFAULT 'image',
    ADD COLUMN IF NOT EXISTS layout_json JSONB,
    ADD COLUMN IF NOT EXISTS layout_revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE floor_plan_room
    ADD COLUMN IF NOT EXISTS shape_parts JSONB,
    ADD COLUMN IF NOT EXISTS placement_zone JSONB;

COMMENT ON COLUMN floor_plan_analysis.input_mode IS '户型输入模式：image/pdf/cad/manual';
COMMENT ON COLUMN floor_plan_analysis.layout_json IS 'manual 手画布局草稿：画布、空间组成块和主布置区';
COMMENT ON COLUMN floor_plan_analysis.layout_revision IS '手画布局乐观锁版本，每次成功保存递增';
COMMENT ON COLUMN floor_plan_room.shape_parts IS 'manual_geometry 可编辑矩形组成块；polygon 为其派生并集';
COMMENT ON COLUMN floor_plan_room.placement_zone IS '主布置矩形，必须完全包含在空间 polygon 内';
COMMENT ON COLUMN floor_plan_room.geometry_source IS '几何来源：ai_vision / cad_geometry / manual_geometry';
