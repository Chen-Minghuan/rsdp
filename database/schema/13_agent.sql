-- ============================================================
-- 营销 Agent 域：会话 / 消息 / 需求版本 / 推荐批次与条目 /
-- 反馈 / 确认条目 / 运行记录 / 报价快照（对应 Flyway V10 + V11 终态）
-- ============================================================

-- 会话表
CREATE TABLE IF NOT EXISTS agent_session (
    session_id VARCHAR(64) PRIMARY KEY,
    created_by VARCHAR(64) NOT NULL,                 -- actor：操作者（导购/代录人）
    customer_user_id VARCHAR(64),                    -- subject：需求归属客户，注册后回填
    customer_name VARCHAR(128),                      -- 代录客户名
    status VARCHAR(20) DEFAULT 'active',             -- active/closed
    current_version_no INTEGER DEFAULT 0,            -- 当前需求版本号
    summary TEXT,                                    -- 会话摘要
    active_run_id VARCHAR(64),                       -- 并发控制：一会话最多一个 running run
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ                           -- 逻辑删除，null=未删
);
COMMENT ON COLUMN agent_session.created_by IS 'actor：操作者（导购/代录人）';
COMMENT ON COLUMN agent_session.customer_user_id IS 'subject：需求归属客户，注册后回填';
COMMENT ON COLUMN agent_session.customer_name IS '代录客户名';
COMMENT ON COLUMN agent_session.active_run_id IS '并发控制：一会话最多一个 running run';
COMMENT ON COLUMN agent_session.deleted_at IS '逻辑删除，null=未删';

-- 消息表
CREATE TABLE IF NOT EXISTS agent_message (
    message_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64),
    client_message_id VARCHAR(64),
    role VARCHAR(20) NOT NULL,                       -- user/assistant/system
    message_type VARCHAR(20) DEFAULT 'text',         -- text/cards/requirement/notice/quote/scheme
    content TEXT,
    metadata JSONB,
    sequence_no BIGINT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN agent_message.client_message_id IS '客户端幂等 ID，非空时配合 session_id 唯一';
COMMENT ON COLUMN agent_message.role IS 'user/assistant/system';
COMMENT ON COLUMN agent_message.message_type IS 'text/cards/requirement/notice';
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_message_client ON agent_message(session_id, client_message_id);
CREATE INDEX IF NOT EXISTS idx_agent_message_session_seq ON agent_message(session_id, sequence_no);

