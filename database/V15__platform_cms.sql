-- RSDP V15 数据库迁移：官网 CMS（RSDP × rooom 全量复现阶段 7）
-- 基于 docs/08-roadmap/RSDP-rooom全量复现与AI整合方案.md 阶段 7
-- 五张薄 CMS 表：首页 Banner / 落地案例 / 内容配置（协议/客服等）/ 自定义字典 / 产品定制。
-- 图片字段仅存 image_assets.image_id（不加外键，图片可独立清理）。
-- 幂等：全部使用 IF NOT EXISTS / ON CONFLICT 守卫，可重复执行。

-- ============================================================
-- 一、首页 Banner（轮播）
-- ============================================================
CREATE TABLE IF NOT EXISTS platform_banner (
    banner_id   VARCHAR(64) PRIMARY KEY,             -- BAN-<完整 UUID>
    position    VARCHAR(32) NOT NULL DEFAULT 'home_top',
    title       VARCHAR(128),
    image_id    VARCHAR(64) NOT NULL,
    link_type   VARCHAR(16) NOT NULL DEFAULT 'none', -- none=不跳转 / rspu=产品详情 / url=外链
    link_value  VARCHAR(512),
    sort_order  INT NOT NULL DEFAULT 0,
    status      VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_platform_banner_position ON platform_banner(position, status, sort_order);

COMMENT ON TABLE platform_banner IS '官网首页轮播 Banner（link_type: none/rspu/url）';

-- ============================================================
-- 二、落地案例
-- ============================================================
CREATE TABLE IF NOT EXISTS platform_case (
    case_id        VARCHAR(64) PRIMARY KEY,          -- CASE-<完整 UUID>
    title          VARCHAR(128) NOT NULL,
    cover_image_id VARCHAR(64),
    content        TEXT,                             -- 富文本 HTML
    sort_order     INT NOT NULL DEFAULT 0,
    status         VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_platform_case_status ON platform_case(status, sort_order);

COMMENT ON TABLE platform_case IS '官网落地案例（封面 + 富文本详情）';

-- ============================================================
-- 三、内容配置（服务协议/客服咨询等，按 code 读取）
-- ============================================================
CREATE TABLE IF NOT EXISTS platform_content (
    content_id   VARCHAR(64) PRIMARY KEY,            -- CONT-<完整 UUID>
    code         VARCHAR(64) NOT NULL UNIQUE,        -- 如 platform_user_agreement / platform_consulting_service
    title        VARCHAR(128),
    content_type VARCHAR(16) NOT NULL DEFAULT 'rich_text', -- image=单图 / rich_text=富文本 / embed=嵌入代码
    content      TEXT,                               -- 富文本 HTML / 图片 image_id / 嵌入代码
    status       VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE platform_content IS '官网内容配置（按 code 唯一读取，免登录公开接口使用）';

-- 内容种子：服务协议 + 客服咨询（占位文案，运营可在管理端修改）
INSERT INTO platform_content (content_id, code, title, content_type, content) VALUES
('CONT-USER-AGREEMENT', 'platform_user_agreement', '服务协议', 'rich_text',
 '<h3>RSDP 家居全案平台服务协议</h3><p>欢迎使用 RSDP 家居全案平台。请您在使用本平台前仔细阅读本协议。</p><p>1. 本平台提供的产品信息、价格信息仅供参考，实际以双方确认的订单为准。</p><p>2. 您应当妥善保管账号信息，因账号保管不善造成的损失由您自行承担。</p><p>3. 未经许可，不得将平台数据用于任何商业用途。</p><p>（本内容为占位文案，请在管理端「官网内容-内容管理」中替换为正式协议。）</p>'),
('CONT-CONSULTING-SERVICE', 'platform_consulting_service', '客服咨询', 'rich_text',
 '<h3>联系客服</h3><p>如需产品咨询、报价或售后服务，请通过以下方式联系我们：</p><p>工作时间：周一至周五 9:00 - 18:00</p><p>（本内容为占位文案，请在管理端「官网内容-内容管理」中配置真实联系方式。）</p>')
ON CONFLICT (code) DO NOTHING;

-- ============================================================
-- 四、自定义字典
-- ============================================================
CREATE TABLE IF NOT EXISTS platform_custom_dict (
    dict_id    VARCHAR(64) PRIMARY KEY,              -- PDIC-<完整 UUID>
    dict_name  VARCHAR(64) NOT NULL,
    dict_type  VARCHAR(32) NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (dict_type, dict_name)
);

COMMENT ON TABLE platform_custom_dict IS '官网自定义字典（运营自定义分类项）';

-- ============================================================
-- 五、产品定制入口
-- ============================================================
CREATE TABLE IF NOT EXISTS platform_customized (
    customized_id  VARCHAR(64) PRIMARY KEY,          -- CUST-<完整 UUID>
    title          VARCHAR(128) NOT NULL,
    cover_image_id VARCHAR(64),
    description    VARCHAR(512),
    link_value     VARCHAR(512),
    sort_order     INT NOT NULL DEFAULT 0,
    status         VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_platform_customized_status ON platform_customized(status, sort_order);

COMMENT ON TABLE platform_customized IS '官网产品定制入口卡片（封面 + 描述 + 跳转）';
