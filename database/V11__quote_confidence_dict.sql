-- =============================================================
-- V11：补充 quote_confidence 字典种子
-- 背景：RSKU 导入的中文置信度映射（高/中/低 → high/mid/low）依赖该字典，
--       此前字典无种子数据导致映射不生效、任意值均可入库。
-- 执行：psql -d rsdp -f V11__quote_confidence_dict.sql
-- =============================================================

BEGIN;

INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('quote_confidence', 'high', '高', 1),
('quote_confidence', 'mid', '中', 2),
('quote_confidence', 'low', '低', 3)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

COMMIT;
