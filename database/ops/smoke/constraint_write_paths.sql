-- V17 合并迁移写入路径回归；仅在空的临时库（已加载 schema/）执行，夹具最终回滚。
-- psql -v ON_ERROR_STOP=1 -d <temp_db> -f database/ops/smoke/constraint_write_paths.sql
BEGIN;

INSERT INTO sys_user(user_id, username, password_hash)
VALUES ('SMK-USER', 'smk-constraints', 'not-a-login');
INSERT INTO agent_session(session_id, created_by) VALUES ('SMK-SESSION', 'SMK-USER');
DO $$
BEGIN
    -- 旧顺序确实被归属外键拒绝。
    BEGIN
        UPDATE agent_session SET active_run_id = 'SMK-RUN' WHERE session_id = 'SMK-SESSION';
        RAISE EXCEPTION 'FAIL: active_run_id 指向不存在的 run 未被拦截';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;
END $$;
INSERT INTO agent_run(run_id, session_id, status) VALUES ('SMK-RUN', 'SMK-SESSION', 'running');
UPDATE agent_session SET active_run_id = 'SMK-RUN'
WHERE session_id = 'SMK-SESSION' AND active_run_id IS NULL;
DO $$
BEGIN
    -- 模拟第二个请求认领失败，子事务回滚其新建 run。
    BEGIN
        INSERT INTO agent_run(run_id, session_id, status) VALUES ('SMK-LOSER', 'SMK-SESSION', 'running');
        UPDATE agent_session SET active_run_id = 'SMK-LOSER'
        WHERE session_id = 'SMK-SESSION' AND active_run_id IS NULL;
        IF FOUND THEN RAISE EXCEPTION 'FAIL: 同会话重复认领成功'; END IF;
        RAISE EXCEPTION USING ERRCODE = 'P0002', MESSAGE = 'SESSION_BUSY';
    EXCEPTION WHEN no_data_found THEN NULL;
    END;
    IF EXISTS (SELECT 1 FROM agent_run WHERE run_id = 'SMK-LOSER') THEN
        RAISE EXCEPTION 'FAIL: 失败认领遗留 run';
    END IF;
END $$;

INSERT INTO factory_master(factory_code, factory_name, factory_level) VALUES ('SMK-F', 'smoke', 'S');
INSERT INTO factory_level_capability(factory_code, level_code, is_primary)
VALUES ('SMK-F', 'S', true), ('SMK-F', 'A', false);
DO $$
BEGIN
    BEGIN
        UPDATE factory_level_capability SET is_primary = true WHERE factory_code = 'SMK-F' AND level_code = 'A';
        RAISE EXCEPTION 'FAIL: 双主项未拦截';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;
END $$;
UPDATE factory_level_capability SET is_primary = false WHERE factory_code = 'SMK-F' AND is_primary;
UPDATE factory_level_capability SET is_primary = true WHERE factory_code = 'SMK-F' AND level_code = 'A';

INSERT INTO rspu_master(rspu_id, category_code, category_path, positioning_label)
VALUES ('SMK-SRC', 'FS', '[]', 'MC'), ('SMK-TGT', 'FS', '[]', 'MC');
INSERT INTO rspu_variant(variant_id, rspu_id) VALUES ('SMK-V', 'SMK-SRC');
INSERT INTO rsku_supply(rsku_id, rspu_id, variant_id, factory_code,
    supplier_configuration_key, supplier_configuration_normalizer_version, deleted_at)
VALUES ('SMK-R1', 'SMK-SRC', 'SMK-V', 'SMK-F', 'BASE', 'v1', NULL),
       ('SMK-R2', 'SMK-SRC', 'SMK-V', 'SMK-F', 'CODE:PREMIUM', 'v1', NULL),
       ('SMK-R3', 'SMK-SRC', 'SMK-V', 'SMK-F', 'BASE', 'v1', now());
INSERT INTO image_assets(image_id, rspu_id, variant_id, image_type, storage_path, deleted_at)
VALUES ('SMK-I1', 'SMK-SRC', 'SMK-V', 'detail', 'smoke/1', NULL),
       ('SMK-I2', 'SMK-SRC', 'SMK-V', 'detail', 'smoke/2', now());
DO $$
BEGIN
    BEGIN
        UPDATE rspu_variant SET rspu_id = 'SMK-TGT' WHERE variant_id = 'SMK-V';
        RAISE EXCEPTION 'FAIL: 默认即时归属检查未生效';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;
    -- 即使延迟，迁移漏掉子行也必须在恢复即时检查时失败且整体回滚。
    BEGIN
        SET CONSTRAINTS fk_rsku_rspu_variant, fk_image_assets_rspu_variant DEFERRED;
        UPDATE rspu_variant SET rspu_id = 'SMK-TGT' WHERE variant_id = 'SMK-V';
        SET CONSTRAINTS fk_rsku_rspu_variant, fk_image_assets_rspu_variant IMMEDIATE;
        RAISE EXCEPTION 'FAIL: 不完整迁移未被拦截';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;
    IF NOT EXISTS (SELECT 1 FROM rspu_variant WHERE variant_id = 'SMK-V' AND rspu_id = 'SMK-SRC') THEN
        RAISE EXCEPTION 'FAIL: 失败迁移未回滚';
    END IF;
END $$;

SET CONSTRAINTS fk_rsku_rspu_variant, fk_image_assets_rspu_variant DEFERRED;
UPDATE rspu_variant SET rspu_id = 'SMK-TGT' WHERE variant_id = 'SMK-V';
UPDATE rsku_supply SET rspu_id = 'SMK-TGT' WHERE rspu_id = 'SMK-SRC' AND deleted_at IS NULL;
UPDATE image_assets SET rspu_id = 'SMK-TGT' WHERE rspu_id = 'SMK-SRC' AND deleted_at IS NULL;
-- 与合并 Mapper 一致，软删子行不能遗漏。
UPDATE rsku_supply SET rspu_id = 'SMK-TGT'
WHERE rspu_id = 'SMK-SRC' AND variant_id = 'SMK-V' AND deleted_at IS NOT NULL;
UPDATE image_assets SET rspu_id = 'SMK-TGT'
WHERE rspu_id = 'SMK-SRC' AND variant_id = 'SMK-V' AND deleted_at IS NOT NULL;
SET CONSTRAINTS fk_rsku_rspu_variant, fk_image_assets_rspu_variant IMMEDIATE;
DO $$
BEGIN
    IF (SELECT count(*) FROM rsku_supply WHERE rspu_id = 'SMK-TGT' AND deleted_at IS NULL) <> 2
       OR (SELECT count(*) FROM rsku_supply WHERE rspu_id = 'SMK-TGT') <> 3
       OR (SELECT count(*) FROM image_assets WHERE rspu_id = 'SMK-TGT') <> 2 THEN
        RAISE EXCEPTION 'FAIL: 供应配置或归档引用迁移丢失';
    END IF;
    RAISE NOTICE 'SMOKE OK: Agent 认领、主等级切换、变体归属迁移及失败回滚';
END $$;
ROLLBACK;
