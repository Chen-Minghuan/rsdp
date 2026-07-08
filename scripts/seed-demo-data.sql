--- RSDP 本地演示数据补充脚本
-- 前置条件：scripts/seed-demo-data.sh 已将对应图片复制到 server/data/uploads/images/
-- 说明：本脚本幂等，可重复执行（ON CONFLICT DO NOTHING / UPDATE WHERE）

-- =================== 演示工厂 ===================
INSERT INTO factory_master (
    factory_code, factory_name, factory_level, home_commercial_tag,
    region, address, contact_person, contact_phone, notes,
    certification, engineering_cases,
    factory_area, employee_count, monthly_capacity, founded_year,
    equipment_list, frame_wood, sponge_supplier, leather_fabric_source, hardware_supplier,
    qc_items, qc_staff_count, shipping_from, logistics_methods, default_packaging,
    auditor_signature, factory_images,
    status, created_at, updated_at
) VALUES
(
    'F001', '东莞高端定制家具厂', 'S', '家用级',
    '广东东莞', '东莞市厚街镇家具大道1号', '张经理', '13800138001', '专注高端沙发/休闲椅定制',
    '["ISO9001","FSC"]'::jsonb, '[{"name":"某五星酒店大堂"}]'::jsonb,
    8000.00, 320, 5000, 2008,
    '["CNC","CUTTING","SEWING","PAINTING"]'::jsonb, '橡木', '东亚海绵', '意大利进口头层牛皮', 'DTC五金',
    '["INCOMING","PROCESS","FINAL","THIRD_PARTY"]'::jsonb, 18, '东莞', '["SPECIAL","SF"]'::jsonb, '["CARTON","WOODEN"]'::jsonb,
    '王审核', '["images/demo-test-01.png","images/demo-test-02.png"]'::jsonb,
    'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
),
(
    'F002', '佛山软体家具厂', 'B', '家用级',
    '广东佛山', '佛山市顺德区龙江镇工业二路8号', '李厂长', '13900139002', '擅长布艺沙发批量生产',
    NULL, NULL,
    12000.00, 450, 8000, 2012,
    '["CUTTING","SEWING","HOT_PRESS"]'::jsonb, '松木', '恒业海绵', '国产棉麻面料', '国产五金',
    '["INCOMING","PROCESS","FINAL"]'::jsonb, 12, '佛山', '["SF","DEBANG"]'::jsonb, '["CARTON"]'::jsonb,
    '刘审核', '[]'::jsonb,
    'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
)
ON CONFLICT (factory_code) DO NOTHING;

-- 演示工厂能力等级
INSERT INTO factory_level_capability (factory_code, level_code, is_primary) VALUES
('F001', 'S', true),
('F001', 'A', false),
('F002', 'B', true),
('TEST', 'A', true)
ON CONFLICT DO NOTHING;

-- 补全 TEST 工厂资料
UPDATE factory_master SET
    home_commercial_tag = '家用级',
    certification = '["ISO9001"]'::jsonb,
    engineering_cases = '[{"name":"测试酒店项目"}]'::jsonb,
    address = '广州市番禺区测试路88号',
    contact_person = '陈经理',
    contact_phone = '13700137000',
    notes = '系统默认测试工厂',
    factory_area = 5000.00,
    employee_count = 200,
    monthly_capacity = 3000,
    founded_year = 2015,
    equipment_list = '["CNC","CUTTING","SEWING"]'::jsonb,
    frame_wood = '桦木',
    sponge_supplier = '测试海绵厂',
    leather_fabric_source = '测试皮行',
    hardware_supplier = '测试五金',
    qc_items = '["INCOMING","PROCESS","FINAL"]'::jsonb,
    qc_staff_count = 8,
    shipping_from = '广州',
    logistics_methods = '["SF","SPECIAL"]'::jsonb,
    default_packaging = '["CARTON"]'::jsonb,
    auditor_signature = '赵审核',
    factory_images = '["images/demo-test-01.png","images/demo-test-02.png"]'::jsonb,
    updated_at = CURRENT_TIMESTAMP
WHERE factory_code = 'TEST';

