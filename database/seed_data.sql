-- RSDP 字典表种子数据（PostgreSQL 版本）
-- 风格数据库的案例/元素/公式种子数据见 database/seed_style_knowledge.sql
-- 后续可根据实际业务扩展

-- 产品类别
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('category', 'FS', '座椅', 1),
('category', 'SF', '沙发', 2),
('category', 'TB', '茶几', 3),
('category', 'FC', '柜类', 4),
('category', 'BS', '吧椅', 5),
('category', 'OF', '办公家具', 6)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 家装风格（扩展为 11 个独立风格，保留 2 位编码）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('style', 'MC', '中古风', 1),
('style', 'BA', '包豪斯', 2),
('style', 'IT', '意式', 3),
('style', 'FR', '法式', 4),
('style', 'WJ', '侘寂', 5),
('style', 'NC', '新中式', 6),
('style', 'CR', '奶油风', 7),
('style', 'IN', '工业风', 8),
('style', 'MP', '孟菲斯', 9),
('style', 'IL', '意式极简轻奢', 10),
('style', 'ZS', '新中式宋式', 11),
('style', 'MB', '现代极简包豪斯', 12),
('style', 'MD', '孟菲斯多巴胺', 13),
('style', 'IO', '工业风LOFT', 14),
('style', 'FN', '法式复古南洋', 15),
('style', 'HH', '混搭风', 16),
('style', 'DL', '国外顶尖大牌搭配', 17)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 办公家具职级
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('grade', 'EX', '总裁级', 1),
('grade', 'MG', '经理级', 2),
('grade', 'ST', '职员级', 3),
('grade', 'PU', '公共区', 4),
('grade', 'CO', '会议区', 5)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- 材质版本码（精简为 19 个准确大类，覆盖风格百科高频材质）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('material', 'TN', '真藤/竹编/草编', 1),
('material', 'PE', 'PE仿藤', 2),
('material', 'LE', '皮革', 3),
('material', 'NP', '纳帕皮', 4),
('material', 'SU', '磨砂皮', 5),
('material', 'MA', '马鞍皮', 6),
('material', 'LI', '亚麻/棉麻', 7),
('material', 'WL', '羊毛', 8),
('material', 'SF', '羊羔绒/泰迪绒', 9),
('material', 'VE', '天鹅绒/绒布', 10),
('material', 'WO', '实木', 11),
('material', 'RK', '藤编+实木混血', 12),
('material', 'MT', '金属/不锈钢/黄铜', 13),
('material', 'GL', '玻璃', 14),
('material', 'ST', '天然石材/大理石/洞石/岩板', 15),
('material', 'CE', '水泥/混凝土/微水泥', 16),
('material', 'CL', '陶瓷', 17),
('material', 'GP', '石膏/PU线条', 18),
('material', 'PL', '塑料/亚克力', 19)
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

-- 颜色码（精简为 15 个准确色系，覆盖风格百科高频颜色）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('color', 'CARAMEL', '焦糖棕', 1),
('color', 'BEIGE', '米白/奶油白/燕麦色', 2),
('color', 'CA', '驼色/奶咖色', 3),
('color', 'DB', '深棕/胡桃木色', 4),
('color', 'NATURAL', '原木色', 5),
('color', 'BLACK', '黑色', 6),
('color', 'GRAY', '灰色', 7),
('color', 'NAVY', '藏青/蓝色系', 8),
('color', 'GN', '绿色系', 9),
('color', 'PR', '紫色系', 10),
('color', 'RD', '红色系', 11),
('color', 'OR', '橙色系', 12),
('color', 'PK', '粉色系', 13),
('color', 'YE', '黄色系', 14),
('color', 'WT', '白色', 15)
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

