-- ============================================================
-- RSDP 基线 DDL · 01 字典域（01_dict.sql）
-- 包含表：category_dict, dict_alias, dict_unresolved_value, six_dim_schema
-- 执行顺序：schema/ 目录按文件名 01 → 12 → 99 依次执行（编号即执行顺序，基线由原 V1__init_db.sql 按域拆分而来）
-- 同步约定：新增/修改本域表结构时须同步 ops/reset_db.sql，约定详见 database/README.md
-- ============================================================
-- 字典表（先创建，后续表的外键依赖它）
CREATE TABLE IF NOT EXISTS category_dict (
    dict_type VARCHAR(32) NOT NULL,
    dict_code VARCHAR(32) NOT NULL,
    dict_name VARCHAR(64) NOT NULL,
    dict_name_en VARCHAR(64),
    parent_code VARCHAR(32),
    sort_order INTEGER,
    status VARCHAR(16) DEFAULT 'active',
    aliases TEXT,                            -- 同义词别名 JSON 数组（V22）
    remark TEXT,                             -- 备注；六维字典存视觉判别要点（V29）
    image_id VARCHAR(64),                    -- 场景封面图 image_assets.image_id，仅 dict_type=scene 使用（V35）
    PRIMARY KEY (dict_type, dict_code)
);

-- 字典别名表（V16 并入）：工厂方言叫法 → 字典码的持久化映射（导入确认后自学习积累）
CREATE TABLE IF NOT EXISTS dict_alias (
    id          BIGSERIAL PRIMARY KEY,
    dict_type   VARCHAR(32) NOT NULL,
    alias_name  VARCHAR(64) NOT NULL,
    dict_code   VARCHAR(16) NOT NULL,
    source      VARCHAR(16) NOT NULL DEFAULT 'ai_confirmed',
    created_by  VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_dict_alias UNIQUE (dict_type, alias_name)
);
CREATE INDEX IF NOT EXISTS idx_dict_alias_type ON dict_alias(dict_type);

-- 未归一值采集表（V19 并入）：导入时字典解析未命中的工厂原文自动计数采集，
-- 供运营在治理页面归并（写 dict_alias 自学习）或忽略
CREATE TABLE IF NOT EXISTS dict_unresolved_value (
    id BIGSERIAL PRIMARY KEY,
    dict_type VARCHAR(32) NOT NULL,                 -- 字典类型：size/color/material（可扩展 style 等）
    raw_value VARCHAR(128) NOT NULL,                -- 未归一的工厂原文
    occurrence_count INT NOT NULL DEFAULT 1,        -- 累计出现次数
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_batch_id VARCHAR(64),                      -- 最近出现的导入批次
    last_username VARCHAR(64),                      -- 最近操作人
    status VARCHAR(16) NOT NULL DEFAULT 'pending',  -- pending/resolved/ignored
    resolved_code VARCHAR(16),                      -- 归并后的字典码（resolved 时填写）
    resolved_by VARCHAR(64),
    resolved_at TIMESTAMPTZ,
    CONSTRAINT uk_dict_unresolved UNIQUE (dict_type, raw_value)
);
CREATE INDEX IF NOT EXISTS idx_dict_unresolved_status ON dict_unresolved_value(status, dict_type);

-- 六维标签维度定义表（V30）：品类 × A-F 维度键 → 标签/说明，替代前后端双写
CREATE TABLE IF NOT EXISTS six_dim_schema (
    id            BIGSERIAL PRIMARY KEY,
    category_code VARCHAR(16)  NOT NULL,
    dim_key       VARCHAR(4)   NOT NULL,
    label         VARCHAR(64)  NOT NULL,
    description   VARCHAR(255) NOT NULL DEFAULT '',
    sort_order    INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_six_dim_schema UNIQUE (category_code, dim_key)
);