-- =================== 演示 RSPU（按风格各一条） ===================
INSERT INTO rspu_master (
    rspu_id, external_code, category_code, category_path, positioning_label,
    status, review_status, created_at, updated_at
) VALUES
('DEMO-RSPU-MC', 'DEMO-MC-001', 'SF', '/SF', 'MC', 'active', '已确认', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSPU-WJ', 'DEMO-WJ-001', 'SF', '/SF', 'WJ', 'active', '已确认', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSPU-CR', 'DEMO-CR-001', 'SF', '/SF', 'CR', 'active', '已确认', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSPU-MD', 'DEMO-MD-001', 'SF', '/SF', 'MD', 'active', '已确认', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSPU-IO', 'DEMO-IO-001', 'SF', '/SF', 'IO', 'active', '已确认', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSPU-IL', 'DEMO-IL-001', 'SF', '/SF', 'IL', 'active', '已确认', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSPU-ZS', 'DEMO-ZS-001', 'SF', '/SF', 'ZS', 'active', '已确认', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSPU-FN', 'DEMO-FN-001', 'SF', '/SF', 'FN', 'active', '已确认', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSPU-HH', 'DEMO-HH-001', 'SF', '/SF', 'HH', 'active', '已确认', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSPU-MB', 'DEMO-MB-001', 'SF', '/SF', 'MB', 'active', '已确认', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (rspu_id) DO NOTHING;

-- RSPU 主风格关联
INSERT INTO rspu_style (rspu_id, style_code, is_primary) VALUES
('DEMO-RSPU-MC', 'MC', true),
('DEMO-RSPU-WJ', 'WJ', true),
('DEMO-RSPU-CR', 'CR', true),
('DEMO-RSPU-MD', 'MD', true),
('DEMO-RSPU-IO', 'IO', true),
('DEMO-RSPU-IL', 'IL', true),
('DEMO-RSPU-ZS', 'ZS', true),
('DEMO-RSPU-FN', 'FN', true),
('DEMO-RSPU-HH', 'HH', true),
('DEMO-RSPU-MB', 'MB', true)
ON CONFLICT DO NOTHING;

-- =================== 演示变体 ===================
INSERT INTO rspu_variant (
    variant_id, rspu_id, display_name, variant_code,
    dimensions, material_code, product_level, status, created_at, updated_at
) VALUES
('DEMO-RSPU-MC-V001', 'DEMO-RSPU-MC', '中古风休闲椅 中号', 'M', '{"w":620,"d":620,"h":820,"unit":"mm"}'::jsonb, 'LE', 'A', 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSPU-WJ-V001', 'DEMO-RSPU-WJ', '侘寂风单椅 中号', 'M', '{"w":600,"d":600,"h":780,"unit":"mm"}'::jsonb, 'LI', 'A', 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSPU-CR-V001', 'DEMO-RSPU-CR', '奶油风沙发 三人位', 'M', '{"w":2100,"d":900,"h":850,"unit":"mm"}'::jsonb, 'SF', 'B', 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSPU-MD-V001', 'DEMO-RSPU-MD', '孟菲斯多巴胺边椅 中号', 'M', '{"w":480,"d":520,"h":820,"unit":"mm"}'::jsonb, 'PL', 'B', 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSPU-IO-V001', 'DEMO-RSPU-IO', '工业风LOFT吧椅 中号', 'M', '{"w":450,"d":450,"h":750,"unit":"mm"}'::jsonb, 'MT', 'B', 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSPU-IL-V001', 'DEMO-RSPU-IL', '意式极简轻奢沙发 三人位', 'M', '{"w":2200,"d":950,"h":800,"unit":"mm"}'::jsonb, 'NP', 'S', 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSPU-ZS-V001', 'DEMO-RSPU-ZS', '新中式宋式圈椅 中号', 'M', '{"w":620,"d":580,"h":920,"unit":"mm"}'::jsonb, 'WO', 'A', 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSPU-FN-V001', 'DEMO-RSPU-FN', '法式复古南洋休闲椅 中号', 'M', '{"w":650,"d":650,"h":850,"unit":"mm"}'::jsonb, 'VE', 'A', 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSPU-HH-V001', 'DEMO-RSPU-HH', '混搭风组合沙发 三人位', 'M', '{"w":2300,"d":900,"h":820,"unit":"mm"}'::jsonb, 'LE', 'B', 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSPU-MB-V001', 'DEMO-RSPU-MB', '现代极简包豪斯扶手椅 中号', 'M', '{"w":580,"d":600,"h":790,"unit":"mm"}'::jsonb, 'LE', 'A', 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (variant_id) DO NOTHING;

-- =================== 演示图片 ===================
INSERT INTO image_assets (
    image_id, rspu_id, image_type, storage_path, storage_url,
    format, is_primary, ai_processed, uploaded_by, created_at
) VALUES
('DEMO-IMG-MC', 'DEMO-RSPU-MC', 'original', 'images/demo-mc-001.png', '/api/v1/images/DEMO-IMG-MC', 'png', true, false, 'SYSTEM', CURRENT_TIMESTAMP),
('DEMO-IMG-WJ', 'DEMO-RSPU-WJ', 'original', 'images/demo-wj-001.png', '/api/v1/images/DEMO-IMG-WJ', 'png', true, false, 'SYSTEM', CURRENT_TIMESTAMP),
('DEMO-IMG-CR', 'DEMO-RSPU-CR', 'original', 'images/demo-cr-001.png', '/api/v1/images/DEMO-IMG-CR', 'png', true, false, 'SYSTEM', CURRENT_TIMESTAMP),
('DEMO-IMG-MD', 'DEMO-RSPU-MD', 'original', 'images/demo-md-001.png', '/api/v1/images/DEMO-IMG-MD', 'png', true, false, 'SYSTEM', CURRENT_TIMESTAMP),
('DEMO-IMG-IO', 'DEMO-RSPU-IO', 'original', 'images/demo-io-001.png', '/api/v1/images/DEMO-IMG-IO', 'png', true, false, 'SYSTEM', CURRENT_TIMESTAMP),
('DEMO-IMG-IL', 'DEMO-RSPU-IL', 'original', 'images/demo-il-001.png', '/api/v1/images/DEMO-IMG-IL', 'png', true, false, 'SYSTEM', CURRENT_TIMESTAMP),
('DEMO-IMG-ZS', 'DEMO-RSPU-ZS', 'original', 'images/demo-zs-001.png', '/api/v1/images/DEMO-IMG-ZS', 'png', true, false, 'SYSTEM', CURRENT_TIMESTAMP),
('DEMO-IMG-FN', 'DEMO-RSPU-FN', 'original', 'images/demo-fn-001.png', '/api/v1/images/DEMO-IMG-FN', 'png', true, false, 'SYSTEM', CURRENT_TIMESTAMP),
('DEMO-IMG-HH', 'DEMO-RSPU-HH', 'original', 'images/demo-hh-001.png', '/api/v1/images/DEMO-IMG-HH', 'png', true, false, 'SYSTEM', CURRENT_TIMESTAMP),
('DEMO-IMG-MB', 'DEMO-RSPU-MB', 'original', 'images/demo-mb-001.png', '/api/v1/images/DEMO-IMG-MB', 'png', true, false, 'SYSTEM', CURRENT_TIMESTAMP)
ON CONFLICT (image_id) DO NOTHING;

-- =================== 演示 RSKU（TEST 工厂报价） ===================
INSERT INTO rsku_supply (
    rsku_id, rspu_id, variant_id, factory_code, factory_sku,
    price_band, product_level, material_code, material_description,
    lead_time_days, moq, warranty_years, shipping_from,
    review_status, created_at, updated_at
) VALUES
('DEMO-RSKU-MC', 'DEMO-RSPU-MC', 'DEMO-RSPU-MC-V001', 'TEST', 'TEST-MC-001', 'mid', 'A', 'LE', '头层牛皮+实木框架', 30, 10, 3, '广州', '待复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSKU-WJ', 'DEMO-RSPU-WJ', 'DEMO-RSPU-WJ-V001', 'TEST', 'TEST-WJ-001', 'mid', 'A', 'LI', '亚麻布艺+实木框架', 25, 10, 3, '广州', '待复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSKU-CR', 'DEMO-RSPU-CR', 'DEMO-RSPU-CR-V001', 'TEST', 'TEST-CR-001', 'mid', 'B', 'SF', '羊羔绒+松木框架', 28, 5, 2, '广州', '待复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSKU-MD', 'DEMO-RSPU-MD', 'DEMO-RSPU-MD-V001', 'TEST', 'TEST-MD-001', 'mid', 'B', 'PL', '塑料+金属支架', 20, 20, 2, '广州', '待复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSKU-IO', 'DEMO-RSPU-IO', 'DEMO-RSPU-IO-V001', 'TEST', 'TEST-IO-001', 'mid', 'B', 'MT', '金属+皮革座面', 22, 15, 2, '广州', '待复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSKU-IL', 'DEMO-RSPU-IL', 'DEMO-RSPU-IL-V001', 'TEST', 'TEST-IL-001', 'high', 'S', 'NP', '纳帕皮+实木框架', 35, 5, 5, '广州', '待复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSKU-ZS', 'DEMO-RSPU-ZS', 'DEMO-RSPU-ZS-V001', 'TEST', 'TEST-ZS-001', 'mid', 'A', 'WO', '实木榫卯结构', 32, 8, 3, '广州', '待复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSKU-FN', 'DEMO-RSPU-FN', 'DEMO-RSPU-FN-V001', 'TEST', 'TEST-FN-001', 'mid', 'A', 'VE', '绒布+实木框架', 30, 10, 3, '广州', '待复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSKU-HH', 'DEMO-RSPU-HH', 'DEMO-RSPU-HH-V001', 'TEST', 'TEST-HH-001', 'mid', 'B', 'LE', '皮革+布艺混搭', 28, 5, 2, '广州', '待复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSKU-MB', 'DEMO-RSPU-MB', 'DEMO-RSPU-MB-V001', 'TEST', 'TEST-MB-001', 'mid', 'A', 'LE', '头层牛皮+金属支架', 26, 10, 3, '广州', '待复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (rsku_id) DO NOTHING;

-- 为 F001/F002 补充部分报价（价格字段留空，方便后续通过前端/导入维护）
INSERT INTO rsku_supply (
    rsku_id, rspu_id, variant_id, factory_code, factory_sku,
    price_band, product_level, material_code, material_description,
    lead_time_days, moq, warranty_years, shipping_from,
    review_status, created_at, updated_at
) VALUES
('DEMO-RSKU-MC-F001', 'DEMO-RSPU-MC', 'DEMO-RSPU-MC-V001', 'F001', 'F001-MC-001', 'high', 'S', 'LE', '进口头层牛皮+橡木框架', 45, 5, 5, '东莞', '待复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
('DEMO-RSKU-CR-F002', 'DEMO-RSPU-CR', 'DEMO-RSPU-CR-V001', 'F002', 'F002-CR-001', 'low', 'B', 'SF', '羊羔绒+松木框架', 18, 20, 2, '佛山', '待复核', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (rsku_id) DO NOTHING;
