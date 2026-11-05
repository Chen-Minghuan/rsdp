-- V25：RSPU/RSKU 业务编码规则改造（2026-10-14，录入链路改造 P1）
--
-- 规则变化（仅影响新发放编码；存量编码不变，uk_*_code_alive 唯一索引不动）：
--   RSPU：{品类}-{风格}-{流水4位}，例 FS-MC-0001 —— 剔除尺寸段（尺寸是变体级属性），
--         风格缺失/未归一降级为保留段 TBD，任何款式都能发号；
--   RSKU：{rspu_code}-{工厂}-{流水3位}，例 FS-MC-0001-A004-001 —— 剔除材质段
--        （材质是报价属性而非供应单元身份），计数器维度随之收缩；
--   编码发放后永不变更：同款合并不再置空/重发已发放的 rsku_code。
--
-- 本脚本：
--   1. 字典新增 style=TBD（待识别）保留码；
--   2. rsku_code_counter 主键从 (rspu_code, factory_code, material_code) 收缩为
--      (rspu_code, factory_code)，合并同键历史行（保留最大流水，防回退重号）。
--
-- 同步约定：database/schema/02_product.sql、zz_seed.sql 与 ops/reset_db.sql 已同步。

-- 1. 风格保留码 TBD（待识别）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('style', 'TBD', '待识别', 99)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 2. rsku_code_counter 维度收缩：先合并同 (rspu_code, factory_code) 的历史行
--    （保留最大 sequence_value，材质维度行数多于一行时其余删除），再删列重建主键。
DELETE FROM rsku_code_counter a
WHERE EXISTS (
    SELECT 1 FROM rsku_code_counter b
    WHERE b.rspu_code = a.rspu_code
      AND b.factory_code = a.factory_code
      AND (b.sequence_value > a.sequence_value
           OR (b.sequence_value = a.sequence_value AND b.material_code > a.material_code))
);

ALTER TABLE rsku_code_counter DROP CONSTRAINT IF EXISTS rsku_code_counter_pkey;
ALTER TABLE rsku_code_counter DROP COLUMN IF EXISTS material_code;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'rsku_code_counter'::regclass AND contype = 'p'
    ) THEN
        ALTER TABLE rsku_code_counter ADD PRIMARY KEY (rspu_code, factory_code);
    END IF;
END $$;
