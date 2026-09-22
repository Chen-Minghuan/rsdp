-- V13：floor_plan_room 扩列支持 CAD 几何落库（CAD 户型导入 P3，docs/08-roadmap/CAD户型导入架构设计.md §7）
-- 同步约定：database/schema/06_floor_plan.sql 与 database/ops/reset_db.sql 已同步修改
ALTER TABLE floor_plan_room
    ADD COLUMN IF NOT EXISTS label VARCHAR(128),          -- 空间标签原文（如"主卧"；未命名空间落"未命名空间 N"）
    ADD COLUMN IF NOT EXISTS polygon JSONB,               -- 房间外环顶点，毫米坐标 [[x,y],...]（CAD 通道）
    ADD COLUMN IF NOT EXISTS centroid JSONB,              -- 质心 {"x":..,"y":..}（毫米，CAD 通道）
    ADD COLUMN IF NOT EXISTS geometry_source VARCHAR(32); -- 几何来源：ai_vision（视觉识别）/ cad_geometry（CAD 解析）

COMMENT ON COLUMN floor_plan_room.label IS '空间标签原文（CAD 通道落库；未命名空间为"未命名空间 N"）';
COMMENT ON COLUMN floor_plan_room.polygon IS '房间外环顶点，毫米坐标 [[x,y],...]，CAD 解析通道落库';
COMMENT ON COLUMN floor_plan_room.centroid IS '房间质心 {"x":..,"y":..}（毫米），CAD 解析通道落库';
COMMENT ON COLUMN floor_plan_room.geometry_source IS '几何来源：ai_vision / cad_geometry';
