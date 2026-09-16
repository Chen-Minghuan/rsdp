-- P2 设计师差异化能力：留资线索设计师归属 + 产品集官网发布开关
ALTER TABLE platform_lead ADD COLUMN IF NOT EXISTS designer_id VARCHAR(64);
COMMENT ON COLUMN platform_lead.designer_id IS '归属设计师（sys_user.user_id，官网设计师分享链接带入，可空；弱关联不加外键）';
CREATE INDEX IF NOT EXISTS idx_platform_lead_designer ON platform_lead(designer_id, created_at);

ALTER TABLE product_collection ADD COLUMN IF NOT EXISTS is_published BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN product_collection.is_published IS '是否发布到官网（/api/v1/public/collections 仅返回已发布集合；仅平台运营可发布）';
CREATE INDEX IF NOT EXISTS idx_product_collection_published ON product_collection(is_published, sort_order);
