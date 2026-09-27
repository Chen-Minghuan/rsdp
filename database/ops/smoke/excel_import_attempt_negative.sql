-- P0-F Import Attempt 执行内核（Flyway V14 合并迁移 Attempt 段）CHECK / NOT NULL / FK / 唯一约束负例冒烟
-- 设计依据：v3.3 设计文档 §49 / §49.2 / §50.3
--
-- 用法（只允许在临时库/冒烟库执行，全程单事务，结束 ROLLBACK 不留痕迹）：
--   psql -v ON_ERROR_STOP=1 -d <temp_db> -f database/ops/smoke/excel_import_attempt_negative.sql
-- 任何一处「预期被拒」的写入若意外成功，脚本即以 EXCEPTION 中止；全部通过输出 SMOKE OK。

BEGIN;

-- ------------------------------------------------------------ 夹具（最小合法行）
INSERT INTO excel_import_batch (batch_id, file_name)
VALUES ('SMK-B25', 'v25-smoke.xlsx');
INSERT INTO excel_import_preview_revision (batch_id, preview_revision, source_sheet_index,
                                           mapping_hash, normalizer_version, snapshot_hash)
VALUES ('SMK-B25', 1, 0, 'smk-mapping-hash', 'smk-normalizer', 'smk-snapshot-hash');
INSERT INTO excel_import_product_group (group_id, batch_id, preview_revision, product_group_key,
                                        normalized_payload, payload_hash, normalizer_version, mapping_hash)
VALUES ('SMK-G1', 'SMK-B25', 1, 'smk-key-1', '{}'::jsonb, 'smk-payload-hash', 'smk-normalizer', 'smk-mapping-hash');

