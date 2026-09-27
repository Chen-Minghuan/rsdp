-- ============================================================
-- V17：供应链商品数据与 Excel 导入改造 v3.3 + 数据正确性约束 + 合并归属外键
-- ============================================================
-- 本脚本由原 V14~V25 十一个增量脚本合并而来（发布前收敛，原脚本均未进入任何
-- 已发布基线）。段标题保留原版本号映射；其中原 V15 的 supplier_identity_governance_item
-- 治理队列表、原 V19 的 import_data_issue Issue 底表、原 V20 的 variant_fingerprint 表与
-- 原 V23 的 import_entity_lineage 血缘表已于 2026-09-24 评审后摘除（治理/血缘为报告制
-- 不持久化；品类门禁改为 confirm 时直接校验快照负载；判重维持 uk_variant_attrs 唯一索引
-- 口径）。设计依据统一为
-- 《docs/RSDP_供应链商品数据与Excel导入改造_开发设计文档_v3.3_完整收口版.md》。
--
-- 同步约定：database/schema/（02_product / 04_image_ai / 05_excel_import / 12_system）
-- 与 database/ops/reset_db.sql 已同步修改。
--
-- 全部语句幂等可重复执行（IF NOT EXISTS / IF EXISTS / pg_constraint 守卫），
-- 供已部分应用过原 V14~V20 的开发库平滑过渡。
-- ============================================================


