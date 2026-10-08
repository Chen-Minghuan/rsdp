-- ============================================================
-- V19: 营销 Agent 户型绑定（W2 户型驱动）
-- agent_session 绑定已确认户型与当前房间；需求版本/推荐批次/确认条目增加房间维度；
-- scheme_item 增加 floor_plan_room_id（方案条目精确到"主卧"而非笼统的 BEDROOM）。
-- 房间维度全部可空：无户型会话（纯口头描述）行为不变。
-- ============================================================

ALTER TABLE agent_session ADD COLUMN IF NOT EXISTS floor_plan_analysis_id VARCHAR(64);
ALTER TABLE agent_session ADD COLUMN IF NOT EXISTS active_room_id VARCHAR(64);
COMMENT ON COLUMN agent_session.floor_plan_analysis_id IS '绑定的已确认户型分析 ID（V19，可空=纯口头描述会话）';
COMMENT ON COLUMN agent_session.active_room_id IS '当前正在选品的房间（V19，可空=未绑定户型或未选择）';

ALTER TABLE agent_requirement_version ADD COLUMN IF NOT EXISTS room_id VARCHAR(64);
COMMENT ON COLUMN agent_requirement_version.room_id IS '需求作用域（V19）：NULL=全屋/全局需求，否则为该房间专属需求';

ALTER TABLE agent_recommend_batch ADD COLUMN IF NOT EXISTS room_id VARCHAR(64);
COMMENT ON COLUMN agent_recommend_batch.room_id IS '本批推荐面向的房间（V19，可空=无户型会话）';

ALTER TABLE agent_confirmed_item ADD COLUMN IF NOT EXISTS room_id VARCHAR(64);
COMMENT ON COLUMN agent_confirmed_item.room_id IS '确认产品归属的房间（V19，来自推荐批次，可空=无户型会话）';

ALTER TABLE scheme_item ADD COLUMN IF NOT EXISTS floor_plan_room_id VARCHAR(64);
COMMENT ON COLUMN scheme_item.floor_plan_room_id IS '方案条目归属的户型房间（V19，区分主卧/次卧等实际空间，可空）';

CREATE INDEX IF NOT EXISTS idx_agent_session_floor_plan ON agent_session(floor_plan_analysis_id);
CREATE INDEX IF NOT EXISTS idx_agent_requirement_room ON agent_requirement_version(session_id, room_id);

-- 外键：户型/房间均为软删表，物理删除默认 RESTRICT 不影响软删链路
ALTER TABLE agent_session DROP CONSTRAINT IF EXISTS fk_agent_session_floor_plan;
ALTER TABLE agent_session ADD CONSTRAINT fk_agent_session_floor_plan
    FOREIGN KEY (floor_plan_analysis_id) REFERENCES floor_plan_analysis(analysis_id);
ALTER TABLE agent_session DROP CONSTRAINT IF EXISTS fk_agent_session_active_room;
ALTER TABLE agent_session ADD CONSTRAINT fk_agent_session_active_room
    FOREIGN KEY (active_room_id) REFERENCES floor_plan_room(room_id);
ALTER TABLE agent_requirement_version DROP CONSTRAINT IF EXISTS fk_agent_requirement_room;
ALTER TABLE agent_requirement_version ADD CONSTRAINT fk_agent_requirement_room
    FOREIGN KEY (room_id) REFERENCES floor_plan_room(room_id);
ALTER TABLE agent_recommend_batch DROP CONSTRAINT IF EXISTS fk_agent_batch_room;
ALTER TABLE agent_recommend_batch ADD CONSTRAINT fk_agent_batch_room
    FOREIGN KEY (room_id) REFERENCES floor_plan_room(room_id);
ALTER TABLE agent_confirmed_item DROP CONSTRAINT IF EXISTS fk_agent_confirmed_room;
ALTER TABLE agent_confirmed_item ADD CONSTRAINT fk_agent_confirmed_room
    FOREIGN KEY (room_id) REFERENCES floor_plan_room(room_id);
ALTER TABLE scheme_item DROP CONSTRAINT IF EXISTS fk_scheme_item_floor_plan_room;
ALTER TABLE scheme_item ADD CONSTRAINT fk_scheme_item_floor_plan_room
    FOREIGN KEY (floor_plan_room_id) REFERENCES floor_plan_room(room_id);
