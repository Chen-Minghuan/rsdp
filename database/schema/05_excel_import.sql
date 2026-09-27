-- ============================================================
-- RSDP 基线 DDL · 05 导入域（05_excel_import.sql）
-- 包含表：excel_import_batch, excel_import_row, document_import_batch（PDF 文档导入批次，V2 并入）, supplier_product_ref（供应商商品引用，v3.3 §6，Flyway V14）, excel_import_preview_revision（Preview Snapshot Header，v3.3 §20.1，Flyway V14）, excel_import_product_group（Immutable 产品聚合输入，v3.3 §21，Flyway V14）, excel_import_sensitive_value（敏感 Preview 值，v3.3 §24.2，Flyway V14）
-- 执行顺序：schema/ 目录按文件名 01 → 12 → 99 依次执行（编号即执行顺序，基线由原 V1__init_db.sql 按域拆分而来）
-- 同步约定：新增/修改本域表结构时须同步 ops/reset_db.sql，约定详见 database/README.md
-- ============================================================
-- Excel AI 辅助导入批次表
CREATE TABLE IF NOT EXISTS excel_import_batch (
    batch_id VARCHAR(64) PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    storage_path VARCHAR(512),                      -- 原始 Excel 文件存储路径
    status VARCHAR(20) DEFAULT 'pending',
    total_rows INT DEFAULT 0,
    success_count INT DEFAULT 0,
    failed_count INT DEFAULT 0,
    column_mapping JSONB,
    preview_rows JSONB,
    price_columns JSONB,
    failures JSONB,
    -- 工厂关联、发货地、交期/MOQ、导入元数据（V2 并入）
    factory_code VARCHAR(16),
    factory_name VARCHAR(128),
    shipping_warehouse_id VARCHAR(64),
    shipping_from VARCHAR(128),
    default_lead_time_days INTEGER,
    default_moq INTEGER,
    category_hint VARCHAR(16),
    header_row_count INTEGER DEFAULT 2,
    data_start_row INTEGER DEFAULT 3,
    import_note TEXT,
    sheet_index INT NOT NULL DEFAULT 0,                 -- 多 Sheet 文件批次解析的工作表索引（V18 并入）
    processed_at TIMESTAMPTZ,
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    -- P0-B1 Immutable Preview Snapshot / Revision（v3.3 §45，Flyway V14 追加，列顺序与 ALTER 一致）
    preview_revision BIGINT,                            -- 当前最新 Preview Revision 指针
    mapping_hash VARCHAR(64),                           -- 当前 mapping 哈希（§47.1.4）
    normalizer_version VARCHAR(64),                     -- 当前 Normalizer 版本
    confirmed_revision BIGINT,                          -- 已确认 Revision 指针（Confirm 语义 P0-B3 收口）
    confirmed_by VARCHAR(64),
    confirmed_at TIMESTAMPTZ,
    import_policy JSONB,                                -- 导入 Policy UI 草稿（§45/§47.1.5）
    retention_hold BOOLEAN NOT NULL DEFAULT FALSE,      -- 审计/争议保留标记（§44），GC 必须跳过
    snapshot_archive_summary JSONB NOT NULL DEFAULT '{}'::jsonb  -- 过期归档非敏感摘要（§44/§45），仅受限 GC 函数写入
    -- 外键在 sys_user 表创建后通过 ALTER TABLE 添加；
    -- preview_revision / confirmed_revision 的指针 FK 在本文件 excel_import_preview_revision 建表后追加
);
CREATE INDEX IF NOT EXISTS idx_excel_import_batch_status ON excel_import_batch(status);
-- 僵死批次收割索引（V43）：reapStaleImporting WHERE status='importing' AND (updated_at IS NULL OR updated_at < ?)
CREATE INDEX IF NOT EXISTS idx_import_batch_stale ON excel_import_batch(updated_at) WHERE status = 'importing';
CREATE INDEX IF NOT EXISTS idx_excel_import_batch_created_by ON excel_import_batch(created_by);
CREATE INDEX IF NOT EXISTS idx_excel_import_batch_factory ON excel_import_batch(factory_code);