-- ============================================================
-- 段 1（原 V14）：P0-A Supplier Identity：supplier_product_ref 建表
-- ============================================================
CREATE TABLE IF NOT EXISTS supplier_product_ref (
    ref_id BIGSERIAL PRIMARY KEY,

    rspu_id VARCHAR(64) NOT NULL,
    factory_code VARCHAR(16) NOT NULL,

    source_namespace VARCHAR(64) NOT NULL DEFAULT 'default',
    first_seen_source_system VARCHAR(64),
    last_seen_source_system VARCHAR(64),

    source_product_code VARCHAR(128) NOT NULL,
    normalized_source_product_code VARCHAR(128) NOT NULL,
    code_normalizer_version VARCHAR(64) NOT NULL,
    source_product_name VARCHAR(256),

    first_import_batch_id VARCHAR(64),
    last_import_batch_id VARCHAR(64),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_supplier_product_ref_rspu
        FOREIGN KEY (rspu_id)
        REFERENCES rspu_master(rspu_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_supplier_product_ref_factory
        FOREIGN KEY (factory_code)
        REFERENCES factory_master(factory_code)
        ON DELETE RESTRICT,

    CONSTRAINT fk_supplier_product_ref_first_batch
        FOREIGN KEY (first_import_batch_id)
        REFERENCES excel_import_batch(batch_id)
        ON DELETE SET NULL,

    CONSTRAINT fk_supplier_product_ref_last_batch
        FOREIGN KEY (last_import_batch_id)
        REFERENCES excel_import_batch(batch_id)
        ON DELETE SET NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_supplier_product_ref
ON supplier_product_ref (
    factory_code,
    source_namespace,
    normalized_source_product_code
);

CREATE INDEX IF NOT EXISTS idx_supplier_product_ref_rspu
ON supplier_product_ref(rspu_id);

CREATE INDEX IF NOT EXISTS idx_supplier_product_ref_factory
ON supplier_product_ref(factory_code);

COMMENT ON TABLE supplier_product_ref IS '供应商商品引用：工厂命名空间内商品编码到 RSPU 的历史可追溯身份映射（v3.3 §6）';
COMMENT ON COLUMN supplier_product_ref.source_namespace IS '供应商编码命名空间，参与 Identity；本期默认 default，禁止用传输渠道（excel/manual/erp-api）充当 namespace';
COMMENT ON COLUMN supplier_product_ref.first_seen_source_system IS '首次进入系统的来源系统（仅 provenance，不参与 Identity）';
COMMENT ON COLUMN supplier_product_ref.last_seen_source_system IS '最近进入系统的来源系统（仅 provenance，不参与 Identity）';
COMMENT ON COLUMN supplier_product_ref.normalized_source_product_code IS '归一化供应商商品编码，参与唯一键 (factory_code, source_namespace, normalized_source_product_code)';
COMMENT ON COLUMN supplier_product_ref.code_normalizer_version IS '归一化算法版本（v3.3 §7；版本演进须受控迁移，原子更新 normalized_source_product_code + code_normalizer_version）';
-- ============================================================
-- 段 2（原 V16）：P0-A Contract：DROP uk_rspu_external_code
-- ============================================================
DROP INDEX IF EXISTS uk_rspu_external_code;


-- ============================================================
-- 段 3（原 V17）：P0-B1 Immutable Preview Snapshot / Revision / 乐观锁底座
-- ============================================================
-- ============================================================
-- 1. Snapshot Header：excel_import_preview_revision（§20.1）
-- ============================================================
CREATE TABLE IF NOT EXISTS excel_import_preview_revision (
    batch_id VARCHAR(64) NOT NULL,
    preview_revision BIGINT NOT NULL,

    source_sheet_index INTEGER NOT NULL,
    source_sheet_name VARCHAR(256),

    category_mode VARCHAR(16),
    category_hint VARCHAR(16),
    candidate_category_codes JSONB NOT NULL DEFAULT '[]'::jsonb,

    mapping_hash VARCHAR(64) NOT NULL,
    normalizer_version VARCHAR(64) NOT NULL,

    hash_version SMALLINT NOT NULL DEFAULT 1,
    snapshot_hash VARCHAR(64) NOT NULL,

    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_import_preview_revision
        PRIMARY KEY(batch_id, preview_revision),

    CONSTRAINT fk_import_preview_revision_batch
        FOREIGN KEY(batch_id)
        REFERENCES excel_import_batch(batch_id)
        ON DELETE CASCADE,

    CONSTRAINT ck_import_preview_category_mode
        CHECK (category_mode IS NULL OR category_mode IN ('SINGLE', 'MIXED')),

    CONSTRAINT ck_import_preview_category_candidates
        CHECK (jsonb_typeof(candidate_category_codes) = 'array'),

    CONSTRAINT ck_import_preview_hash_version
        CHECK (hash_version > 0),

    CONSTRAINT ck_import_preview_category_policy
        CHECK (
            category_mode IS NULL
            OR (
                category_mode = 'SINGLE'
                AND category_hint IS NOT NULL
                AND jsonb_array_length(candidate_category_codes) = 0
            )
            OR (
                category_mode = 'MIXED'
                AND category_hint IS NULL
                AND jsonb_array_length(candidate_category_codes) >= 2
            )
        )
);

COMMENT ON TABLE excel_import_preview_revision IS 'Excel 导入 Preview Snapshot Header（v3.3 §20.1）：per-sheet（batch）边界的不可变 Revision 元数据，固化品类策略 / mapping_hash / normalizer_version / snapshot_hash；只允许 INSERT 新 Revision';
COMMENT ON COLUMN excel_import_preview_revision.preview_revision IS 'Revision 号，batch 内单调递增（§46：任何 Preview 编辑 +1），与 batch_id 复合主键';
COMMENT ON COLUMN excel_import_preview_revision.snapshot_hash IS '对 §47.1.3 SnapshotHashMaterial 的 SHA-256；Material 排除自身与 created_at/created_by，包含按 product_group_key 排序的全部 payload_hash';
COMMENT ON COLUMN excel_import_preview_revision.hash_version IS 'Canonical Hash 协议版本（§47.1.6），协议变更必须升级版本，不得静默改变历史 Hash';

-- ============================================================
-- 2. Immutable 产品聚合输入：excel_import_product_group（§21）
-- ============================================================
CREATE TABLE IF NOT EXISTS excel_import_product_group (
    group_id VARCHAR(64) PRIMARY KEY,

    batch_id VARCHAR(64) NOT NULL,
    preview_revision BIGINT NOT NULL,

    product_group_key VARCHAR(256) NOT NULL,

    normalized_payload JSONB NOT NULL,

    payload_hash_version SMALLINT NOT NULL DEFAULT 1,
    payload_hash VARCHAR(64) NOT NULL,

    normalizer_version VARCHAR(64) NOT NULL,
    mapping_hash VARCHAR(64) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_import_product_group_revision
        FOREIGN KEY(batch_id, preview_revision)
        REFERENCES excel_import_preview_revision(batch_id, preview_revision)
        ON DELETE CASCADE,

    CONSTRAINT uk_import_product_group_revision_key
        UNIQUE(batch_id, preview_revision, product_group_key),

    CONSTRAINT uk_import_product_group_revision_id
        UNIQUE(batch_id, preview_revision, group_id),

    CONSTRAINT ck_import_product_group_payload_hash_version
        CHECK(payload_hash_version > 0)
);

CREATE INDEX IF NOT EXISTS idx_import_product_group_batch_revision
ON excel_import_product_group(batch_id, preview_revision);

COMMENT ON TABLE excel_import_product_group IS 'Excel 导入 Preview 产品聚合输入（v3.3 §21）：某 Revision 下「这个逻辑产品被确认成什么标准化数据」的不可变输入；执行期状态/计数/Checkpoint 归 excel_import_attempt_item（P0-F），本表禁止 runtime 字段';
COMMENT ON COLUMN excel_import_product_group.normalized_payload IS '非敏感/普通级别的标准化数据；采购成本等敏感值只允许以 Sensitive Ref 出现（§24.2 excel_import_sensitive_value，B2/B3 落地）';
COMMENT ON COLUMN excel_import_product_group.payload_hash IS '对 §47.1.2 PayloadHashMaterial 的 SHA-256：payload_hash_version + canonical(payload) + 按 value_path 排序的敏感值 HMAC 条目';

-- ============================================================
-- 3. excel_import_batch：Revision 指针与 Confirm / GC 元数据（§45）
-- ============================================================
ALTER TABLE excel_import_batch
    ADD COLUMN IF NOT EXISTS preview_revision BIGINT,
    ADD COLUMN IF NOT EXISTS mapping_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS normalizer_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS confirmed_revision BIGINT,
    ADD COLUMN IF NOT EXISTS confirmed_by VARCHAR(64),
    ADD COLUMN IF NOT EXISTS confirmed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS import_policy JSONB,
    ADD COLUMN IF NOT EXISTS retention_hold BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS snapshot_archive_summary JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN excel_import_batch.preview_revision IS '当前最新 Preview Revision 指针（§45）；真正的不可变策略/版本/哈希在 excel_import_preview_revision';
COMMENT ON COLUMN excel_import_batch.confirmed_revision IS '已确认 Revision 指针（§45）；Confirm 语义在 P0-B3 收口';
COMMENT ON COLUMN excel_import_batch.import_policy IS '导入 Policy 的 UI 草稿（§45/§47.1.5）；执行期不可变 policy_payload/policy_hash 归 excel_import_attempt（P0-F）';
COMMENT ON COLUMN excel_import_batch.retention_hold IS '审计/争议保留标记（§44）：true 时 GC 必须跳过整个 batch，解除须记审计日志';
COMMENT ON COLUMN excel_import_batch.snapshot_archive_summary IS '快照超保留期后的非敏感审计摘要（§44/§45），只能由受限 GC 函数写入；活跃指针为 NULL 且摘要存在时 Confirm/Import 返回 410 snapshot_expired';

-- 指针完整性：有值时必须引用同一 Batch 下真实存在的 Snapshot Revision（§45）
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_excel_import_batch_current_revision' AND conrelid = 'excel_import_batch'::regclass
    ) THEN
        ALTER TABLE excel_import_batch
            ADD CONSTRAINT fk_excel_import_batch_current_revision FOREIGN KEY(batch_id, preview_revision)
    REFERENCES excel_import_preview_revision(batch_id, preview_revision);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_excel_import_batch_confirmed_revision' AND conrelid = 'excel_import_batch'::regclass
    ) THEN
        ALTER TABLE excel_import_batch
            ADD CONSTRAINT fk_excel_import_batch_confirmed_revision FOREIGN KEY(batch_id, confirmed_revision)
    REFERENCES excel_import_preview_revision(batch_id, preview_revision);
    END IF;
