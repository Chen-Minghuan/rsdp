-- V40：方案项级空间标签（方案 A：空间设计画布）
-- 背景：方案"按空间展示"的分区标签此前只能由产品 rspu_scene 首场景实时推导，
-- 用户无法在方案层面做空间设计（同一产品在不同方案想放不同空间做不到）。
-- scheme_item.space_tag：本方案内的空间覆盖标签（存场景字典码，可空 = 跟随产品场景推导）；
-- design_order_item.space_tag：订单空间快照（由方案生成订单时复制冻结，客户邀请页按空间分组展示）。
-- 存量数据均为 NULL，行为与现状完全一致，零迁移成本。

ALTER TABLE scheme_item ADD COLUMN IF NOT EXISTS space_tag VARCHAR(32);
COMMENT ON COLUMN scheme_item.space_tag IS '空间覆盖标签（场景字典码，可空：空=跟随产品 rspu_scene 首场景推导；有值=本方案内人工指定的空间，仅影响本方案，不修改产品属性）';

ALTER TABLE design_order_item ADD COLUMN IF NOT EXISTS space_tag VARCHAR(32);
COMMENT ON COLUMN design_order_item.space_tag IS '空间快照（由方案生成订单时从 scheme_item.space_tag 复制冻结；为空=未指定空间/存量订单，邀请页平铺展示兜底）';
