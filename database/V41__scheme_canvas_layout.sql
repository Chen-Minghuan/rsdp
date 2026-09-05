-- V41：方案画布布局（搭配画布）
-- 背景：方案支持前端白底画布，用户把方案产品拖到画布上摆位，布局需持久化到方案上。
-- scheme.canvas_layout：画布布局 JSONB，结构为 { "<schemeItemId>": { "x": 0.12, "y": 0.30, "scale": 1.0, "z": 1 } }，
-- key 为 scheme_item_id 字符串，x/y 为 0~1 相对坐标，scale 缩放 0.3~3，z 为层级。
-- 存量数据均为 NULL（未使用画布），行为与现状完全一致，零迁移成本。

ALTER TABLE scheme ADD COLUMN IF NOT EXISTS canvas_layout JSONB;
COMMENT ON COLUMN scheme.canvas_layout IS '画布布局（搭配画布，V41）：JSON 对象，key=scheme_item_id 字符串，value={x,y,scale,z}（x/y 为 0~1 相对坐标，scale 缩放 0.3~3，z 层级）；可空=未保存画布布局';
