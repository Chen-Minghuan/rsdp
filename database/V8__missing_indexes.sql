-- ============================================================
-- V8：补充高频查询缺失索引
-- ============================================================

-- 按 RSKU 查询图片（RSKU 详情页、报价单）
CREATE INDEX IF NOT EXISTS idx_image_rsku ON image_assets(rsku_id);

-- 订单按项目/方案查询
CREATE INDEX IF NOT EXISTS idx_design_order_project ON design_order(project_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_design_order_scheme ON design_order(scheme_id) WHERE deleted_at IS NULL;

-- RSPU 外部编码查重（Excel/ERP 导入）
CREATE INDEX IF NOT EXISTS idx_rspu_external_code ON rspu_master(external_code) WHERE deleted_at IS NULL;

-- RSPU 风格关联反查 RSPU（风格筛选）
CREATE INDEX IF NOT EXISTS idx_rspu_style_rspu ON rspu_style(rspu_id, style_code);