-- 需求版本表
CREATE TABLE IF NOT EXISTS agent_requirement_version (
    version_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    version_no INTEGER NOT NULL,
    constraints JSONB NOT NULL,
    patch JSONB,
    source VARCHAR(20) NOT NULL,                     -- extract/followup/manual/feedback
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN agent_requirement_version.constraints IS '本版需求约束全量快照';
COMMENT ON COLUMN agent_requirement_version.patch IS '本版应用的 operations 留痕';
COMMENT ON COLUMN agent_requirement_version.source IS 'extract/followup/manual/feedback';
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_requirement_version_no ON agent_requirement_version(session_id, version_no);

-- 推荐批次表（含 V11 Skill 溯源列）
CREATE TABLE IF NOT EXISTS agent_recommend_batch (
    batch_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    version_no INTEGER NOT NULL,
    query_criteria JSONB,
    -- V11 新增列：ALTER ADD COLUMN 物理顺序追加在 created_at 之后（与迁移重放保持一致）
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    skill_id VARCHAR(64),                            -- 执行推荐的 Skill ID（如 living-room-matching）
    skill_version VARCHAR(32),                       -- Skill 版本号
    batch_type VARCHAR(20) DEFAULT 'primary'         -- primary（主体选品）/companion（配套推荐）
);
COMMENT ON COLUMN agent_recommend_batch.query_criteria IS '实际下发的检索条件';
COMMENT ON COLUMN agent_recommend_batch.skill_id IS '执行推荐的 Skill ID（如 living-room-matching），空=非 Skill 链路';
COMMENT ON COLUMN agent_recommend_batch.skill_version IS 'Skill 版本号（如 1.0.0），推荐可追溯';
COMMENT ON COLUMN agent_recommend_batch.batch_type IS 'primary（主体选品）/companion（配套推荐）';

-- 推荐条目表（含 V11 分组标签列）
CREATE TABLE IF NOT EXISTS agent_recommend_item (
    item_id VARCHAR(64) PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
    rspu_id VARCHAR(64) NOT NULL,
    rank INTEGER NOT NULL,
    rank_score NUMERIC(10,4),
    snapshot JSONB NOT NULL,
    reason JSONB,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    group_tag VARCHAR(64)                            -- 配套分组标签（品类码 TB/FS/FC 等，V11 追加列）
);
COMMENT ON COLUMN agent_recommend_item.snapshot IS '产品事实快照：名称/颜色/材质/retailPrice/尺寸等';
COMMENT ON COLUMN agent_recommend_item.reason IS '推荐理由：highlights + evidenceRefs';
COMMENT ON COLUMN agent_recommend_item.group_tag IS '配套分组标签（品类码 TB/FS/FC 等），主体选品为空';
CREATE INDEX IF NOT EXISTS idx_agent_recommend_item_batch ON agent_recommend_item(batch_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_recommend_item_rank ON agent_recommend_item(batch_id, rank);

-- 反馈表
CREATE TABLE IF NOT EXISTS agent_feedback (
    feedback_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    item_id VARCHAR(64),
    batch_id VARCHAR(64),
    action VARCHAR(20) NOT NULL,                     -- confirm/reject/modify
    parsed JSONB,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN agent_feedback.item_id IS '指向 recommend_item';
COMMENT ON COLUMN agent_feedback.action IS 'confirm/reject/modify';

-- 确认条目表
CREATE TABLE IF NOT EXISTS agent_confirmed_item (
    item_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    recommend_item_id VARCHAR(64),
    rspu_id VARCHAR(64) NOT NULL,
    spec JSONB,
    quantity INTEGER DEFAULT 1,
    status VARCHAR(20) DEFAULT 'confirmed',          -- confirmed/cancelled
    idempotency_key VARCHAR(128) NOT NULL,
    confirmed_by VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN agent_confirmed_item.idempotency_key IS '幂等键，防重复确认';
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_confirmed_item_idem ON agent_confirmed_item(idempotency_key);

-- 运行记录表
CREATE TABLE IF NOT EXISTS agent_run (
    run_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) DEFAULT 'running',            -- running/waiting_human/done/failed
    current_node VARCHAR(64),
    checkpoint_id VARCHAR(128),
    framework_version VARCHAR(32),
    error_code VARCHAR(64),
    started_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ
);
COMMENT ON COLUMN agent_run.status IS 'running/waiting_human/done/failed';
COMMENT ON COLUMN agent_run.checkpoint_id IS '框架 checkpoint 标识（断点恢复）';
CREATE INDEX IF NOT EXISTS idx_agent_run_session ON agent_run(session_id);

-- 报价快照表（V11；行快照只存售价口径，绝不存成本字段）
CREATE TABLE IF NOT EXISTS agent_quote (
    quote_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    items JSONB NOT NULL,
    list_total NUMERIC(14,2),
    price_rate NUMERIC(6,4),
    deal_total NUMERIC(14,2),
    status VARCHAR(20) DEFAULT 'generated',          -- generated/exported
    idempotency_key VARCHAR(128) NOT NULL,
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN agent_quote.items IS '行快照（售价口径，无 factoryPrice/cost/margin 任何成本字段）';
COMMENT ON COLUMN agent_quote.status IS 'generated（已生成）/exported（已导出为方案）';
COMMENT ON COLUMN agent_quote.idempotency_key IS '幂等键，防重复生成';
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_quote_idem ON agent_quote(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_agent_quote_session ON agent_quote(session_id);