END $$;


-- ============================================================
-- 段 4（原 V18）：P0-B2 敏感 Preview 值表
-- ============================================================
CREATE TABLE IF NOT EXISTS excel_import_sensitive_value (
    sensitive_value_id VARCHAR(64) PRIMARY KEY,

    batch_id VARCHAR(64) NOT NULL,
    preview_revision BIGINT NOT NULL,

    group_id VARCHAR(64),

    value_path VARCHAR(512) NOT NULL,

    ciphertext TEXT NOT NULL,
    encryption_key_version INTEGER NOT NULL,

    value_hmac VARCHAR(64) NOT NULL,
    hmac_key_version INTEGER NOT NULL,

    data_classification VARCHAR(32) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    retention_until TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_import_sensitive_revision
        FOREIGN KEY(batch_id, preview_revision)
        REFERENCES excel_import_preview_revision(batch_id, preview_revision)
        ON DELETE CASCADE,

    CONSTRAINT fk_import_sensitive_group_revision
        FOREIGN KEY(batch_id, preview_revision, group_id)
        REFERENCES excel_import_product_group(batch_id, preview_revision, group_id)
        ON DELETE CASCADE,

    CONSTRAINT ck_import_sensitive_owner
        CHECK (group_id IS NOT NULL),

    CONSTRAINT ck_import_sensitive_key_version
        CHECK (
            encryption_key_version > 0
            AND hmac_key_version > 0
        )
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_import_sensitive_group_path
ON excel_import_sensitive_value(batch_id, preview_revision, group_id, value_path)
WHERE group_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_import_sensitive_revision
ON excel_import_sensitive_value(batch_id, preview_revision);

COMMENT ON TABLE excel_import_sensitive_value IS '敏感 Preview 值（v3.3 §24.2）：采购成本等敏感值的密文 + HMAC 存储，normalized_payload 中只出现 Sensitive Ref';
COMMENT ON COLUMN excel_import_sensitive_value.value_path IS '敏感值在 payload 中的路径（PayloadHashMaterial 敏感值条目的排序键，§47.1.2）';
COMMENT ON COLUMN excel_import_sensitive_value.value_hmac IS 'HMAC-SHA-256(secret, canonicalSensitiveValue)（§47.1.7）：同一业务值重新加密不造成 Hash 漂移';
COMMENT ON COLUMN excel_import_sensitive_value.retention_until IS '保留期截止（§44）；超期由受限 GC 函数随整个未 hold batch 的快照一并清理';
-- ============================================================
-- 段 5（原 V20）：P0-C rspu_variant 组合候选键（支撑组合 FK）
-- ============================================================
-- ============================================================
-- rspu_variant 组合候选键（§15：支撑组合 FK，数据库直接阻止
--    「RSPU-A + 属于 RSPU-B 的 Variant」被错误写入 RSKU）
--    （原 variant_fingerprint 身份索引表已于 2026-09-24 评审后摘除：v1 仅镜像
--     uk_variant_attrs 判重口径，v2 治理能力未启用，判重维持唯一索引语义）
-- ============================================================
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uk_rspu_variant_rspu_variant' AND conrelid = 'rspu_variant'::regclass
    ) THEN
        ALTER TABLE rspu_variant
            ADD CONSTRAINT uk_rspu_variant_rspu_variant UNIQUE (rspu_id, variant_id);
    END IF;
END $$;


-- ============================================================
-- 段 6（原 V22）：P0-D RSKU Identity 收口（Supplier Configuration）
-- ============================================================
-- ============================================================
-- 1. §34 RSKU 字段增强（supplier_configuration_key /
--    supplier_configuration_normalizer_version 先允许 NULL，第 5 步回填后 SET NOT NULL；
--    不设 DEFAULT 'BASE'，新写入由 Java 侧显式赋值）
-- ============================================================
ALTER TABLE rsku_supply
    ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'active',

    ADD COLUMN IF NOT EXISTS currency_code VARCHAR(8) NOT NULL DEFAULT 'CNY',
    ADD COLUMN IF NOT EXISTS tax_included BOOLEAN,
    ADD COLUMN IF NOT EXISTS price_unit VARCHAR(32),

    ADD COLUMN IF NOT EXISTS moq_unit VARCHAR(32),

    ADD COLUMN IF NOT EXISTS quote_date DATE,
    ADD COLUMN IF NOT EXISTS effective_from DATE,
    ADD COLUMN IF NOT EXISTS effective_to DATE,

    ADD COLUMN IF NOT EXISTS supplier_material_grade_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS supplier_material_grade_text VARCHAR(128),
    ADD COLUMN IF NOT EXISTS supplier_configuration_key VARCHAR(192),
    ADD COLUMN IF NOT EXISTS supplier_configuration_normalizer_version VARCHAR(64),

    ADD COLUMN IF NOT EXISTS source_batch_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS source_row_id BIGINT,

    ADD COLUMN IF NOT EXISTS quote_version INTEGER NOT NULL DEFAULT 1;

COMMENT ON COLUMN rsku_supply.status IS 'RSKU 状态机（§36）：active/inactive/expired/suspended/discontinued';
COMMENT ON COLUMN rsku_supply.supplier_configuration_key IS '工厂内稳定供应配置身份（§18.2）：CODE:<归一码> / TEXT:<归一原文> / BASE；RSKU Identity 三元组之一';
COMMENT ON COLUMN rsku_supply.supplier_configuration_normalizer_version IS '生成 supplier_configuration_key 的归一规则集版本（§18.2/§18.3）；升级须先 Dry Run 治理';
COMMENT ON COLUMN rsku_supply.quote_version IS '报价乐观并发版本（§37）：报价更新 quote_version+1，冲突返回 quote_version_conflict';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_rsku_moq_positive' AND conrelid = 'rsku_supply'::regclass
    ) THEN
        ALTER TABLE rsku_supply
            ADD CONSTRAINT ck_rsku_moq_positive CHECK (moq IS NULL OR moq > 0);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_rsku_effective_date' AND conrelid = 'rsku_supply'::regclass
    ) THEN
        ALTER TABLE rsku_supply
            ADD CONSTRAINT ck_rsku_effective_date CHECK (
        effective_from IS NULL
        OR effective_to IS NULL
        OR effective_to >= effective_from
    );
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_rsku_quote_version_positive' AND conrelid = 'rsku_supply'::regclass
    ) THEN
        ALTER TABLE rsku_supply
            ADD CONSTRAINT ck_rsku_quote_version_positive CHECK (quote_version > 0);
    END IF;
