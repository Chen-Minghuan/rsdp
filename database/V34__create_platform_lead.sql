-- V34: 官网留资线索表（platform_lead）
-- 背景：用户端官网（Nuxt 3 新站）的留资 CTA / 表单 / AI 户型搭配入口统一写入本表，
-- 管理端「留资线索」页做分配与跟进（见 docs/09-design/RSDP管理端工作台设计文档.md 第 4.3 节（docs/09-design/ 已删除））。
-- 幂等：IF NOT EXISTS

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
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  platform_lead IS '官网留资线索（用户端 CTA/表单/AI 搭配入口）';
COMMENT ON COLUMN platform_lead.source IS '来源：ai_match=AI 户型搭配 / site_form=官网表单 / design_booking=设计服务预约';
COMMENT ON COLUMN platform_lead.status IS '跟进状态：pending=待跟进 / contacted=已联系 / done=已完成';
COMMENT ON COLUMN platform_lead.follow_log IS '跟进记录 JSON 数组（追加式）';

CREATE INDEX IF NOT EXISTS idx_platform_lead_status ON platform_lead(status, created_at);
CREATE INDEX IF NOT EXISTS idx_platform_lead_source ON platform_lead(source, created_at);