-- 空间类型（风格数据库 case 用）
INSERT INTO category_dict (dict_type, dict_code, dict_name, sort_order) VALUES
('room_type', 'LIVING_ROOM', '客厅', 1),
('room_type', 'BEDROOM', '卧室', 2),
('room_type', 'DINING_ROOM', '餐厅', 3),
('room_type', 'STUDY_ROOM', '书房', 4),
('room_type', 'OFFICE_EXECUTIVE', '总裁办公室', 5),
('room_type', 'OFFICE_STAFF', '职员办公区', 6),
('room_type', 'OFFICE_MEETING', '会议室', 7),
('room_type', 'CAFE', '咖啡厅', 8),
('room_type', 'HOTEL_ROOM', '酒店客房', 9),
('room_type', 'BAR', '酒吧', 10),
('room_type', 'OUTDOOR', '户外', 11)
ON CONFLICT (dict_type, dict_code) DO NOTHING;

-- =================== 系统权限与角色 ===================

-- 角色
INSERT INTO sys_role (role_code, role_name) VALUES
('ADMIN', '系统管理员'),
('EDITOR', '编辑员'),
('VIEWER', '浏览者'),
('FACTORY_ADMIN', '工厂管理员'),
('DESIGNER', '设计师'),
('USER', '普通用户')
ON CONFLICT (role_code) DO NOTHING;

-- 权限
INSERT INTO sys_permission (permission_code, permission_name) VALUES
('product:read', '查看产品'),
('product:create', '新品录入'),
('product:update', '编辑产品元数据'),
('product:delete', '删除产品'),
('product:review', '复核产品'),
('product:import', '批量导入产品'),
('factory:read', '查看工厂'),
('factory:create', '创建工厂'),
('factory:update', '编辑工厂'),
('factory:delete', '删除工厂'),
('rsku:read', '查看报价'),
('rsku:create', '新增报价'),
('rsku:update', '编辑报价'),
('rsku:delete', '删除报价'),
('rsku:import', '批量导入报价'),
('quote:read', '查看报价单'),
('quote:generate', '生成报价单'),
('quote:export', '导出报价单'),
('scheme:read', '查看搭配方案'),
('scheme:create', '创建搭配方案'),
('scheme:update', '编辑搭配方案'),
('scheme:delete', '删除搭配方案'),
('dict:create', '创建字典项'),
('user:read', '查看用户'),
('user:create', '创建用户'),
('user:update', '编辑用户'),
('user:delete', '删除用户'),
('user:reset-password', '重置密码'),
('admin:async-metrics', '查看异步线程池指标'),
('admin:vector-backfill', '向量回填'),
('collection:read', '查看产品集'),
('collection:create', '创建产品集'),
('collection:update', '编辑产品集'),
('collection:delete', '删除产品集'),
('capability:read', '查看工厂产品能力'),
('capability:create', '创建工厂产品能力'),
('capability:update', '编辑工厂产品能力'),
('capability:delete', '删除工厂产品能力'),
('designer:profile:read', '查看设计师画像'),
('designer:profile:update', '编辑设计师画像'),
('recommendation:score:config:read', '查看推荐打分配置'),
('recommendation:score:config:update', '编辑推荐打分配置'),
('scheme:candidate:read', '查看 AI 推荐候选'),
('scheme:candidate:create', '创建 AI 推荐候选'),
('scheme:candidate:update', '编辑 AI 推荐候选'),
('scheme:candidate:delete', '删除 AI 推荐候选')
ON CONFLICT (permission_code) DO NOTHING;

-- ADMIN 拥有所有权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'ADMIN'
ON CONFLICT DO NOTHING;

-- EDITOR：除用户管理和高级 admin 外的全部权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'EDITOR'
  AND p.permission_code NOT IN ('user:read', 'user:create', 'user:update', 'user:delete', 'user:reset-password', 'admin:async-metrics', 'admin:vector-backfill', 'recommendation:score:config:read', 'recommendation:score:config:update')
ON CONFLICT DO NOTHING;

-- VIEWER：只读
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'VIEWER'
  AND p.permission_code IN ('product:read', 'factory:read', 'rsku:read', 'quote:read', 'scheme:read', 'collection:read', 'capability:read')
ON CONFLICT DO NOTHING;

-- FACTORY_ADMIN：自己工厂产品 + 报价相关 + 只读
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'FACTORY_ADMIN'
  AND p.permission_code IN ('product:read', 'product:create', 'product:update', 'factory:read', 'rsku:read', 'rsku:create', 'rsku:update', 'rsku:delete', 'rsku:import', 'capability:read')
