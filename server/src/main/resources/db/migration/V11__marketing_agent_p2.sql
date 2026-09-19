-- ============================================================
-- V11 营销 Agent P2：Skill 溯源 / 配套分组 / 报价快照
-- ============================================================

-- 1. 推荐批次：Skill 溯源 + 批次类型
ALTER TABLE agent_recommend_batch
    ADD COLUMN IF NOT EXISTS skill_id VARCHAR(64),           -- 执行推荐的 Skill，如 living-room-matching
    ADD COLUMN IF NOT EXISTS skill_version VARCHAR(32),      -- Skill 版本，如 1.0.0
    ADD COLUMN IF NOT EXISTS batch_type VARCHAR(20) DEFAULT 'primary';  -- primary（主体选品）/companion（配套推荐）
COMMENT ON COLUMN agent_recommend_batch.skill_id IS '执行推荐的 Skill ID（如 living-room-matching），空=非 Skill 链路';
COMMENT ON COLUMN agent_recommend_batch.skill_version IS 'Skill 版本号（如 1.0.0），推荐可追溯';
COMMENT ON COLUMN agent_recommend_batch.batch_type IS 'primary（主体选品）/companion（配套推荐）';

-- 2. 推荐条目：配套分组标签（品类码 TB/FS/FC 等）
ALTER TABLE agent_recommend_item
    ADD COLUMN IF NOT EXISTS group_tag VARCHAR(64);
COMMENT ON COLUMN agent_recommend_item.group_tag IS '配套分组标签（品类码 TB/FS/FC 等），主体选品为空';

-- 3. 报价快照表（HITL 后生成；行快照只存售价口径，绝不存成本字段）
CREATE TABLE IF NOT EXISTS agent_quote (
    quote_id VARCHAR(64) PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    items JSONB NOT NULL,                            -- 行快照：confirmedItemId/rspuId/rskuId/名称/主图/数量/标准售价/价格来源/小计/交期
    list_total NUMERIC(14,2),                        -- 标准售价合计
    price_rate NUMERIC(6,4),                         -- 生效折扣率快照
    deal_total NUMERIC(14,2),                        -- 预计成交合计（list_total × price_rate）
    status VARCHAR(20) DEFAULT 'generated',          -- generated/exported
    idempotency_key VARCHAR(128) NOT NULL,           -- 幂等键，防重复生成
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN agent_quote.items IS '行快照（售价口径，无 factoryPrice/cost/margin 任何成本字段）';
COMMENT ON COLUMN agent_quote.status IS 'generated（已生成）/exported（已导出为方案）';
COMMENT ON COLUMN agent_quote.idempotency_key IS '幂等键，防重复生成';
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_quote_idem ON agent_quote(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_agent_quote_session ON agent_quote(session_id);
