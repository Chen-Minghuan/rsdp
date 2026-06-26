-- RSDP 字典表种子数据（PostgreSQL 版本）
-- 后续可根据实际业务扩展

-- 产品类别
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('category', 'FS', '座椅', 1),
('category', 'SF', '沙发', 2),
('category', 'TB', '茶几', 3),
('category', 'FC', '柜类', 4),
('category', 'BS', '吧椅', 5),
('category', 'DT', '桌子', 6),
('category', 'CB', '柜子', 7),
('category', 'BD', '床', 8),
('category', 'OF', '办公家具', 9)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 家装风格
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('style', 'MC', '中古风', 1),
('style', 'BA', '包豪斯', 2),
('style', 'IT', '意式', 3),
('style', 'FR', '法式', 4),
('style', 'WJ', '侘寂', 5),
('style', 'NC', '新中式', 6),
('style', 'CR', '奶油风', 7),
('style', 'IN', '工业风', 8),
('style', 'MP', '孟菲斯', 9)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 办公家具职级
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('grade', 'EX', '总裁级', 1),
('grade', 'MG', '经理级', 2),
('grade', 'ST', '职员级', 3),
('grade', 'PU', '公共区', 4),
('grade', 'CO', '会议区', 5)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 材质版本码
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('material', 'TN', '真藤', 1),
('material', 'PE', 'PE仿藤', 2),
('material', 'LE', '皮革', 3),
('material', 'NP', '纳帕皮', 4),
('material', 'SU', '磨砂皮', 5),
('material', 'MA', '马鞍皮', 6),
('material', 'LI', '亚麻', 7),
('material', 'SF', '羊羔绒', 8),
('material', 'VE', '天鹅绒', 9),
('material', 'WO', '实木', 10),
('material', 'RK', '藤编+实木混血', 11),
('material', 'MT', '金属', 12)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维标签 - 休闲椅 A 维度（轮廓）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order) VALUES
('six_dim_A', 'A字架形', 'A字架形', 'FS', 1),
('six_dim_A', '蛋形', '蛋形', 'FS', 2),
('six_dim_A', '方盒形', '方盒形', 'FS', 3)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 六维标签 - 休闲椅 B 维度（靠背）
INSERT INTO category_dict (dict_type, dict_code, dict_name, parent_code, sort_order) VALUES
('six_dim_B', '编织镂空', '编织镂空', 'FS', 1),
('six_dim_B', '高背包裹', '高背包裹', 'FS', 2),
('six_dim_B', '无靠背', '无靠背', 'FS', 3)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 工厂等级
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('factory_level', 'S', 'S级战略厂', 1),
('factory_level', 'A', 'A级核心厂', 2),
('factory_level', 'B', 'B级合作厂', 3),
('factory_level', 'C', 'C级备选厂', 4)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 复核状态
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('review_status', '待复核', '待复核', 1),
('review_status', '已确认', '已确认', 2),
('review_status', '存疑', '存疑', 3)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 尺寸码
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('size', 'S', '小号', 1),
('size', 'M', '中号', 2),
('size', 'L', '大号', 3),
('size', 'SINGLE', '单人位', 4),
('size', 'DOUBLE', '双人位', 5),
('size', 'TRIPLE', '三人位', 6)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 颜色码
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('color', 'CARAMEL', '焦糖棕', 1),
('color', 'BEIGE', '米白', 2),
('color', 'NATURAL', '原木色', 3),
('color', 'BLACK', '黑色', 4),
('color', 'GRAY', '灰色', 5),
('color', 'NAVY', '藏青', 6)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 场景标签
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('scene', 'LIVING', '客厅', 1),
('scene', 'STUDY', '书房', 2),
('scene', 'BEDROOM', '卧室', 3),
('scene', 'CAFE', '咖啡厅', 4),
('scene', 'OFFICE', '办公室', 5),
('scene', 'HOTEL', '酒店', 6)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 空间类型（用于 AI 搭配方案）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('room_type', 'LIVING_ROOM', '客厅', 1),
('room_type', 'BEDROOM', '卧室', 2),
('room_type', 'DINING_ROOM', '餐厅', 3),
('room_type', 'STUDY', '书房', 4),
('room_type', 'OFFICE', '办公室', 5)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 报价置信度
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('quote_confidence', 'high', '高', 1),
('quote_confidence', 'mid', '中', 2),
('quote_confidence', 'low', '低', 3)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 产品状态
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('product_status', 'active', '在售', 1),
('product_status', 'discontinued', '停产', 2),
('product_status', 'draft', '草稿', 3)
ON CONFLICT (dict_type, dict_code) DO NOTHING;