DO $$
BEGIN
    -- -------------------------------------------------------- excel_import_attempt

    -- 1. NOT NULL：policy_payload
    BEGIN
        INSERT INTO excel_import_attempt (attempt_id, batch_id, preview_revision, policy_payload,
                                          policy_hash, status)
        VALUES ('SMK-A01', 'SMK-B25', 1, NULL, 'h', 'pending');
        RAISE EXCEPTION 'FAIL: attempt.policy_payload NOT NULL 未拦截';
    EXCEPTION WHEN not_null_violation THEN
    END;

    -- 2. NOT NULL：batch_id
    BEGIN
        INSERT INTO excel_import_attempt (attempt_id, batch_id, preview_revision, policy_payload,
                                          policy_hash, status)
        VALUES ('SMK-A02', NULL, 1, '{}'::jsonb, 'h', 'pending');
        RAISE EXCEPTION 'FAIL: attempt.batch_id NOT NULL 未拦截';
    EXCEPTION WHEN not_null_violation THEN
    END;

    -- 3. ck_import_attempt_status：非法状态
    BEGIN
        INSERT INTO excel_import_attempt (attempt_id, batch_id, preview_revision, policy_payload,
                                          policy_hash, status)
        VALUES ('SMK-A03', 'SMK-B25', 1, '{}'::jsonb, 'h', 'bogus');
        RAISE EXCEPTION 'FAIL: ck_import_attempt_status 未拦截';
    EXCEPTION WHEN check_violation THEN
    END;

    -- 4. ck_import_attempt_outcome：done 但 outcome 为 NULL
    BEGIN
        INSERT INTO excel_import_attempt (attempt_id, batch_id, preview_revision, policy_payload,
                                          policy_hash, status, outcome)
        VALUES ('SMK-A04', 'SMK-B25', 1, '{}'::jsonb, 'h', 'done', NULL);
        RAISE EXCEPTION 'FAIL: done 无 outcome 未被 ck_import_attempt_outcome 拦截';
    EXCEPTION WHEN check_violation THEN
    END;

    -- 5. ck_import_attempt_outcome：非 done 携带 outcome
    BEGIN
        INSERT INTO excel_import_attempt (attempt_id, batch_id, preview_revision, policy_payload,
                                          policy_hash, status, outcome)
        VALUES ('SMK-A05', 'SMK-B25', 1, '{}'::jsonb, 'h', 'pending', 'success');
        RAISE EXCEPTION 'FAIL: 非 done 携带 outcome 未被 ck_import_attempt_outcome 拦截';
    EXCEPTION WHEN check_violation THEN
    END;

    -- 6. ck_import_attempt_outcome：done + 非法 outcome 枚举
    BEGIN
        INSERT INTO excel_import_attempt (attempt_id, batch_id, preview_revision, policy_payload,
                                          policy_hash, status, outcome)
        VALUES ('SMK-A06', 'SMK-B25', 1, '{}'::jsonb, 'h', 'done', 'bogus');
        RAISE EXCEPTION 'FAIL: 非法 outcome 枚举未被拦截';
    EXCEPTION WHEN check_violation THEN
    END;

    -- 7. ck_import_attempt_policy_payload：正文必须是 JSON object
    BEGIN
        INSERT INTO excel_import_attempt (attempt_id, batch_id, preview_revision, policy_payload,
                                          policy_hash, status)
        VALUES ('SMK-A07', 'SMK-B25', 1, '[]'::jsonb, 'h', 'pending');
        RAISE EXCEPTION 'FAIL: 非 object policy_payload 未被拦截';
    EXCEPTION WHEN check_violation THEN
    END;

    -- 8. ck_import_attempt_retry_count：负数
    BEGIN
        INSERT INTO excel_import_attempt (attempt_id, batch_id, preview_revision, policy_payload,
                                          policy_hash, status, retry_count)
        VALUES ('SMK-A08', 'SMK-B25', 1, '{}'::jsonb, 'h', 'pending', -1);
        RAISE EXCEPTION 'FAIL: 负 retry_count 未被拦截';
    EXCEPTION WHEN check_violation THEN
    END;

    -- 9. fk_import_attempt_revision：Revision 不存在
    BEGIN
        INSERT INTO excel_import_attempt (attempt_id, batch_id, preview_revision, policy_payload,
                                          policy_hash, status)
        VALUES ('SMK-A09', 'SMK-B25', 999, '{}'::jsonb, 'h', 'pending');
        RAISE EXCEPTION 'FAIL: fk_import_attempt_revision 未拦截';
    EXCEPTION WHEN foreign_key_violation THEN
    END;

    -- 10. uk_import_attempt：同 (batch, revision, policy_hash_version, policy_hash) 只能一个 Attempt（§50）
    INSERT INTO excel_import_attempt (attempt_id, batch_id, preview_revision, policy_payload,
                                      policy_hash, status)
    VALUES ('SMK-A10', 'SMK-B25', 1, '{}'::jsonb, 'dup-hash', 'pending');
    BEGIN
        INSERT INTO excel_import_attempt (attempt_id, batch_id, preview_revision, policy_payload,
                                          policy_hash, status)
        VALUES ('SMK-A10B', 'SMK-B25', 1, '{}'::jsonb, 'dup-hash', 'pending');
        RAISE EXCEPTION 'FAIL: uk_import_attempt 未拦截重复逻辑 Attempt';
    EXCEPTION WHEN unique_violation THEN
    END;

    -- 11. 正例：done + 合法 outcome 允许
    INSERT INTO excel_import_attempt (attempt_id, batch_id, preview_revision, policy_payload,
                                      policy_hash, status, outcome)
    VALUES ('SMK-A11', 'SMK-B25', 1, '{}'::jsonb, 'done-hash', 'done', 'partial_success');

    -- -------------------------------------------------------- excel_import_attempt_item

    -- 12. ck_import_attempt_item_status：非法状态
    BEGIN
        INSERT INTO excel_import_attempt_item (attempt_id, batch_id, preview_revision, group_id, status)
        VALUES ('SMK-A11', 'SMK-B25', 1, 'SMK-G1', 'bogus');
        RAISE EXCEPTION 'FAIL: ck_import_attempt_item_status 未拦截';
    EXCEPTION WHEN check_violation THEN
    END;

    -- 13. ck_import_attempt_item_counts：负计数
    BEGIN
        INSERT INTO excel_import_attempt_item (attempt_id, batch_id, preview_revision, group_id,
                                               status, quote_failed_count)
        VALUES ('SMK-A11', 'SMK-B25', 1, 'SMK-G1', 'pending', -1);
        RAISE EXCEPTION 'FAIL: ck_import_attempt_item_counts 未拦截';
    EXCEPTION WHEN check_violation THEN
    END;

    -- 14. fk_import_attempt_item_attempt：Attempt 不存在
    BEGIN
        INSERT INTO excel_import_attempt_item (attempt_id, batch_id, preview_revision, group_id)
        VALUES ('SMK-A-NOPE', 'SMK-B25', 1, 'SMK-G1');
        RAISE EXCEPTION 'FAIL: fk_import_attempt_item_attempt 未拦截';
    EXCEPTION WHEN foreign_key_violation THEN
    END;

    -- 15. fk_import_attempt_item_group：ProductGroup 不存在
    BEGIN
        INSERT INTO excel_import_attempt_item (attempt_id, batch_id, preview_revision, group_id)
        VALUES ('SMK-A11', 'SMK-B25', 1, 'SMK-G-NOPE');
        RAISE EXCEPTION 'FAIL: fk_import_attempt_item_group 未拦截';
    EXCEPTION WHEN foreign_key_violation THEN
    END;

    -- -------------------------------------------------------- 清理顺序（§49 GC）

    -- 16. fk_import_attempt_revision ON DELETE RESTRICT：有 Attempt 的 Revision 禁止删除
    BEGIN
        DELETE FROM excel_import_preview_revision WHERE batch_id = 'SMK-B25' AND preview_revision = 1;
        RAISE EXCEPTION 'FAIL: Revision ON DELETE RESTRICT 未拦截';
    EXCEPTION WHEN foreign_key_violation THEN
    END;

    -- 17. 正例：先删 Attempt（Item 级联）再删 Revision 成功
    INSERT INTO excel_import_attempt_item (attempt_id, batch_id, preview_revision, group_id)
    VALUES ('SMK-A11', 'SMK-B25', 1, 'SMK-G1');
    DELETE FROM excel_import_attempt WHERE batch_id = 'SMK-B25';
    IF EXISTS (SELECT 1 FROM excel_import_attempt_item WHERE batch_id = 'SMK-B25') THEN
        RAISE EXCEPTION 'FAIL: Attempt 删除后 Item 未级联清理';
    END IF;
    DELETE FROM excel_import_product_group WHERE batch_id = 'SMK-B25';
    DELETE FROM excel_import_preview_revision WHERE batch_id = 'SMK-B25';

    -- -------------------------------------------------------- async_task 幂等键（§50.3）

    -- 18. uk_async_task_type_idempotency：同 (task_type, idempotency_key) 唯一
    INSERT INTO async_task (task_id, task_type, idempotency_key)
    VALUES ('SMK-T1', 'excel_import', 'excel-import-attempt:SMK-A1');
    BEGIN
        INSERT INTO async_task (task_id, task_type, idempotency_key)
        VALUES ('SMK-T2', 'excel_import', 'excel-import-attempt:SMK-A1');
        RAISE EXCEPTION 'FAIL: uk_async_task_type_idempotency 未拦截';
    EXCEPTION WHEN unique_violation THEN
    END;

    -- 19. 正例：idempotency_key 为 NULL 不纳入唯一约束（重复 enqueue 兼容旧任务）
    INSERT INTO async_task (task_id, task_type) VALUES ('SMK-T3', 'excel_import');
    INSERT INTO async_task (task_id, task_type) VALUES ('SMK-T4', 'excel_import');

    RAISE NOTICE 'SMOKE OK: excel_import_attempt 负例全部按预期拒绝';
END $$;

ROLLBACK;
