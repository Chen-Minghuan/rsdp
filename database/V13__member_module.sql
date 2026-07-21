-- RSDP V13 数据库迁移：用户中心 + 企业团队完整版（RSDP × rooom 全量复现阶段 5）
-- 基于 docs/08-roadmap/RSDP-rooom全量复现与AI整合方案.md 阶段 5
-- V6 轻量版（company_name/group_name 文本字段）升级为企业/分组实体；
-- 新增永久邀请码、邀请记录、认证设计师标记。
-- 说明：company_name/group_name 文本列保留不删（DDL 只增不删；project.company_name 回退等仍在使用）。
-- 幂等：全部使用 IF NOT EXISTS / NOT EXISTS 守卫，可重复执行。

-- ============================================================
-- 一、企业表
-- ============================================================
CREATE TABLE IF NOT EXISTS company (
    company_id    VARCHAR(64) PRIMARY KEY,           -- COM-<完整 UUID>
    company_name  VARCHAR(128) NOT NULL,
    logo_image_id VARCHAR(64),                       -- 企业 Logo（image_assets.image_id，不加外键，图片可独立清理）
    price_ratio   NUMERIC(5,4) NOT NULL DEFAULT 1,   -- 企业级折扣率，计价优先级高于全局 order.price_rate
    owner_id      VARCHAR(64) NOT NULL REFERENCES sys_user(user_id),
    status        VARCHAR(16) NOT NULL DEFAULT 'active',
    deleted_at    TIMESTAMP,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_company_price_ratio CHECK (price_ratio >= 0 AND price_ratio <= 1)
);
CREATE INDEX IF NOT EXISTS idx_company_owner ON company(owner_id);
CREATE INDEX IF NOT EXISTS idx_company_name ON company(company_name) WHERE deleted_at IS NULL;

COMMENT ON TABLE company IS '企业实体（用户中心-企业团队）；企业账号 = sys_user.company_id 非空';
COMMENT ON COLUMN company.price_ratio IS '企业级折扣率 [0,1]，订单计价优先于全局 order.price_rate';

-- ============================================================
-- 二、企业内分组/部门表
-- ============================================================
CREATE TABLE IF NOT EXISTS member_group (
    group_id    VARCHAR(64) PRIMARY KEY,             -- GRP-<完整 UUID>
    company_id  VARCHAR(64) NOT NULL REFERENCES company(company_id),
    group_name  VARCHAR(64) NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT true,
    deleted_at  TIMESTAMP,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_member_group_company ON member_group(company_id) WHERE deleted_at IS NULL;

COMMENT ON TABLE member_group IS '企业内分组/部门';

-- ============================================================
-- 三、sys_user 扩展：企业归属 / 邀请 / 认证设计师
-- ============================================================
ALTER TABLE sys_user
    ADD COLUMN IF NOT EXISTS company_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS group_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS invite_code VARCHAR(16),
    ADD COLUMN IF NOT EXISTS invited_by VARCHAR(64),
    ADD COLUMN IF NOT EXISTS certified_designer BOOLEAN NOT NULL DEFAULT false;

-- 邀请码唯一（PG 唯一索引天然允许多个 NULL）
CREATE UNIQUE INDEX IF NOT EXISTS idx_sys_user_invite_code ON sys_user(invite_code);
CREATE INDEX IF NOT EXISTS idx_sys_user_company ON sys_user(company_id);

ALTER TABLE sys_user DROP CONSTRAINT IF EXISTS fk_sys_user_company;
ALTER TABLE sys_user
    ADD CONSTRAINT fk_sys_user_company FOREIGN KEY (company_id) REFERENCES company(company_id);
ALTER TABLE sys_user DROP CONSTRAINT IF EXISTS fk_sys_user_group;
ALTER TABLE sys_user
    ADD CONSTRAINT fk_sys_user_group FOREIGN KEY (group_id) REFERENCES member_group(group_id);
ALTER TABLE sys_user DROP CONSTRAINT IF EXISTS fk_sys_user_invited_by;
ALTER TABLE sys_user
    ADD CONSTRAINT fk_sys_user_invited_by FOREIGN KEY (invited_by) REFERENCES sys_user(user_id);

COMMENT ON COLUMN sys_user.company_id IS '所属企业（company.company_id），非空即企业账号';
COMMENT ON COLUMN sys_user.group_id IS '所属企业分组（member_group.group_id）';
COMMENT ON COLUMN sys_user.invite_code IS '永久邀请码（8 位），注册时通过 ?inviteCode= 绑定';
COMMENT ON COLUMN sys_user.invited_by IS '邀请人 user_id（注册归因，不携带权限）';
COMMENT ON COLUMN sys_user.certified_designer IS '认证设计师标记（VIEWER 一键升级时置位并补 DESIGNER 角色）';

-- ============================================================
-- 四、邀请记录表
-- ============================================================
CREATE TABLE IF NOT EXISTS invite_record (
    id          BIGSERIAL PRIMARY KEY,
    inviter_id  VARCHAR(64) NOT NULL REFERENCES sys_user(user_id),
    invitee_id  VARCHAR(64) NOT NULL REFERENCES sys_user(user_id),
    invite_code VARCHAR(16) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_invite_record_inviter ON invite_record(inviter_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_invite_record_invitee ON invite_record(invitee_id);

COMMENT ON TABLE invite_record IS '用户邀请记录（永久邀请码注册归因）';

-- ============================================================
-- 五、历史数据迁移（幂等）：company_name/group_name 文本 → 实体（同名企业合并）
--     若库未应用 V6（无 company_name 列）则跳过，无可迁移数据
-- ============================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'sys_user' AND column_name = 'company_name') THEN
        -- 5.1 按 distinct company_name 创建企业（已存在同名企业则跳过），
        --     管理员取该企业下最早创建的用户
        INSERT INTO company (company_id, company_name, owner_id)
        SELECT 'COM-' || gen_random_uuid()::text,
               dc.company_name,
               (SELECT u.user_id FROM sys_user u
                WHERE u.company_name = dc.company_name
                ORDER BY u.created_at ASC NULLS LAST, u.user_id ASC
                LIMIT 1)
        FROM (SELECT DISTINCT company_name FROM sys_user
              WHERE company_name IS NOT NULL AND btrim(company_name) <> '') dc
        WHERE NOT EXISTS (SELECT 1 FROM company c WHERE c.company_name = dc.company_name);

        -- 5.2 回填用户企业归属
        UPDATE sys_user u SET company_id = c.company_id
        FROM company c
        WHERE u.company_id IS NULL AND u.company_name = c.company_name;

        -- 5.3 按 distinct (company_name, group_name) 创建分组（已存在同名分组则跳过）
        INSERT INTO member_group (group_id, company_id, group_name)
        SELECT 'GRP-' || gen_random_uuid()::text, c.company_id, dg.group_name
        FROM (SELECT DISTINCT company_name, group_name FROM sys_user
              WHERE company_name IS NOT NULL AND btrim(company_name) <> ''
                AND group_name IS NOT NULL AND btrim(group_name) <> '') dg
        JOIN company c ON c.company_name = dg.company_name
        WHERE NOT EXISTS (SELECT 1 FROM member_group g
                          WHERE g.company_id = c.company_id AND g.group_name = dg.group_name);

        -- 5.4 回填用户分组归属
        UPDATE sys_user u SET group_id = g.group_id
        FROM company c
        JOIN member_group g ON g.company_id = c.company_id
        WHERE u.group_id IS NULL
          AND u.company_name = c.company_name
          AND u.group_name = g.group_name;
    END IF;
END $$;
