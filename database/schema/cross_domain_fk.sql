-- ============================================================
-- RSDP 基线 DDL · 跨域后置外键（cross_domain_fk.sql，无数字编号）
-- 包含表：（无新表）后置外键 ALTER——原文件已有的循环引用后置（excel_import_batch.created_by、document_import_batch.created_by、sys_user↔company/member_group/invited_by、user_favorite.folder_id、scheme.project_id）+ 按域拆分后跨域执行序后置（rspu_master.created_by、rsku_supply↔factory_master/factory_warehouse、user_favorite/favorite_folder/product_collection/project/design_order/recommendation_score_config→sys_user）
-- 执行顺序：schema/ 目录按文件名字母序执行（01_~12_ 数字编号域文件在前，本文件字母开头排最后，zz_seed.sql 种子殿后）
-- 同步约定：新增/修改本域表结构时须同步 ops/reset_db.sql，约定详见 database/README.md
-- ============================================================
-- 补齐 excel_import_batch 外键（该表在 sys_user 之前创建）
ALTER TABLE excel_import_batch
    DROP CONSTRAINT IF EXISTS fk_excel_import_batch_created_by;
ALTER TABLE excel_import_batch
    ADD CONSTRAINT fk_excel_import_batch_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(user_id);

-- 补齐 document_import_batch 外键（该表在 sys_user 之前创建）
ALTER TABLE document_import_batch
    DROP CONSTRAINT IF EXISTS fk_document_import_batch_created_by;
ALTER TABLE document_import_batch
    ADD CONSTRAINT fk_document_import_batch_created_by
        FOREIGN KEY (created_by) REFERENCES sys_user(user_id);

-- 补齐 sys_user 企业/邀请外键（company/member_group 在 sys_user 之后创建，循环引用需后置）
ALTER TABLE sys_user DROP CONSTRAINT IF EXISTS fk_sys_user_company;
ALTER TABLE sys_user
    ADD CONSTRAINT fk_sys_user_company FOREIGN KEY (company_id) REFERENCES company(company_id);
ALTER TABLE sys_user DROP CONSTRAINT IF EXISTS fk_sys_user_group;
ALTER TABLE sys_user
    ADD CONSTRAINT fk_sys_user_group FOREIGN KEY (group_id) REFERENCES member_group(group_id);
ALTER TABLE sys_user DROP CONSTRAINT IF EXISTS fk_sys_user_invited_by;
ALTER TABLE sys_user
    ADD CONSTRAINT fk_sys_user_invited_by FOREIGN KEY (invited_by) REFERENCES sys_user(user_id);

-- 补齐 user_favorite 文件夹外键（favorite_folder 在 user_favorite 之后创建）
ALTER TABLE user_favorite DROP CONSTRAINT IF EXISTS fk_user_favorite_folder;
ALTER TABLE user_favorite
    ADD CONSTRAINT fk_user_favorite_folder FOREIGN KEY (folder_id) REFERENCES favorite_folder(folder_id);

-- scheme.project_id 外键（表创建顺序约束，单独补加）
ALTER TABLE scheme DROP CONSTRAINT IF EXISTS fk_scheme_project;
ALTER TABLE scheme ADD CONSTRAINT fk_scheme_project FOREIGN KEY (project_id) REFERENCES project(project_id);

-- ============================================================
-- 按域拆分后的跨域外键后置（02_product 在 03_factory 之前、07/08 在 09_user_team 之前执行，
-- 建表时目标表尚不存在，FK 统一在此补加；与既有循环引用后置同一模式）
-- ============================================================

-- 02_product → 09_user_team：rspu_master 录入人外键（V3）
ALTER TABLE rspu_master DROP CONSTRAINT IF EXISTS fk_rspu_master_created_by;
ALTER TABLE rspu_master
    ADD CONSTRAINT fk_rspu_master_created_by FOREIGN KEY (created_by) REFERENCES sys_user(user_id);

-- 02_product → 03_factory：rsku_supply 工厂/仓库外键
ALTER TABLE rsku_supply DROP CONSTRAINT IF EXISTS fk_rsku_supply_factory;
ALTER TABLE rsku_supply
    ADD CONSTRAINT fk_rsku_supply_factory FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code);
ALTER TABLE rsku_supply DROP CONSTRAINT IF EXISTS fk_rsku_supply_warehouse;
ALTER TABLE rsku_supply
    ADD CONSTRAINT fk_rsku_supply_warehouse FOREIGN KEY (shipping_warehouse_id) REFERENCES factory_warehouse(warehouse_id);

-- 07_project_scheme → 09_user_team：收藏/产品集/项目用户外键
ALTER TABLE user_favorite DROP CONSTRAINT IF EXISTS fk_user_favorite_user;
ALTER TABLE user_favorite
    ADD CONSTRAINT fk_user_favorite_user FOREIGN KEY (user_id) REFERENCES sys_user(user_id);
ALTER TABLE favorite_folder DROP CONSTRAINT IF EXISTS fk_favorite_folder_user;
ALTER TABLE favorite_folder
    ADD CONSTRAINT fk_favorite_folder_user FOREIGN KEY (user_id) REFERENCES sys_user(user_id);
ALTER TABLE product_collection DROP CONSTRAINT IF EXISTS fk_product_collection_created_by;
ALTER TABLE product_collection
    ADD CONSTRAINT fk_product_collection_created_by FOREIGN KEY (created_by) REFERENCES sys_user(user_id);
ALTER TABLE project DROP CONSTRAINT IF EXISTS fk_project_owner;
ALTER TABLE project
    ADD CONSTRAINT fk_project_owner FOREIGN KEY (owner_id) REFERENCES sys_user(user_id);

-- 08_order_pricing → 09_user_team：订单/推荐配置用户外键
ALTER TABLE design_order DROP CONSTRAINT IF EXISTS fk_design_order_created_by;
ALTER TABLE design_order
    ADD CONSTRAINT fk_design_order_created_by FOREIGN KEY (created_by) REFERENCES sys_user(user_id);
ALTER TABLE recommendation_score_config DROP CONSTRAINT IF EXISTS fk_recommendation_config_created_by;
ALTER TABLE recommendation_score_config
    ADD CONSTRAINT fk_recommendation_config_created_by FOREIGN KEY (created_by) REFERENCES sys_user(user_id);

