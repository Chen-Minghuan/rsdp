-- ============================================================
-- RSDP 基线 DDL · 11 官网平台域（11_platform.sql）
-- 包含表：platform_banner, platform_case, platform_content, platform_custom_dict, platform_customized, platform_lead
-- 执行顺序：schema/ 目录按文件名 01 → 12 → 99 依次执行（编号即执行顺序，基线由原 V1__init_db.sql 按域拆分而来）
-- 同步约定：新增/修改本域表结构时须同步 ops/reset_db.sql，约定详见 database/README.md
-- ============================================================
-- ============================================================
-- 官网 CMS（V15 并入）：Banner / 落地案例 / 内容配置 / 自定义字典 / 产品定制
-- ============================================================
CREATE TABLE IF NOT EXISTS platform_banner (
    banner_id   VARCHAR(64) PRIMARY KEY,
    position    VARCHAR(32) NOT NULL DEFAULT 'home_top',
    title       VARCHAR(128),
    image_id    VARCHAR(64) NOT NULL,
    link_type   VARCHAR(16) NOT NULL DEFAULT 'none',
    link_value  VARCHAR(512),
    sort_order  INT NOT NULL DEFAULT 0,
    status      VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_platform_banner_position ON platform_banner(position, status, sort_order);

CREATE TABLE IF NOT EXISTS platform_case (
    case_id        VARCHAR(64) PRIMARY KEY,
    title          VARCHAR(128) NOT NULL,
    cover_image_id VARCHAR(64),
    content        TEXT,
    sort_order     INT NOT NULL DEFAULT 0,
    status         VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_platform_case_status ON platform_case(status, sort_order);

CREATE TABLE IF NOT EXISTS platform_content (
    content_id   VARCHAR(64) PRIMARY KEY,
    code         VARCHAR(64) NOT NULL UNIQUE,
    title        VARCHAR(128),
    content_type VARCHAR(16) NOT NULL DEFAULT 'rich_text',
    content      TEXT,
    status       VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS platform_custom_dict (
    dict_id    VARCHAR(64) PRIMARY KEY,
    dict_name  VARCHAR(64) NOT NULL,
    dict_type  VARCHAR(32) NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (dict_type, dict_name)
);

CREATE TABLE IF NOT EXISTS platform_customized (
    customized_id  VARCHAR(64) PRIMARY KEY,
    title          VARCHAR(128) NOT NULL,
    cover_image_id VARCHAR(64),
    description    VARCHAR(512),
    link_value     VARCHAR(512),
    sort_order     INT NOT NULL DEFAULT 0,
    status         VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_platform_customized_status ON platform_customized(status, sort_order);

-- 官网留资线索表（V34 并入）：用户端 CTA/表单/AI 搭配入口留资，管理端分配跟进
CREATE TABLE IF NOT EXISTS platform_lead (
    lead_id     VARCHAR(64) PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    phone       VARCHAR(32)  NOT NULL,
    source      VARCHAR(32)  NOT NULL,
    intent      TEXT,
    budget      VARCHAR(32),
    status      VARCHAR(16)  NOT NULL DEFAULT 'pending',
    assignee    VARCHAR(64),
    follow_log  JSONB,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_platform_lead_status ON platform_lead(status, created_at);
CREATE INDEX IF NOT EXISTS idx_platform_lead_source ON platform_lead(source, created_at);

