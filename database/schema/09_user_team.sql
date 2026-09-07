-- ============================================================
-- RSDP 基线 DDL · 09 用户与团队域（09_user_team.sql）
-- 包含表：sys_user, sys_role, sys_permission, sys_user_role, sys_role_permission, sys_user_factory, company, member_group, invite_record, designer_profile, user_operator
-- 执行顺序：schema/ 目录按文件名 01 → 12 → 99 依次执行（编号即执行顺序，基线由原 V1__init_db.sql 按域拆分而来）
-- 同步约定：新增/修改本域表结构时须同步 ops/reset_db.sql，约定详见 database/README.md
-- ============================================================
-- 操作员表
CREATE TABLE IF NOT EXISTS user_operator (
    user_id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    real_name VARCHAR(64),
    role VARCHAR(32),
    status VARCHAR(16) DEFAULT 'active',
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ
);

-- 系统用户表
CREATE TABLE IF NOT EXISTS sys_user (
    user_id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    nickname VARCHAR(64),
    company_name VARCHAR(128),
    group_name VARCHAR(64),
    status VARCHAR(16) DEFAULT 'active',
    token_version INT DEFAULT 0,
    view_full_catalog BOOLEAN NOT NULL DEFAULT false,
    company_id VARCHAR(64),
    group_id VARCHAR(64),
    invite_code VARCHAR(16),
    invited_by VARCHAR(64),
    certified_designer BOOLEAN NOT NULL DEFAULT false,
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ
);

-- idx_sys_user_username 已于 V43 删除：被 username 行内 UNIQUE 覆盖，冗余
CREATE UNIQUE INDEX IF NOT EXISTS idx_sys_user_invite_code ON sys_user(invite_code);
CREATE INDEX IF NOT EXISTS idx_sys_user_company ON sys_user(company_id);

-- 企业表（V13 并入）
CREATE TABLE IF NOT EXISTS company (
    company_id    VARCHAR(64) PRIMARY KEY,
    company_name  VARCHAR(128) NOT NULL,
    logo_image_id VARCHAR(64),
    price_ratio   NUMERIC(5,4) NOT NULL DEFAULT 1,
    owner_id      VARCHAR(64) NOT NULL REFERENCES sys_user(user_id),
    status        VARCHAR(16) NOT NULL DEFAULT 'active',
    deleted_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_company_price_ratio CHECK (price_ratio >= 0 AND price_ratio <= 1)
);
CREATE INDEX IF NOT EXISTS idx_company_owner ON company(owner_id);
CREATE INDEX IF NOT EXISTS idx_company_name ON company(company_name) WHERE deleted_at IS NULL;

-- 企业内分组/部门表（V13 并入）
CREATE TABLE IF NOT EXISTS member_group (
    group_id    VARCHAR(64) PRIMARY KEY,
    company_id  VARCHAR(64) NOT NULL REFERENCES company(company_id),
    group_name  VARCHAR(64) NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT true,
    deleted_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_member_group_company ON member_group(company_id) WHERE deleted_at IS NULL;

-- 邀请记录表（V13 并入）
CREATE TABLE IF NOT EXISTS invite_record (
    id          BIGSERIAL PRIMARY KEY,
    inviter_id  VARCHAR(64) NOT NULL REFERENCES sys_user(user_id),
    invitee_id  VARCHAR(64) NOT NULL REFERENCES sys_user(user_id),
    invite_code VARCHAR(16) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_invite_record_inviter ON invite_record(inviter_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_invite_record_invitee ON invite_record(invitee_id);

-- 角色表
CREATE TABLE IF NOT EXISTS sys_role (
    role_id BIGSERIAL PRIMARY KEY,
    role_code VARCHAR(32) NOT NULL UNIQUE,
    role_name VARCHAR(64) NOT NULL,
    status VARCHAR(16) DEFAULT 'active',
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ
);

-- 权限表
CREATE TABLE IF NOT EXISTS sys_permission (
    permission_id BIGSERIAL PRIMARY KEY,
    permission_code VARCHAR(64) NOT NULL UNIQUE,
    permission_name VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- 角色权限关联表
CREATE TABLE IF NOT EXISTS sys_role_permission (
    id BIGSERIAL PRIMARY KEY,
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (role_id, permission_id),
    FOREIGN KEY (role_id) REFERENCES sys_role(role_id),
    FOREIGN KEY (permission_id) REFERENCES sys_permission(permission_id)
);

-- 用户角色关联表
CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    role_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES sys_user(user_id),
    FOREIGN KEY (role_id) REFERENCES sys_role(role_id)
);

-- 用户工厂关联表（用于厂商业务员数据权限）
CREATE TABLE IF NOT EXISTS sys_user_factory (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    factory_code VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, factory_code),
    FOREIGN KEY (user_id) REFERENCES sys_user(user_id),
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code)
);

-- 设计师画像
CREATE TABLE IF NOT EXISTS designer_profile (
    profile_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL UNIQUE,
    real_name VARCHAR(64),
    avatar_url TEXT,
    specialties JSONB,
    preferred_styles JSONB,
    preferred_categories JSONB,
    price_sensitivity VARCHAR(16),
    location VARCHAR(64),
    company_name VARCHAR(128),
    contact_phone VARCHAR(32),
    bio TEXT,
    default_budget_min DECIMAL(18,2),
    default_budget_max DECIMAL(18,2),
    is_public BOOLEAN DEFAULT false,
    status VARCHAR(16) DEFAULT 'active',
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    FOREIGN KEY (user_id) REFERENCES sys_user(user_id)
);
-- idx_designer_profile_user 已于 V43 删除：被 user_id 行内 UNIQUE 覆盖，冗余
CREATE INDEX IF NOT EXISTS idx_designer_profile_status ON designer_profile(status);