END $$;

-- 状态机五值枚举以§36 为准；采用「显式拒绝 NULL」形态，不依赖 PostgreSQL CHECK 的 UNKNOWN 语义
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_rsku_status' AND conrelid = 'rsku_supply'::regclass
    ) THEN
        ALTER TABLE rsku_supply
            ADD CONSTRAINT ck_rsku_status CHECK (
        status IS NOT NULL
        AND status IN ('active', 'inactive', 'expired', 'suspended', 'discontinued')
    );
    END IF;
END $$;

-- ============================================================
-- 2. §34.1 字段级人工修正标记（RSPU / Variant / RSKU 三层）
--    Key 使用稳定 JSON Pointer，Value 只保存治理元数据，不复制敏感业务值
-- ============================================================
ALTER TABLE rspu_master
    ADD COLUMN IF NOT EXISTS manual_override_fields JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE rspu_variant
    ADD COLUMN IF NOT EXISTS manual_override_fields JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE rsku_supply
    ADD COLUMN IF NOT EXISTS manual_override_fields JSONB NOT NULL DEFAULT '{}'::jsonb;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_rspu_manual_override_fields_object' AND conrelid = 'rspu_master'::regclass
    ) THEN
        ALTER TABLE rspu_master
            ADD CONSTRAINT ck_rspu_manual_override_fields_object CHECK (jsonb_typeof(manual_override_fields) = 'object');
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_variant_manual_override_fields_object' AND conrelid = 'rspu_variant'::regclass
    ) THEN
        ALTER TABLE rspu_variant
            ADD CONSTRAINT ck_variant_manual_override_fields_object CHECK (jsonb_typeof(manual_override_fields) = 'object');
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_rsku_manual_override_fields_object' AND conrelid = 'rsku_supply'::regclass
    ) THEN
        ALTER TABLE rsku_supply
            ADD CONSTRAINT ck_rsku_manual_override_fields_object CHECK (jsonb_typeof(manual_override_fields) = 'object');
    END IF;
END $$;

COMMENT ON COLUMN rspu_master.manual_override_fields IS '字段级人工修正权威标记（§34.1）：JSON Pointer → {active, correctedBy, correctedAt, source}；Excel/AI/重试不得覆盖 active 字段';
COMMENT ON COLUMN rspu_variant.manual_override_fields IS '字段级人工修正权威标记（§34.1），同 rspu_master.manual_override_fields';
COMMENT ON COLUMN rsku_supply.manual_override_fields IS '字段级人工修正权威标记（§34.1），同 rspu_master.manual_override_fields';

-- ============================================================
-- 3. §33 variant_id 回填（Legacy Default Variant 兜底）+ 零 NULL 校验 + SET NOT NULL
-- ============================================================
DO $$
DECLARE
    r RECORD;
    legacy_variant_id VARCHAR(64);
    backfill_count INTEGER := 0;
    remaining_nulls INTEGER;
BEGIN
    FOR r IN SELECT DISTINCT rspu_id FROM rsku_supply WHERE variant_id IS NULL LOOP
        -- 复用同 RSPU 下「三属性 code+text 全空」的未软删 Variant；不存在才创建兜底变体
        SELECT rv.variant_id INTO legacy_variant_id
        FROM rspu_variant rv
        WHERE rv.rspu_id = r.rspu_id
          AND rv.deleted_at IS NULL
          AND rv.size_code IS NULL AND rv.size_text IS NULL
          AND rv.color_code IS NULL AND rv.color_text IS NULL
          AND rv.material_code IS NULL AND rv.material_text IS NULL
        ORDER BY rv.created_at, rv.variant_id
        LIMIT 1;

        IF legacy_variant_id IS NULL THEN
            legacy_variant_id := 'LGVAR-' || md5('rsku-legacy-default:' || r.rspu_id);
            INSERT INTO rspu_variant (variant_id, rspu_id, display_name, status, created_at, updated_at)
            VALUES (
                legacy_variant_id,
                r.rspu_id,
                '遗留默认变体（v3.3 迁移回填：历史 RSKU 无变体归属证据）',
                'active',
                NOW(),
                NOW()
            );
            RAISE NOTICE 'V14: 为 RSPU % 创建 Legacy Default Variant %', r.rspu_id, legacy_variant_id;
        END IF;

        UPDATE rsku_supply
        SET variant_id = legacy_variant_id
        WHERE rspu_id = r.rspu_id AND variant_id IS NULL;
        GET DIAGNOSTICS backfill_count = ROW_COUNT;
        RAISE NOTICE 'V14: RSPU % 回填 % 条 variant_id NULL 的 RSKU → %', r.rspu_id, backfill_count, legacy_variant_id;
    END LOOP;

    SELECT COUNT(*) INTO remaining_nulls FROM rsku_supply WHERE variant_id IS NULL;
    IF remaining_nulls > 0 THEN
        RAISE EXCEPTION 'V14: variant_id 回填后仍存在 % 条 NULL，迁移中断（§33 要求零 NULL）', remaining_nulls;
    END IF;
END $$;

ALTER TABLE rsku_supply ALTER COLUMN variant_id SET NOT NULL;

