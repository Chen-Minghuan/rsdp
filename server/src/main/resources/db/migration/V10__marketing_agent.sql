-- ============================================================
-- V10 营销 Agent 数据层：会话 / 消息 / 需求版本 / 推荐批次与条目 /
-- 反馈 / 确认条目 / 运行记录（幂等写法）
-- ============================================================

-- 1. 会话表
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

-- 2. 消息表
CREATE TABLE IF NOT EXISTS agent_message (
    message_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64),                              -- 所属运行（用户消息可为空）
    client_message_id VARCHAR(64),                   -- 客户端幂等 ID
    role VARCHAR(20) NOT NULL,                       -- user/assistant/system
    message_type VARCHAR(20) DEFAULT 'text',         -- text/cards/requirement/notice
    content TEXT,
    metadata JSONB,                                  -- 卡片/引用等附加数据
    sequence_no BIGINT NOT NULL,                     -- 会话内单调递增序号
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN agent_message.client_message_id IS '客户端幂等 ID，非空时配合 session_id 唯一';
COMMENT ON COLUMN agent_message.role IS 'user/assistant/system';
COMMENT ON COLUMN agent_message.message_type IS 'text/cards/requirement/notice';
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_message_client ON agent_message(session_id, client_message_id);
CREATE INDEX IF NOT EXISTS idx_agent_message_session_seq ON agent_message(session_id, sequence_no);

-- 3. 需求版本表
CREATE TABLE IF NOT EXISTS agent_requirement_version (
    version_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    version_no INTEGER NOT NULL,
    constraints JSONB NOT NULL,                      -- 本版需求约束全量快照
    patch JSONB,                                     -- 本版应用的 operations 留痕
    source VARCHAR(20) NOT NULL,                     -- extract/followup/manual/feedback
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN agent_requirement_version.constraints IS '本版需求约束全量快照';
COMMENT ON COLUMN agent_requirement_version.patch IS '本版应用的 operations 留痕';
COMMENT ON COLUMN agent_requirement_version.source IS 'extract/followup/manual/feedback';
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_requirement_version_no ON agent_requirement_version(session_id, version_no);

-- 4. 推荐批次表
CREATE TABLE IF NOT EXISTS agent_recommend_batch (
    batch_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    version_no INTEGER NOT NULL,                     -- 对应需求版本号
    query_criteria JSONB,                            -- 实际下发的检索条件
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN agent_recommend_batch.query_criteria IS '实际下发的检索条件';

-- 5. 推荐条目表
CREATE TABLE IF NOT EXISTS agent_recommend_item (
    item_id VARCHAR(64) PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
    rspu_id VARCHAR(64) NOT NULL,
    rank INTEGER NOT NULL,                           -- 批次内排名
    rank_score NUMERIC(10,4),
    snapshot JSONB NOT NULL,                         -- 产品事实快照：名称/颜色/材质/retailPrice/尺寸等
    reason JSONB,                                    -- highlights + evidenceRefs
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN agent_recommend_item.snapshot IS '产品事实快照：名称/颜色/材质/retailPrice/尺寸等';
COMMENT ON COLUMN agent_recommend_item.reason IS '推荐理由：highlights + evidenceRefs';
CREATE INDEX IF NOT EXISTS idx_agent_recommend_item_batch ON agent_recommend_item(batch_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_recommend_item_rank ON agent_recommend_item(batch_id, rank);

-- 6. 反馈表
CREATE TABLE IF NOT EXISTS agent_feedback (
    feedback_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    item_id VARCHAR(64),                             -- 指向 recommend_item
    batch_id VARCHAR(64),
    action VARCHAR(20) NOT NULL,                     -- confirm/reject/modify
    parsed JSONB,                                    -- 解析后的反馈结构
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN agent_feedback.item_id IS '指向 recommend_item';
COMMENT ON COLUMN agent_feedback.action IS 'confirm/reject/modify';

-- 7. 确认条目表
CREATE TABLE IF NOT EXISTS agent_confirmed_item (
    item_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    recommend_item_id VARCHAR(64),                   -- 来源推荐条目
    rspu_id VARCHAR(64) NOT NULL,
    spec JSONB,                                      -- 确认规格（颜色/尺寸等）
    quantity INTEGER DEFAULT 1,
    status VARCHAR(20) DEFAULT 'confirmed',          -- confirmed/cancelled
    idempotency_key VARCHAR(128) NOT NULL,           -- 幂等键，防重复确认
    confirmed_by VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN agent_confirmed_item.idempotency_key IS '幂等键，防重复确认';
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_confirmed_item_idem ON agent_confirmed_item(idempotency_key);

-- 8. 运行记录表
CREATE TABLE IF NOT EXISTS agent_run (
    run_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) DEFAULT 'running',            -- running/waiting_human/done/failed
    current_node VARCHAR(64),                        -- 当前执行到的图节点
    checkpoint_id VARCHAR(128),                      -- 框架 checkpoint 标识（断点恢复）
    framework_version VARCHAR(32),                   -- Agent 框架版本
    error_code VARCHAR(64),
    started_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ
);
COMMENT ON COLUMN agent_run.status IS 'running/waiting_human/done/failed';
COMMENT ON COLUMN agent_run.checkpoint_id IS '框架 checkpoint 标识（断点恢复）';
CREATE INDEX IF NOT EXISTS idx_agent_run_session ON agent_run(session_id);
