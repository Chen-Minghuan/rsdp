-- V20: RSPU/RSKU 业务编码字段与 RSKU 编码计数器
-- 幂等：可重复执行，已存在字段/表/索引时跳过

-- RSPU 业务编码
ALTER TABLE rspu_master
    ADD COLUMN IF NOT EXISTS rspu_code VARCHAR(32) UNIQUE;

CREATE INDEX IF NOT EXISTS idx_rspu_code ON rspu_master(rspu_code);

-- RSKU 业务编码
ALTER TABLE rsku_supply
    ADD COLUMN IF NOT EXISTS rsku_code VARCHAR(64) UNIQUE;

CREATE INDEX IF NOT EXISTS idx_rsku_code ON rsku_supply(rsku_code);

-- RSKU 编码计数器：按 RSPU 业务编码 + 工厂代码 + 材质码维度递增
CREATE TABLE IF NOT EXISTS rsku_code_counter (
    rspu_code VARCHAR(32) NOT NULL,
    factory_code VARCHAR(16) NOT NULL,
    material_code VARCHAR(16) NOT NULL,
    sequence_value BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (rspu_code, factory_code, material_code)
);
