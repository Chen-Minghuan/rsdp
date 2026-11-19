-- 录入证据与 Office 商品命令的持久化回执。存量 V28 推断归属保留待核验。
ALTER TABLE rspu_master ADD COLUMN IF NOT EXISTS owner_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE rspu_master ADD COLUMN IF NOT EXISTS source_channel VARCHAR(32);
ALTER TABLE rspu_master ADD COLUMN IF NOT EXISTS entry_provenance JSONB;
COMMENT ON COLUMN rspu_master.owner_verified IS '归属在后端建档时核验或经平台核证；V28 首报价回填仅作线索';
COMMENT ON COLUMN rspu_master.source_channel IS '首次建档渠道，后续编辑不改写';
COMMENT ON COLUMN rspu_master.entry_provenance IS '首次录入的可信主体和请求信息；仅授权接口可读';

CREATE TABLE IF NOT EXISTS office_catalog_receipt (
    factory_code VARCHAR(16) NOT NULL,
    workspace_id VARCHAR(64) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    result_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (factory_code, workspace_id, actor_id, request_id)
);

ALTER TABLE excel_import_batch ADD COLUMN IF NOT EXISTS office_workspace_id VARCHAR(64);
ALTER TABLE excel_import_batch ADD COLUMN IF NOT EXISTS office_actor_id VARCHAR(64);
ALTER TABLE excel_import_batch ADD COLUMN IF NOT EXISTS office_actor_name VARCHAR(128);
