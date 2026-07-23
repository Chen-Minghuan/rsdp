-- RSDP V16 数据库迁移：订单增强（RSDP × rooom 全量复现阶段 8）
-- 基于 docs/08-roadmap/RSDP-rooom全量复现与AI整合方案.md 阶段 8
-- design_order_item 加行级改价 adjust_price（AES 加密 TEXT，与 original/final_price 同模式）；
-- design_order 加合同文件 contract_file_id（指向 image_assets，不加外键，文件可独立清理）。
-- 幂等：IF NOT EXISTS 守卫，可重复执行。

-- 行级改价：非空时行到手单价 = adjust_price（优先于 原价快照 × 折扣率），仅 PENDING 可编辑
ALTER TABLE design_order_item
    ADD COLUMN IF NOT EXISTS adjust_price TEXT;

COMMENT ON COLUMN design_order_item.adjust_price IS '行级改价（AES 加密到手单价；非空时优先于原价快照×折扣率，仅 PENDING 可编辑）';

-- 合同文件回填：上传的合同 docx/pdf 关联订单
ALTER TABLE design_order
    ADD COLUMN IF NOT EXISTS contract_file_id VARCHAR(64);

COMMENT ON COLUMN design_order.contract_file_id IS '合同文件（image_assets.image_id，image_type=contract；不加外键，文件可独立清理）';
