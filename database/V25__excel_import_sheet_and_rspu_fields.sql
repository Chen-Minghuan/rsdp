-- ============================================================================
-- V18 迁移：Excel 录入系统性方案（多 Sheet 导入 + RSPU 描述/零售参考价）
-- ============================================================================
-- 背景：
--   1. 多 Sheet 工厂报价单（如沃高茶家具 10 个 sheet）需要逐一导入：
--      excel_import_batch 需记录本批次解析的工作表索引，confirm 导入按该索引
--      重新解析对应 sheet（修复硬编码 sheet 0 的问题）。
--   2. 真实报价单含长文本配置说明（材质解析/功能配置/配置说明）与销售价列：
--      rspu_master 需新增长文本描述原文列与不加密的零售参考价列
--      （factory_price 仍为 AES 加密的工厂价，二者角色不同）。
-- 幂等：全部使用 ADD COLUMN IF NOT EXISTS，可重复执行安全。
-- 同步约定：V1__init_db.sql 与 reset_db.sql 已并入相同列定义（全新初始化直接创建）。
-- ============================================================================

-- 1. excel_import_batch：批次解析的工作表索引（0-based，默认 0 兼容历史批次）
ALTER TABLE excel_import_batch
    ADD COLUMN IF NOT EXISTS sheet_index INT NOT NULL DEFAULT 0;

-- 2. rspu_master：长文本描述原文（材质解析/功能配置/配置说明等列原文）
ALTER TABLE rspu_master
    ADD COLUMN IF NOT EXISTS description TEXT;

-- 3. rspu_master：零售参考价（销售价/含税价/零售价/市场价，不加密）
ALTER TABLE rspu_master
    ADD COLUMN IF NOT EXISTS retail_price NUMERIC(14,2);