ON CONFLICT DO NOTHING;

-- DESIGNER：方案/报价 + 只读
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'DESIGNER'
  AND p.permission_code IN ('product:read', 'factory:read', 'rsku:read', 'quote:read', 'quote:generate', 'quote:export', 'scheme:read', 'scheme:create', 'scheme:update', 'scheme:delete', 'collection:read', 'capability:read', 'designer:profile:read', 'designer:profile:update', 'scheme:candidate:read', 'scheme:candidate:create', 'scheme:candidate:update', 'scheme:candidate:delete')
ON CONFLICT DO NOTHING;

-- USER：只读
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM sys_role r, sys_permission p
WHERE r.role_code = 'USER'
  AND p.permission_code IN ('product:read', 'factory:read', 'rsku:read', 'quote:read', 'scheme:read', 'collection:read', 'capability:read')
ON CONFLICT DO NOTHING;

-- =================== 开发测试账号（仅在开发/演示环境使用） ===================

-- 测试工厂
INSERT INTO factory_master (factory_code, factory_name, factory_level, region, status) VALUES
('TEST', '测试工厂', 'A', '广东', 'active')
ON CONFLICT (factory_code) DO NOTHING;

-- 测试用户（密码统一为 admin123）
-- 按 username 冲突更新，确保开发环境重置后密码与快速登录按钮一致
INSERT INTO sys_user (user_id, username, password_hash, nickname, status, view_full_catalog) VALUES
('USER-ADMIN-00000001', 'admin', '$2a$10$YQtLexRaBqyq/izJKShvFOCfdZb3qZkF9.npxvreC.Z843SuVE8z.', '系统管理员', 'active', true),
('USER-EDITOR-00000001', 'editor', '$2a$10$YQtLexRaBqyq/izJKShvFOCfdZb3qZkF9.npxvreC.Z843SuVE8z.', '编辑员', 'active', true),
('USER-VIEWER-00000001', 'viewer', '$2a$10$YQtLexRaBqyq/izJKShvFOCfdZb3qZkF9.npxvreC.Z843SuVE8z.', '浏览者', 'active', false),
('USER-DESIGNER-00000001', 'designer', '$2a$10$YQtLexRaBqyq/izJKShvFOCfdZb3qZkF9.npxvreC.Z843SuVE8z.', '设计师', 'active', false),
('USER-FACTORY-00000001', 'factory', '$2a$10$YQtLexRaBqyq/izJKShvFOCfdZb3qZkF9.npxvreC.Z843SuVE8z.', '工厂管理员', 'active', false),
('USER-USER-00000001', 'user', '$2a$10$YQtLexRaBqyq/izJKShvFOCfdZb3qZkF9.npxvreC.Z843SuVE8z.', '普通用户', 'active', false)
ON CONFLICT (username) DO UPDATE SET
  password_hash = EXCLUDED.password_hash,
  nickname = EXCLUDED.nickname,
  status = EXCLUDED.status,
  view_full_catalog = EXCLUDED.view_full_catalog;

-- 测试用户角色关联
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.user_id, r.role_id
FROM sys_user u, sys_role r
WHERE u.username IN ('admin', 'editor', 'viewer', 'designer', 'factory', 'user')
  AND r.role_code = CASE u.username
    WHEN 'admin' THEN 'ADMIN'
    WHEN 'editor' THEN 'EDITOR'
    WHEN 'viewer' THEN 'VIEWER'
    WHEN 'designer' THEN 'DESIGNER'
    WHEN 'factory' THEN 'FACTORY_ADMIN'
    WHEN 'user' THEN 'USER'
  END
ON CONFLICT (user_id, role_id) DO NOTHING;

-- 工厂管理员绑定测试工厂
INSERT INTO sys_user_factory (user_id, factory_code)
SELECT u.user_id, 'TEST'
FROM sys_user u
WHERE u.username = 'factory'
ON CONFLICT (user_id, factory_code) DO NOTHING;
