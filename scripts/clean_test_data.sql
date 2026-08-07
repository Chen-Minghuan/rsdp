-- RSDP 测试数据清理脚本
-- 用途：清空产品、工厂、方案、订单、导入批次等业务数据，保留用户/权限/字典/系统配置
-- 执行方式：docker exec -i rsdp-postgres psql -U rsdp -d rsdp < scripts/clean_test_data.sql

-- 环境守卫：防止误在其他数据库执行
DO $$
BEGIN
    IF current_database() != 'rsdp' THEN
        RAISE EXCEPTION '此脚本只能在 rsdp 数据库中执行，当前数据库: %', current_database();
    END IF;
END $$;

-- 关闭外键约束检查，清理完成后恢复
SET session_replication_role = replica;

TRUNCATE TABLE matching_feedback CASCADE;
TRUNCATE TABLE product_style_match CASCADE;
TRUNCATE TABLE style_element CASCADE;
TRUNCATE TABLE style_matching_formula CASCADE;
TRUNCATE TABLE style_case CASCADE;
TRUNCATE TABLE ai_recognition CASCADE;
TRUNCATE TABLE image_assets CASCADE;
TRUNCATE TABLE price_history CASCADE;
TRUNCATE TABLE rsku_supply CASCADE;
TRUNCATE TABLE rspu_price_column_mapping CASCADE;
TRUNCATE TABLE excel_import_price_column CASCADE;
TRUNCATE TABLE excel_import_row CASCADE;
TRUNCATE TABLE factory_lead_time_rule CASCADE;
TRUNCATE TABLE rspu_factory_mapping CASCADE;
TRUNCATE TABLE factory_capacity_assessment CASCADE;
TRUNCATE TABLE factory_variant_capacity CASCADE;
TRUNCATE TABLE factory_warehouse CASCADE;
TRUNCATE TABLE factory_level_capability CASCADE;
TRUNCATE TABLE variant_code_counter CASCADE;
TRUNCATE TABLE rspu_variant CASCADE;
TRUNCATE TABLE rspu_relation CASCADE;
TRUNCATE TABLE factory_master CASCADE;
TRUNCATE TABLE rspu_scene CASCADE;
TRUNCATE TABLE rspu_code_counter CASCADE;
TRUNCATE TABLE rsku_code_counter CASCADE;
TRUNCATE TABLE rspu_style CASCADE;
TRUNCATE TABLE rspu_master CASCADE;
TRUNCATE TABLE excel_import_batch CASCADE;
TRUNCATE TABLE async_task CASCADE;
TRUNCATE TABLE audit_log CASCADE;
TRUNCATE TABLE factory_product_capability CASCADE;
TRUNCATE TABLE scheme_item CASCADE;
TRUNCATE TABLE scheme CASCADE;
TRUNCATE TABLE scheme_candidate CASCADE;
TRUNCATE TABLE recommendation_score_config CASCADE;
TRUNCATE TABLE designer_profile CASCADE;
TRUNCATE TABLE product_collection_item CASCADE;
TRUNCATE TABLE product_collection CASCADE;
TRUNCATE TABLE user_favorite CASCADE;
TRUNCATE TABLE favorite_folder CASCADE;
TRUNCATE TABLE template_tag CASCADE;
TRUNCATE TABLE platform_banner CASCADE;
TRUNCATE TABLE platform_case CASCADE;
TRUNCATE TABLE platform_content CASCADE;
TRUNCATE TABLE platform_custom_dict CASCADE;
TRUNCATE TABLE platform_customized CASCADE;
TRUNCATE TABLE project CASCADE;
TRUNCATE TABLE design_order_item CASCADE;
TRUNCATE TABLE design_order CASCADE;
TRUNCATE TABLE order_no_counter CASCADE;
TRUNCATE TABLE dict_unresolved_value CASCADE;

SET session_replication_role = DEFAULT;

-- 重置业务序列（可选；序列不存在时跳过——注意 setval 字面量会在解析期转 regclass，须用 to_regclass 守卫）
DO $$
BEGIN
    IF to_regclass('public.rspu_master_rspu_id_seq') IS NOT NULL THEN
        PERFORM setval('rspu_master_rspu_id_seq', 1, false);
    END IF;
END $$;

ANALYZE;

DO $$
DECLARE
    r_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO r_count FROM rspu_master;
    RAISE NOTICE '清理完成，当前 rspu_master 行数: %', r_count;
END $$;
