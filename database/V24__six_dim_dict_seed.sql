-- V24：六维标签字典体系（阶段 P0 数据基础）
-- 依据：docs/08-roadmap/六维标签体系完善方案.md v2.1
--
-- 步骤 0（前置）：补种 category 品类字典 DT/BD/LT。
--   AI 品类枚举来自 VisionService.buildCategoryEnumText()（读取 category 字典），
--   不补这三条，AI 永远不会识别出餐桌/床/灯具，三个品类的六维定义永不生效。
-- 步骤 1：dict_code 编码规则 = {品类码}-{中文名}（如 SF-宽厚扶手、TB-直边、DT-直边）。
--   category_dict 主键为 (dict_type, dict_code)，不含 parent_code，
--   多品类同名枚举值（TB/DT 都有"直边"）靠前缀码区分共存；
--   dict_name = 中文名，展示层统一走 resolveDictName 显示名。
--   存量 6 条 FS 六维种子在此规范化为前缀码；与附录 A 清单有对应项的一并改为规范名。
--   （六维字典无 FK 引用、存量 six_dim_tags 实际值为自由文本不引用字典码，改码无下游破坏）
-- 步骤 2/3（six_dim_A~F 全品类枚举种子，E 维度不建独立枚举）随后续步骤补充进本文件。
--
-- 幂等：全部 UPDATE 按旧码定位、INSERT ON CONFLICT DO NOTHING，可重复执行。

-- ==================== 步骤 0：品类字典补种 ====================
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('category', 'DT', '餐桌', 7),
('category', 'BD', '床', 8),
('category', 'LT', '灯具', 9)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- ==================== 步骤 1：存量 FS 六维种子编码规范化 ====================
-- A字架形 → 外露骨架形（附录 A v2.0 修正记录 #7：A字架形并入外露骨架形，A字架形收为其 alias）
UPDATE category_dict SET dict_code = 'FS-外露骨架形', dict_name = '外露骨架形'
WHERE dict_type = 'six_dim_A' AND dict_code = 'A字架形';
-- 蛋形 → 蛋形/球形（附录 A A.1：蛋椅/蛋壳椅/球椅/太空舱椅合并为一项）
UPDATE category_dict SET dict_code = 'FS-蛋形/球形', dict_name = '蛋形/球形'
WHERE dict_type = 'six_dim_A' AND dict_code = '蛋形';
UPDATE category_dict SET dict_code = 'FS-方盒形', dict_name = '方盒形'
WHERE dict_type = 'six_dim_A' AND dict_code = '方盒形';
-- 编织镂空 → 编织镂空靠背（对齐附录 A A.1 B 维度规范名）
UPDATE category_dict SET dict_code = 'FS-编织镂空靠背', dict_name = '编织镂空靠背'
WHERE dict_type = 'six_dim_B' AND dict_code = '编织镂空';
-- 高背包裹：v2.0 已拆分为「高靠背」+「翼形包裹靠背」，此处仅规范化编码、名称保留为存量枚举
UPDATE category_dict SET dict_code = 'FS-高背包裹', dict_name = '高背包裹'
WHERE dict_type = 'six_dim_B' AND dict_code = '高背包裹';
UPDATE category_dict SET dict_code = 'FS-无靠背', dict_name = '无靠背'
WHERE dict_type = 'six_dim_B' AND dict_code = '无靠背';