-- Excel 行级导入记录表（V2 并入）
CREATE TABLE IF NOT EXISTS excel_import_row (
    row_id BIGSERIAL PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
    excel_row_number INTEGER NOT NULL,             -- Excel中的原始行号
    row_type VARCHAR(32) NOT NULL,                 -- product/module/header/unknown/preview_placeholder
    parent_row_id BIGINT,                          -- 模块行关联到产品型号行
    raw_data JSONB NOT NULL,                       -- 原始数据快照
    mapped_fields JSONB,                           -- AI映射后的字段
    selected_price_columns JSONB,                  -- 识别的价格列
    status VARCHAR(16) DEFAULT 'pending',          -- pending/processing/success/failed/skipped
    processing_stage VARCHAR(32),                  -- 当前处理阶段
    generated_rspu_id VARCHAR(64),                 -- 生成的RSPU ID
    generated_variant_id VARCHAR(64),              -- 生成的变体ID
    generated_rsku_ids JSONB,                      -- ["RSKU-001", "RSKU-002"]
    failure_reason TEXT,                           -- 失败原因描述
    failure_stage VARCHAR(32),                     -- 在哪个阶段失败
    extracted_image_count INTEGER DEFAULT 0,       -- 提取到的图片数量
    image_asset_ids JSONB,                         -- ["IMG-001", "IMG-002"]
    override_image_asset_ids JSONB,                -- 用户在数据清洗页编辑后的图片 asset ID 列表（V33）
    ai_task_id VARCHAR(64),                        -- 关联的异步AI识别任务
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    FOREIGN KEY (batch_id) REFERENCES excel_import_batch(batch_id),
    FOREIGN KEY (parent_row_id) REFERENCES excel_import_row(row_id),
    FOREIGN KEY (generated_rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (generated_variant_id) REFERENCES rspu_variant(variant_id),
    UNIQUE (batch_id, excel_row_number)
);
CREATE INDEX IF NOT EXISTS idx_import_row_batch ON excel_import_row(batch_id, status);
CREATE INDEX IF NOT EXISTS idx_import_row_type ON excel_import_row(batch_id, row_type);
CREATE INDEX IF NOT EXISTS idx_import_row_rspu ON excel_import_row(generated_rspu_id);
CREATE INDEX IF NOT EXISTS idx_import_row_parent ON excel_import_row(parent_row_id);

-- 文档（PDF）导入批次表（阶段 3.1：PDF 导入异步批次化）
-- 提交即返回 batchId，批处理复用 async_task 体系（task_type=document_import）异步执行；
-- 行级/页级明细收敛在 JSONB 字段（failures/page_results），不新建行表
CREATE TABLE IF NOT EXISTS document_import_batch (
    batch_id VARCHAR(64) PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    storage_path VARCHAR(512),                       -- 原始 PDF 文件存储路径（MinIO/本地磁盘）
    status VARCHAR(20) DEFAULT 'pending',            -- pending/processing/done/partial_success/failed
    total_pages INT DEFAULT 0,
    processed_pages INT DEFAULT 0,                   -- 已处理页数（前端进度轮询）
    product_pages INT DEFAULT 0,
    detected_products INT DEFAULT 0,
    success_count INT DEFAULT 0,
    fail_count INT DEFAULT 0,
    skip_count INT DEFAULT 0,                        -- 图片 contentHash 查重命中跳过建档数
    failures JSONB,                                  -- [{pageIndex, reason}]，含"已存在跳过"明细
    page_results JSONB,                              -- [{pageIndex, pageType, productCount, status}]
    task_ids JSONB,                                  -- 建档 product_entry 任务 ID（与 rspu_ids 一一对应，供前端继续轮询）
    rspu_ids JSONB,
    category_hint VARCHAR(16),
    error_message TEXT,
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
    -- created_by 外键在 cross_domain_fk.sql 后置（sys_user 在 09 域创建）
);
CREATE INDEX IF NOT EXISTS idx_document_import_batch_status ON document_import_batch(status);
CREATE INDEX IF NOT EXISTS idx_document_import_batch_created_by ON document_import_batch(created_by);

-- 供应商商品引用表（v3.3 设计文档 §6，Flyway V14；external_code 迁移 Phase 1 Expand）
-- 供应商（工厂）命名空间内商品编码 → RSPU 的历史可追溯身份映射；
-- source_namespace 参与 Identity，first/last_seen_source_system 仅 provenance 不参与 Identity；
-- 唯一性口径 (factory_code, source_namespace, normalized_source_product_code)，
-- 与 rspu_master.external_code 旧全局唯一索引并存（Phase 1 只 Expand，不删旧索引）
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

COMMENT ON TABLE supplier_product_ref IS '供应商商品引用：工厂命名空间内商品编码到 RSPU 的历史可追溯身份映射（v3.3 §6）';
COMMENT ON COLUMN supplier_product_ref.source_namespace IS '供应商编码命名空间，参与 Identity；本期默认 default，禁止用传输渠道（excel/manual/erp-api）充当 namespace';
COMMENT ON COLUMN supplier_product_ref.first_seen_source_system IS '首次进入系统的来源系统（仅 provenance，不参与 Identity）';
COMMENT ON COLUMN supplier_product_ref.last_seen_source_system IS '最近进入系统的来源系统（仅 provenance，不参与 Identity）';
COMMENT ON COLUMN supplier_product_ref.normalized_source_product_code IS '归一化供应商商品编码，参与唯一键 (factory_code, source_namespace, normalized_source_product_code)';
COMMENT ON COLUMN supplier_product_ref.code_normalizer_version IS '归一化算法版本（v3.3 §7；版本演进须受控迁移，原子更新 normalized_source_product_code + code_normalizer_version）';
-- ============================================================
-- P0-B1：Immutable Preview Snapshot / Revision / Optimistic Lock 底座（Flyway V14）
-- ============================================================

-- Excel 导入 Preview Snapshot Header（v3.3 §20.1）
-- per-sheet（batch）边界的不可变 Revision 元数据：品类策略 / mapping_hash / normalizer_version / snapshot_hash；
-- 只允许 INSERT 新 Revision（§46），应用层 Repository 不暴露 UPDATE / DELETE（§20.1）；
-- 生产库 Runtime Role 的 REVOKE UPDATE/DELETE + 受限 GC 函数属 §85.2 B 类上线门禁运维项
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

-- Excel 导入 Preview 产品聚合输入（v3.3 §21）：immutable input
-- 执行期状态 / 计数 / Checkpoint / 结果摘要归 §49.2 excel_import_attempt_item（P0-F），本表禁止 runtime 字段（§21.1）
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

COMMENT ON TABLE excel_import_product_group IS 'Excel 导入 Preview 产品聚合输入（v3.3 §21）：某 Revision 下「这个逻辑产品被确认成什么标准化数据」的不可变输入；执行期状态/计数/Checkpoint 归 excel_import_attempt_item（P0-F），本表禁止 runtime 字段';
COMMENT ON COLUMN excel_import_product_group.normalized_payload IS '非敏感/普通级别的标准化数据；采购成本等敏感值只允许以 Sensitive Ref 出现（§24.2 excel_import_sensitive_value，B2/B3 落地）';
COMMENT ON COLUMN excel_import_product_group.payload_hash IS '对 §47.1.2 PayloadHashMaterial 的 SHA-256：payload_hash_version + canonical(payload) + 按 value_path 排序的敏感值 HMAC 条目';

-- excel_import_batch 指针列 COMMENT（§45；列定义见上方 CREATE TABLE excel_import_batch 末尾）
COMMENT ON COLUMN excel_import_batch.preview_revision IS '当前最新 Preview Revision 指针（§45）；真正的不可变策略/版本/哈希在 excel_import_preview_revision';
COMMENT ON COLUMN excel_import_batch.confirmed_revision IS '已确认 Revision 指针（§45）；Confirm 语义在 P0-B3 收口';
COMMENT ON COLUMN excel_import_batch.import_policy IS '导入 Policy 的 UI 草稿（§45/§47.1.5）；执行期不可变 policy_payload/policy_hash 归 excel_import_attempt（P0-F）';
COMMENT ON COLUMN excel_import_batch.retention_hold IS '审计/争议保留标记（§44）：true 时 GC 必须跳过整个 batch，解除须记审计日志';
COMMENT ON COLUMN excel_import_batch.snapshot_archive_summary IS '快照超保留期后的非敏感审计摘要（§44/§45），只能由受限 GC 函数写入；活跃指针为 NULL 且摘要存在时 Confirm/Import 返回 410 snapshot_expired';

-- 指针完整性：有值时必须引用同一 Batch 下真实存在的 Snapshot Revision（§45）
ALTER TABLE excel_import_batch
    DROP CONSTRAINT IF EXISTS fk_excel_import_batch_current_revision;
ALTER TABLE excel_import_batch
    ADD CONSTRAINT fk_excel_import_batch_current_revision
    FOREIGN KEY(batch_id, preview_revision)
    REFERENCES excel_import_preview_revision(batch_id, preview_revision);

ALTER TABLE excel_import_batch
    DROP CONSTRAINT IF EXISTS fk_excel_import_batch_confirmed_revision;
ALTER TABLE excel_import_batch
    ADD CONSTRAINT fk_excel_import_batch_confirmed_revision
    FOREIGN KEY(batch_id, confirmed_revision)
    REFERENCES excel_import_preview_revision(batch_id, preview_revision);
-- ============================================================
-- P0-B2：敏感 Preview 值表（v3.3 §24.2，Flyway V14）
-- ============================================================

-- 采购成本等敏感值不直接写入 normalized_payload，统一落本表（密文 + HMAC）；
-- Payload 中只保存 Sensitive Ref；§47.1.2 PayloadHashMaterial 敏感值条目参与 payload_hash。
-- B2 阶段只建表与约束（品类 Snapshot 负载不含敏感值，暂无写入方）；
-- 加密/HMAC Key 托管与轮换（§42.4/§42.5/§43.2）属 P0-E。
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
-- P0-F：Import Attempt 执行内核（v3.3 §48-§50，Flyway V14）
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
