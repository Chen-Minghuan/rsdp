-- V28：工厂可见性体系（INTEG-001，2026-11-14）——归属标记 + 公共库开关 + 整批放行 + 黑名单
--
-- 背景：商品主档统一归 rsdp，工厂上传产品一律入公共库。工厂可见性规则：
--   可见(F) = 自家已报价（无条件） ∪ 整批放行（工厂×工厂） ∪ 公共库开关 ON（全量） − 黑名单（最高优先，
--   但不遮蔽自家已报价产品）。上下架/元数据维护权 = 谁录入谁负责（owner）。
--
-- 结构变更：
--   1) rspu_master 增 owner_type（PLATFORM/FACTORY）与 owner_factory_code（录入工厂归属标记）；
--      存量数据回填：有 RSKU 报价的产品归属首个报价工厂（created_at 最早），无报价留 PLATFORM。
--   2) factory_master 增 public_catalog_enabled（工厂级公共库开关，默认开）。
--   3) 新建 factory_catalog_grant（整批放行：owner 厂放行给目标厂）与
--      factory_catalog_block（黑名单：浏览厂屏蔽目标厂全部产品）。
--
-- 同步约定：database/schema/02_product.sql、03_factory.sql 已同步相同结构（check_flyway_schema_sync.sh 双路径闸）。

-- 1) 归属标记
ALTER TABLE rspu_master ADD COLUMN IF NOT EXISTS owner_type VARCHAR(16) NOT NULL DEFAULT 'PLATFORM';
ALTER TABLE rspu_master ADD COLUMN IF NOT EXISTS owner_factory_code VARCHAR(16);
ALTER TABLE rspu_master DROP CONSTRAINT IF EXISTS fk_rspu_owner_factory;
ALTER TABLE rspu_master
    ADD CONSTRAINT fk_rspu_owner_factory FOREIGN KEY (owner_factory_code) REFERENCES factory_master(factory_code);
COMMENT ON COLUMN rspu_master.owner_type IS '录入归属类型：PLATFORM=平台录入，FACTORY=工厂录入（INTEG-001）';
COMMENT ON COLUMN rspu_master.owner_factory_code IS '录入工厂编码（owner_type=FACTORY 时必填），上下架/维护权判定依据';

-- 存量回填：归属首个未删除报价的工厂（最早一条）；无报价保持 PLATFORM
UPDATE rspu_master m
SET owner_type = 'FACTORY',
    owner_factory_code = src.factory_code
FROM (
    SELECT DISTINCT ON (rspu_id) rspu_id, factory_code
    FROM rsku_supply
    WHERE deleted_at IS NULL
    ORDER BY rspu_id, created_at NULLS LAST, rsku_id
) src
WHERE m.rspu_id = src.rspu_id
  AND m.owner_factory_code IS NULL;

ALTER TABLE rspu_master ADD CONSTRAINT ck_rspu_owner
    CHECK ((owner_type = 'FACTORY' AND owner_factory_code IS NOT NULL)
        OR (owner_type <> 'FACTORY' AND owner_factory_code IS NULL));
CREATE INDEX IF NOT EXISTS idx_rspu_owner_factory ON rspu_master(owner_factory_code);

-- 2) 工厂级公共库开关（默认开：对其他厂默认可见）
ALTER TABLE factory_master ADD COLUMN IF NOT EXISTS public_catalog_enabled BOOLEAN NOT NULL DEFAULT TRUE;
COMMENT ON COLUMN factory_master.public_catalog_enabled IS '工厂级公共库开关：关=仅自家已报价+被放行产品；开=可见全库（减黑名单）';

-- 3) 整批放行 / 黑名单
CREATE TABLE IF NOT EXISTS factory_catalog_grant (
    id BIGSERIAL PRIMARY KEY,
    owner_factory_code VARCHAR(16) NOT NULL REFERENCES factory_master(factory_code),
    granted_factory_code VARCHAR(16) NOT NULL REFERENCES factory_master(factory_code),
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (owner_factory_code, granted_factory_code),
    CONSTRAINT ck_grant_not_self CHECK (owner_factory_code <> granted_factory_code)
);
COMMENT ON TABLE factory_catalog_grant IS '整批放行：owner 厂授权目标厂在其公共库开关关闭时仍可见 owner 厂全部产品（含新品）';

CREATE TABLE IF NOT EXISTS factory_catalog_block (
    id BIGSERIAL PRIMARY KEY,
    factory_code VARCHAR(16) NOT NULL REFERENCES factory_master(factory_code),
    blocked_factory_code VARCHAR(16) NOT NULL REFERENCES factory_master(factory_code),
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (factory_code, blocked_factory_code),
    CONSTRAINT ck_block_not_self CHECK (factory_code <> blocked_factory_code)
);
COMMENT ON TABLE factory_catalog_block IS '黑名单：浏览厂屏蔽目标厂全部产品，优先级高于公共库开关与整批放行（不遮蔽自家已报价产品）';