-- ============================================================
-- 4. §32.2 组合 FK：先断言存量 (rspu_id, variant_id) 与 rspu_variant 一致，
--    再移除仅依赖 variant_id 的旧孤立 FK，建立组合 FK
-- ============================================================
DO $$
DECLARE
    mismatch_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO mismatch_count
    FROM rsku_supply rs
    WHERE NOT EXISTS (
        SELECT 1 FROM rspu_variant rv
        WHERE rv.rspu_id = rs.rspu_id AND rv.variant_id = rs.variant_id
    );
    IF mismatch_count > 0 THEN
        RAISE EXCEPTION 'V14: 存在 % 条 RSKU 的 (rspu_id, variant_id) 与 rspu_variant 归属不一致，需先人工治理（§32.2）', mismatch_count;
    END IF;
END $$;

ALTER TABLE rsku_supply DROP CONSTRAINT IF EXISTS rsku_supply_variant_id_fkey;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_rsku_rspu_variant' AND conrelid = 'rsku_supply'::regclass
    ) THEN
        ALTER TABLE rsku_supply
            ADD CONSTRAINT fk_rsku_rspu_variant FOREIGN KEY (rspu_id, variant_id)
    REFERENCES rspu_variant(rspu_id, variant_id)
    ON DELETE RESTRICT;
    END IF;
END $$;

-- ============================================================
-- 5. §76 Supplier Configuration 回填：歧义断言 → 确定性 BASE 回填 → 零 NULL → SET NOT NULL
-- ============================================================
DO $$
DECLARE
    ambiguous_groups INTEGER;
BEGIN
    -- 同一 Variant + Factory 存在多条存活 RSKU = 配置拆分歧义（旧索引含 rspu_id，理论上
    -- 跨 RSPU 错挂时可能出现）；zero ambiguous configuration 前不创建新唯一索引（§76）
    SELECT COUNT(*) INTO ambiguous_groups
    FROM (
        SELECT variant_id, factory_code
        FROM rsku_supply
        WHERE deleted_at IS NULL
        GROUP BY variant_id, factory_code
        HAVING COUNT(*) > 1
    ) t;
    IF ambiguous_groups > 0 THEN
        RAISE EXCEPTION 'V14: 存在 % 组同一 Variant+Factory 的多条存活 RSKU（配置歧义），需先人工治理（§76）', ambiguous_groups;
    END IF;
END $$;

-- 存量逐行回填 BASE：旧 Identity 下同一 Variant+Factory 至多一条存活 RSKU（上一步已断言），
-- 无配置拆分证据，BASE 回填不压缩任何既有等级差异；含软删行一并回填以满足 NOT NULL
UPDATE rsku_supply
SET supplier_configuration_key = 'BASE',
    supplier_configuration_normalizer_version = '1'
WHERE supplier_configuration_key IS NULL;

DO $$
DECLARE
    remaining_nulls INTEGER;
BEGIN
    SELECT COUNT(*) INTO remaining_nulls
    FROM rsku_supply
    WHERE supplier_configuration_key IS NULL
       OR supplier_configuration_normalizer_version IS NULL;
    IF remaining_nulls > 0 THEN
        RAISE EXCEPTION 'V14: Supplier Configuration 回填后仍存在 % 条 NULL，迁移中断', remaining_nulls;
    END IF;
END $$;

ALTER TABLE rsku_supply ALTER COLUMN supplier_configuration_key SET NOT NULL;
ALTER TABLE rsku_supply ALTER COLUMN supplier_configuration_normalizer_version SET NOT NULL;

-- ============================================================
-- 6. §32.3 唯一约束切换：旧 Identity（RSPU+Variant+Factory）→
--    新 Identity（Variant+Factory+Supplier Configuration）
-- ============================================================
DROP INDEX IF EXISTS idx_rsku_unique;

CREATE UNIQUE INDEX IF NOT EXISTS uk_rsku_variant_factory_config_alive
ON rsku_supply(variant_id, factory_code, supplier_configuration_key)
WHERE deleted_at IS NULL;

COMMENT ON INDEX uk_rsku_variant_factory_config_alive IS 'RSKU Identity（§32.1）：同一 Factory + 同一 Variant + 同一 Supplier Configuration 只有一个当前有效 RSKU；A/AA/S 等横向等级以不同 supplier_configuration_key 并存、互不覆盖';


-- ============================================================
-- 段 7（原 V24）：清理被唯一索引左前缀覆盖的冗余索引
-- ============================================================
DROP INDEX IF EXISTS idx_supplier_product_ref_factory;
DROP INDEX IF EXISTS idx_import_product_group_batch_revision;
DROP INDEX IF EXISTS idx_variant_fingerprint_variant;


-- ============================================================
-- 段 8（原 V25）：P0-F Import Attempt 执行内核 + async_task 幂等键
-- ============================================================
-- ============================================================
-- 1. §49 excel_import_attempt
-- ============================================================
CREATE TABLE IF NOT EXISTS excel_import_attempt (
    attempt_id VARCHAR(64) PRIMARY KEY,

    batch_id VARCHAR(64) NOT NULL,
    preview_revision BIGINT NOT NULL,

    policy_schema_version SMALLINT NOT NULL DEFAULT 1,
    policy_payload JSONB NOT NULL,
    policy_hash_version SMALLINT NOT NULL DEFAULT 1,
    policy_hash VARCHAR(64) NOT NULL,

    status VARCHAR(16) NOT NULL,
    outcome VARCHAR(32),
    result_summary JSONB NOT NULL DEFAULT '{}'::jsonb,

    task_id VARCHAR(64),

    retry_count INTEGER NOT NULL DEFAULT 0,

    lease_owner VARCHAR(128),
    last_heartbeat_at TIMESTAMPTZ,
    lease_expires_at TIMESTAMPTZ,

    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,

    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    error_code VARCHAR(64),
    error_message TEXT,

    CONSTRAINT fk_import_attempt_revision
        FOREIGN KEY(batch_id, preview_revision)
        REFERENCES excel_import_preview_revision(batch_id, preview_revision)
        ON DELETE RESTRICT,

    CONSTRAINT uk_import_attempt
        UNIQUE(batch_id, preview_revision, policy_hash_version, policy_hash),

    CONSTRAINT uk_import_attempt_revision_id
        UNIQUE(attempt_id, batch_id, preview_revision),

    CONSTRAINT ck_import_attempt_status
        CHECK(status IN ('pending', 'running', 'done', 'failed')),

    CONSTRAINT ck_import_attempt_retry_count
        CHECK(retry_count >= 0),

    CONSTRAINT ck_import_attempt_policy_schema_version
        CHECK(policy_schema_version > 0),

    CONSTRAINT ck_import_attempt_policy_payload
        CHECK(jsonb_typeof(policy_payload) = 'object'),

    CONSTRAINT ck_import_attempt_policy_hash_version
        CHECK(policy_hash_version > 0),

    CONSTRAINT ck_import_attempt_outcome
        CHECK(
            (status = 'done'
                AND outcome IS NOT NULL
                AND outcome IN ('success', 'partial_success', 'business_rejected'))
            OR (status <> 'done' AND outcome IS NULL)
        ),

    CONSTRAINT ck_import_attempt_result_summary
        CHECK(jsonb_typeof(result_summary) = 'object')
);

