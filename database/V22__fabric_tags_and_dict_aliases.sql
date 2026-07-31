-- V22：面料标签字段 + 字典别名机制
-- 1) rspu_master 新增 fabric_tags（软体商品的接触面面料，与结构材质 material_tags 区分）
-- 2) category_dict 新增 aliases（同义词别名，解决 AI 识别名称与字典标准名差字导致匹配失败被丢弃的问题）
-- 3) 新增面料字典 dict_type='fabric' 种子数据
-- 4) 回填常用材质/面料别名

ALTER TABLE rspu_master ADD COLUMN IF NOT EXISTS fabric_tags JSONB DEFAULT '[]';
COMMENT ON COLUMN rspu_master.fabric_tags IS '面料标签字典码 JSON 数组，如 ["LI","KJ"]（沙发/床垫/椅子等软体商品接触面面料）';

ALTER TABLE category_dict ADD COLUMN IF NOT EXISTS aliases TEXT;
COMMENT ON COLUMN category_dict.aliases IS '同义词别名 JSON 数组，字典匹配时精确名未命中则按别名匹配，如 ["真皮","头层牛皮"]';

-- 面料类型字典（与 material_grade 面料等级区分；软体类商品专用）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('fabric', 'LI', '亚麻/棉麻', 1),
('fabric', 'XN', '雪尼尔', 2),
('fabric', 'KJ', '科技布', 3),
('fabric', 'VE', '天鹅绒/绒布', 4),
('fabric', 'SF', '羊羔绒/泰迪绒', 5),
('fabric', 'DX', '灯芯绒', 6),
('fabric', 'ZP', '真皮', 7),
('fabric', 'NP', '纳帕皮', 8),
('fabric', 'MS', '磨砂皮', 9),
('fabric', 'CX', '超纤皮', 10),
('fabric', 'PU', 'PU/PVC革', 11),
('fabric', 'WB', '网布', 12)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 常用材质别名（AI 识别叫法 → 字典标准项）
UPDATE category_dict SET aliases = '["橡木","胡桃木","松木","原木","木头","木质"]' WHERE dict_type='material' AND dict_code='WO';
UPDATE category_dict SET aliases = '["真皮","牛皮","头层牛皮","黄牛皮","皮质"]' WHERE dict_type='material' AND dict_code='LE';
UPDATE category_dict SET aliases = '["棉麻","麻布","亚麻布","棉麻布"]' WHERE dict_type='material' AND dict_code='LI';
UPDATE category_dict SET aliases = '["绒布","丝绒","天鹅绒布"]' WHERE dict_type='material' AND dict_code='VE';
UPDATE category_dict SET aliases = '["羊羔毛","泰迪绒","圈圈绒","羊羔绒"]' WHERE dict_type='material' AND dict_code='SF';
UPDATE category_dict SET aliases = '["不锈钢","黄铜","铁艺","铝合金","金属框架"]' WHERE dict_type='material' AND dict_code='MT';
UPDATE category_dict SET aliases = '["大理石","岩板","洞石","石材"]' WHERE dict_type='material' AND dict_code='ST';
UPDATE category_dict SET aliases = '["仿藤","PE藤","塑料藤"]' WHERE dict_type='material' AND dict_code='PE';
UPDATE category_dict SET aliases = '["藤编","竹编","草编","真藤"]' WHERE dict_type='material' AND dict_code='TN';
UPDATE category_dict SET aliases = '["亚克力","有机玻璃"]' WHERE dict_type='material' AND dict_code='PL';

-- 面料别名
UPDATE category_dict SET aliases = '["棉麻","麻布","亚麻布","棉麻布"]' WHERE dict_type='fabric' AND dict_code='LI';
UPDATE category_dict SET aliases = '["雪尼尔绒","雪尼尔布"]' WHERE dict_type='fabric' AND dict_code='XN';
UPDATE category_dict SET aliases = '["科技绒","科技皮革","三防布"]' WHERE dict_type='fabric' AND dict_code='KJ';
UPDATE category_dict SET aliases = '["绒布","丝绒","天鹅绒布"]' WHERE dict_type='fabric' AND dict_code='VE';
UPDATE category_dict SET aliases = '["羊羔毛","泰迪绒","圈圈绒","羊羔绒"]' WHERE dict_type='fabric' AND dict_code='SF';
UPDATE category_dict SET aliases = '["灯芯绒布","条绒"]' WHERE dict_type='fabric' AND dict_code='DX';
UPDATE category_dict SET aliases = '["牛皮","头层牛皮","黄牛皮","全皮"]' WHERE dict_type='fabric' AND dict_code='ZP';
UPDATE category_dict SET aliases = '["纳帕真皮","Napa皮","NAPPA皮"]' WHERE dict_type='fabric' AND dict_code='NP';
UPDATE category_dict SET aliases = '["反绒皮","麂皮"]' WHERE dict_type='fabric' AND dict_code='MS';
UPDATE category_dict SET aliases = '["超纤","超纤革"]' WHERE dict_type='fabric' AND dict_code='CX';
UPDATE category_dict SET aliases = '["PU皮","PVC革","人造革","仿皮","西皮"]' WHERE dict_type='fabric' AND dict_code='PU';
UPDATE category_dict SET aliases = '["网眼布","透气网布"]' WHERE dict_type='fabric' AND dict_code='WB';