CREATE INDEX IF NOT EXISTS idx_import_attempt_running_lease
ON excel_import_attempt(status, lease_expires_at)
WHERE status = 'running';

COMMENT ON TABLE excel_import_attempt IS 'Import Attempt（v3.3 §49）：对某一 immutable Preview Revision 使用某一 Import Policy 的一次逻辑执行；policy_payload/policy_hash INSERT 后不可更改，Worker/Recovery 只读本表的 Policy Snapshot（excel_import_batch.import_policy 只是 UI 草稿）';
COMMENT ON COLUMN excel_import_attempt.policy_payload IS '默认值已完整物化的不可变 Policy 正文（§47.1.5 policy_schema_version=1 全字段契约）';
COMMENT ON COLUMN excel_import_attempt.policy_hash IS 'policy_hash_version + policy_schema_version + canonical(policy_payload) 的 SHA-256（§47.1.5）';
COMMENT ON COLUMN excel_import_attempt.outcome IS 'Attempt 业务终态（§49.2.3）：success / partial_success / business_rejected；仅 status=done 时非空（ck_import_attempt_outcome）';
COMMENT ON COLUMN excel_import_attempt.result_summary IS 'done 时同事务物化的结果摘要（§49.2.3）：各终态 Group 计数、Variant/Quote 成功失败计数与结果定位信息，done Replay 原样返回';
COMMENT ON COLUMN excel_import_attempt.lease_owner IS '当前执行 Worker 标识（§49.1）；running + lease_expires_at < now() 时 Recovery 可重新抢占';

-- ============================================================
-- 2. §49.2 excel_import_attempt_item（Runtime 结果与恢复 Checkpoint 唯一载体）
-- ============================================================
CREATE TABLE IF NOT EXISTS excel_import_attempt_item (
    attempt_id VARCHAR(64) NOT NULL,

    batch_id VARCHAR(64) NOT NULL,
    preview_revision BIGINT NOT NULL,
    group_id VARCHAR(64) NOT NULL,

    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    checkpoint VARCHAR(32),

    variant_success_count INTEGER NOT NULL DEFAULT 0,
    variant_failed_count INTEGER NOT NULL DEFAULT 0,
    quote_success_count INTEGER NOT NULL DEFAULT 0,
    quote_failed_count INTEGER NOT NULL DEFAULT 0,

    result_summary JSONB,

    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY(attempt_id, group_id),

    CONSTRAINT fk_import_attempt_item_attempt
        FOREIGN KEY(attempt_id, batch_id, preview_revision)
        REFERENCES excel_import_attempt(attempt_id, batch_id, preview_revision)
        ON DELETE CASCADE,

    CONSTRAINT fk_import_attempt_item_group
        FOREIGN KEY(batch_id, preview_revision, group_id)
        REFERENCES excel_import_product_group(batch_id, preview_revision, group_id)
        ON DELETE RESTRICT,

    CONSTRAINT ck_import_attempt_item_status
        CHECK(
            status IN (
                'pending',
                'running',
                'imported',
                'partial',
                'rejected',
                'failed',
                'skipped'
            )
        ),

    CONSTRAINT ck_import_attempt_item_counts
        CHECK(
            variant_success_count >= 0
            AND variant_failed_count >= 0
            AND quote_success_count >= 0
            AND quote_failed_count >= 0
        )
);

CREATE INDEX IF NOT EXISTS idx_import_attempt_item_status
ON excel_import_attempt_item(attempt_id, status);

CREATE INDEX IF NOT EXISTS idx_import_attempt_item_group
ON excel_import_attempt_item(batch_id, preview_revision, group_id);

COMMENT ON TABLE excel_import_attempt_item IS 'Import AttemptItem（v3.3 §49.2）：一次 Attempt 内一个 ProductGroup 的 Runtime 结果与恢复 Checkpoint；终态 imported/partial/rejected/skipped 与领域写入同一事务落地（§31/§50.2），failed 为可重试技术失败';
COMMENT ON COLUMN excel_import_attempt_item.checkpoint IS '当前 Group 的可恢复执行阶段（§49.2.2：validated/identity_resolved/persisting/committed）；「已完成」判定只依赖 status 终态';

-- ============================================================
-- 3. §50.3 async_task 副作用幂等键（重复 enqueue 返回既有任务）
-- ============================================================
ALTER TABLE async_task
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);

CREATE UNIQUE INDEX IF NOT EXISTS uk_async_task_type_idempotency
ON async_task(task_type, idempotency_key)
WHERE idempotency_key IS NOT NULL;

COMMENT ON COLUMN async_task.idempotency_key IS '副作用幂等键（v3.3 §50.3）：Worker 崩溃重试/重复 enqueue 不重复建任务；excel_import 任务键为 excel-import-attempt:<attemptId>';


-- 第二部分：数据正确性约束（原 V18，发布前合并）
-- ============================================================
-- V18：数据正确性收口（2026-09-24 评审）——归属一致组合外键 + 通配规则 NULL 判重 +
-- 主项部分唯一索引 + Agent 域内部外键 + 遗留表删除 + style_vector 退役 + 索引瘦身
-- 同步约定：database/schema/（01/02/03/07/09/10/13 + cross_domain_fk）与
-- database/ops/reset_db.sql 已同步修改；全部语句幂等可重复执行
-- ============================================================

-- ============================================================
-- 1. 归属一致组合外键：只保证「存在」的单列 FK 升级为「归属一致」组合 FK
-- ============================================================

-- 1a. RSKU 工厂↔仓库：工厂 A 不得使用工厂 B 的仓库
CREATE UNIQUE INDEX IF NOT EXISTS uk_factory_warehouse_factory_warehouse
    ON factory_warehouse(factory_code, warehouse_id);
ALTER TABLE rsku_supply DROP CONSTRAINT IF EXISTS fk_rsku_supply_warehouse;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_rsku_supply_factory_warehouse'
          AND conrelid = 'rsku_supply'::regclass) THEN
        ALTER TABLE rsku_supply
            ADD CONSTRAINT fk_rsku_supply_factory_warehouse
            FOREIGN KEY (factory_code, shipping_warehouse_id)
            REFERENCES factory_warehouse(factory_code, warehouse_id);
    END IF;
END $$;

-- rspu_factory_mapping 同规则升级（旧基线 V1 的内联单列 FK 一并撤除）
ALTER TABLE rspu_factory_mapping
    DROP CONSTRAINT IF EXISTS rspu_factory_mapping_shipping_warehouse_id_fkey;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_rspu_factory_mapping_factory_warehouse'
          AND conrelid = 'rspu_factory_mapping'::regclass) THEN
        ALTER TABLE rspu_factory_mapping
            ADD CONSTRAINT fk_rspu_factory_mapping_factory_warehouse
            FOREIGN KEY (factory_code, shipping_warehouse_id)
            REFERENCES factory_warehouse(factory_code, warehouse_id);
    END IF;
END $$;

-- 1b. 用户企业↔部门组：用户所属企业必须与群组所属企业一致
CREATE UNIQUE INDEX IF NOT EXISTS uk_member_group_company_group
    ON member_group(company_id, group_id);
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_sys_user_company_group'
          AND conrelid = 'sys_user'::regclass) THEN
        ALTER TABLE sys_user
            ADD CONSTRAINT fk_sys_user_company_group
            FOREIGN KEY (company_id, group_id)
            REFERENCES member_group(company_id, group_id);
    END IF;
END $$;

-- 1c. 收藏↔收藏夹：用户 A 的收藏不得挂到用户 B 的文件夹
CREATE UNIQUE INDEX IF NOT EXISTS uk_favorite_folder_user_folder
    ON favorite_folder(user_id, folder_id);
ALTER TABLE user_favorite DROP CONSTRAINT IF EXISTS fk_user_favorite_folder;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_user_favorite_user_folder'
          AND conrelid = 'user_favorite'::regclass) THEN
        ALTER TABLE user_favorite
            ADD CONSTRAINT fk_user_favorite_user_folder
            FOREIGN KEY (user_id, folder_id)
            REFERENCES favorite_folder(user_id, folder_id);
    END IF;
END $$;

-- 1d. 图片归属层级：variant_id 非空时 rspu_id 必须等于变体所属产品
-- （图片归属层级约定：RSPU 为主；变体专属图必须同时携带一致的 rspu_id）
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_image_assets_rspu_variant'
          AND conrelid = 'image_assets'::regclass) THEN
        ALTER TABLE image_assets
            ADD CONSTRAINT fk_image_assets_rspu_variant
            FOREIGN KEY (rspu_id, variant_id)
            REFERENCES rspu_variant(rspu_id, variant_id);
    END IF;
END $$;

-- ============================================================
-- 2. factory_product_capability：通配规则 NULL 判重（NULLS NOT DISTINCT，PG15+）
--    与 lead_time_rule 同口径；空串视为非法（统一 NULL 表达通配）
-- ============================================================
ALTER TABLE factory_product_capability
    DROP CONSTRAINT IF EXISTS factory_product_capability_factory_code_category_code_style_key;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
        WHERE conname = 'uk_factory_product_capability_match'
          AND conrelid = 'factory_product_capability'::regclass) THEN
        ALTER TABLE factory_product_capability
            ADD CONSTRAINT uk_factory_product_capability_match
            UNIQUE NULLS NOT DISTINCT (factory_code, category_code, style_code, material_code);
    END IF;
END $$;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_factory_capability_no_blank_code'
          AND conrelid = 'factory_product_capability'::regclass) THEN
        ALTER TABLE factory_product_capability
            ADD CONSTRAINT ck_factory_capability_no_blank_code
            CHECK ((category_code IS NULL OR category_code <> '')
               AND (style_code IS NULL OR style_code <> '')
               AND (material_code IS NULL OR material_code <> ''));
    END IF;
END $$;

-- ============================================================
-- 3. 主项部分唯一索引：业务确认「最多一个」（「必须有一个」仍由业务事务保证）
-- ============================================================
-- 主风格：每 RSPU 最多一条 is_primary = true
CREATE UNIQUE INDEX IF NOT EXISTS uk_rspu_style_primary
    ON rspu_style(rspu_id) WHERE is_primary = true;
-- 默认仓库：每工厂最多一条 is_default = true
CREATE UNIQUE INDEX IF NOT EXISTS uk_factory_warehouse_default
    ON factory_warehouse(factory_code) WHERE is_default = true;
-- 工厂主等级：每工厂最多一条 is_primary = true
CREATE UNIQUE INDEX IF NOT EXISTS uk_factory_level_capability_primary
    ON factory_level_capability(factory_code) WHERE is_primary = true;

-- ============================================================
-- 4. Agent 域内部外键：消息→会话、需求版本→会话、推荐批次→会话/需求版本、
--    推荐条目→批次、反馈→会话/条目/批次、确认条目→会话/推荐条目、运行→会话、
--    报价→会话、会话活跃运行→运行。商品快照（recommend_item.rspu_id、
--    confirmed_item.rspu_id、quote.items 内嵌）保留弱引用不加 FK；
--    customer_user_id 为注册后回填的外部归属，不加 FK。
--    全部为 RESTRICT（默认）：会话域物理删除前须先清理子记录（归档策略另议）。
-- ============================================================
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_agent_message_session') THEN
        ALTER TABLE agent_message ADD CONSTRAINT fk_agent_message_session
            FOREIGN KEY (session_id) REFERENCES agent_session(session_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_agent_message_run') THEN
        ALTER TABLE agent_message ADD CONSTRAINT fk_agent_message_run
            FOREIGN KEY (run_id) REFERENCES agent_run(run_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_agent_requirement_session') THEN
        ALTER TABLE agent_requirement_version ADD CONSTRAINT fk_agent_requirement_session
            FOREIGN KEY (session_id) REFERENCES agent_session(session_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_agent_recommend_batch_session') THEN
        ALTER TABLE agent_recommend_batch ADD CONSTRAINT fk_agent_recommend_batch_session
            FOREIGN KEY (session_id) REFERENCES agent_session(session_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_agent_recommend_batch_version') THEN
        ALTER TABLE agent_recommend_batch ADD CONSTRAINT fk_agent_recommend_batch_version
            FOREIGN KEY (session_id, version_no)
            REFERENCES agent_requirement_version(session_id, version_no);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_agent_recommend_item_batch') THEN
        ALTER TABLE agent_recommend_item ADD CONSTRAINT fk_agent_recommend_item_batch
            FOREIGN KEY (batch_id) REFERENCES agent_recommend_batch(batch_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_agent_feedback_session') THEN
        ALTER TABLE agent_feedback ADD CONSTRAINT fk_agent_feedback_session
            FOREIGN KEY (session_id) REFERENCES agent_session(session_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_agent_feedback_item') THEN
        ALTER TABLE agent_feedback ADD CONSTRAINT fk_agent_feedback_item
            FOREIGN KEY (item_id) REFERENCES agent_recommend_item(item_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_agent_feedback_batch') THEN
        ALTER TABLE agent_feedback ADD CONSTRAINT fk_agent_feedback_batch
            FOREIGN KEY (batch_id) REFERENCES agent_recommend_batch(batch_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_agent_confirmed_session') THEN
        ALTER TABLE agent_confirmed_item ADD CONSTRAINT fk_agent_confirmed_session
            FOREIGN KEY (session_id) REFERENCES agent_session(session_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_agent_confirmed_recommend_item') THEN
        ALTER TABLE agent_confirmed_item ADD CONSTRAINT fk_agent_confirmed_recommend_item
            FOREIGN KEY (recommend_item_id) REFERENCES agent_recommend_item(item_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_agent_run_session') THEN
        ALTER TABLE agent_run ADD CONSTRAINT fk_agent_run_session
            FOREIGN KEY (session_id) REFERENCES agent_session(session_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_agent_quote_session') THEN
        ALTER TABLE agent_quote ADD CONSTRAINT fk_agent_quote_session
            FOREIGN KEY (session_id) REFERENCES agent_session(session_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_agent_session_active_run') THEN
        ALTER TABLE agent_session ADD CONSTRAINT fk_agent_session_active_run
            FOREIGN KEY (active_run_id) REFERENCES agent_run(run_id);
    END IF;
    -- actor 用户外键（created_by / confirmed_by 为 sys_user.user_id）
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_agent_session_created_by') THEN
        ALTER TABLE agent_session ADD CONSTRAINT fk_agent_session_created_by
            FOREIGN KEY (created_by) REFERENCES sys_user(user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_agent_confirmed_by') THEN
        ALTER TABLE agent_confirmed_item ADD CONSTRAINT fk_agent_confirmed_by
            FOREIGN KEY (confirmed_by) REFERENCES sys_user(user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_agent_quote_created_by') THEN
        ALTER TABLE agent_quote ADD CONSTRAINT fk_agent_quote_created_by
            FOREIGN KEY (created_by) REFERENCES sys_user(user_id);
    END IF;
END $$;

-- ============================================================
-- 5. 遗留表删除（无生产代码引用、本地 0 行；删除前已核对无评估/排产/反馈闭环）
-- ============================================================
DROP TABLE IF EXISTS user_operator CASCADE;
DROP TABLE IF EXISTS factory_capacity_assessment CASCADE;
DROP TABLE IF EXISTS factory_variant_capacity CASCADE;
DROP TABLE IF EXISTS matching_feedback CASCADE;

-- ============================================================
-- 6. rspu_master.style_vector 退役：新向量已入 pgvector（product_image_embedding），
--    历史值可由向量表重建，引用代码已清理
-- ============================================================
ALTER TABLE rspu_master DROP COLUMN IF EXISTS style_vector;

-- ============================================================
-- 7. 索引瘦身：被同表唯一索引左前缀覆盖的单列索引删除；
--    idx_variant_dimensions_gin 换成贴合实际查询的 seat_count 表达式索引
-- ============================================================
DROP INDEX IF EXISTS idx_dict_alias_type;              -- 由 uk_dict_alias(dict_type, alias_name) 覆盖
DROP INDEX IF EXISTS idx_collection_item_collection;   -- 由 product_collection_item_collection_id_rspu_id_key 覆盖
DROP INDEX IF EXISTS idx_product_match_rspu;           -- 由 product_style_match_rspu_id_style_code_key 覆盖
DROP INDEX IF EXISTS idx_agent_recommend_item_batch;   -- 由 uk_agent_recommend_item_rank(batch_id, rank) 覆盖
DROP INDEX IF EXISTS idx_variant_dimensions_gin;
CREATE INDEX IF NOT EXISTS idx_variant_seat_count
    ON rspu_variant ((COALESCE(dimensions->>'seat_count', dimensions->>'seatCount')))
    WHERE deleted_at IS NULL;


-- 第三部分：产品合并归属外键（原 V19，发布前合并）
-- 产品合并分步迁移变体及子行；默认仍即时校验，仅合并事务显式 SET CONSTRAINTS DEFERRED。
ALTER TABLE rsku_supply
    ALTER CONSTRAINT fk_rsku_rspu_variant DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE image_assets
    ALTER CONSTRAINT fk_image_assets_rspu_variant DEFERRABLE INITIALLY IMMEDIATE;
