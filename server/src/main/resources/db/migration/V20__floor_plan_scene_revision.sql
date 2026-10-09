-- V20：CAD Scene Revision 模型（设计文档 V1.2.3 §8 Target Scene/Revision、§41 数据库长期建议）
-- 1) floor_plan_analysis 增加 active/working/latest Revision 指针与 CAD 原文件对象键
-- 2) floor_plan_room 增加 analysis_revision / confirmation_status（Revision scoped 对象）
-- 3) 新增 floor_plan_scene_revision（Revision 头表 + 不可变自动解析快照）
-- 4) 新增 floor_plan_furniture（Revision scoped 家具对象）
-- 5) 新增 cad_analysis_feedback（人工校正 Diff 反馈，算法改进材料）
-- 6) 存量回填：每条现存 floor_plan_analysis 建 Revision 1（trigger=LEGACY_IMPORT）
-- 同步约定：结构部分已同步 database/schema/06_floor_plan.sql 与 database/ops/reset_db.sql（三处一致）
-- 幂等：IF NOT EXISTS / WHERE NOT EXISTS 守卫，可重复执行。

-- 1) 分析头表 Revision 指针（§8.4.3）+ CAD 原文件对象键（target-scene 重解析用）
ALTER TABLE floor_plan_analysis ADD COLUMN IF NOT EXISTS active_analysis_revision INTEGER;
ALTER TABLE floor_plan_analysis ADD COLUMN IF NOT EXISTS working_analysis_revision INTEGER;
ALTER TABLE floor_plan_analysis ADD COLUMN IF NOT EXISTS latest_analysis_revision INTEGER;
ALTER TABLE floor_plan_analysis ADD COLUMN IF NOT EXISTS cad_object_key VARCHAR(256);
COMMENT ON COLUMN floor_plan_analysis.active_analysis_revision IS '当前业务正式使用的 Scene Revision（仅 Scene Activation 切换）';
COMMENT ON COLUMN floor_plan_analysis.working_analysis_revision IS '当前正在编辑/确认的工作 Revision，可空';
COMMENT ON COLUMN floor_plan_analysis.latest_analysis_revision IS '已创建的最大 Revision 序号';
COMMENT ON COLUMN floor_plan_analysis.cad_object_key IS 'CAD 原文件存储对象键（target-scene 切换重解析读取用；视觉通道为空）';

-- 2) 房间对象 Revision 化（§8.4.1：所有读写必须带 analysis_id + analysis_revision 双条件）
ALTER TABLE floor_plan_room ADD COLUMN IF NOT EXISTS analysis_revision INTEGER NOT NULL DEFAULT 1;
ALTER TABLE floor_plan_room ADD COLUMN IF NOT EXISTS confirmation_status VARCHAR(16) NOT NULL DEFAULT 'AUTO';
COMMENT ON COLUMN floor_plan_room.analysis_revision IS '所属 Scene Revision（逻辑键 analysis_id + analysis_revision + room_id）';
COMMENT ON COLUMN floor_plan_room.confirmation_status IS '确认状态：AUTO（自动识别未确认）/ CONFIRMED（已人工确认）';
CREATE INDEX IF NOT EXISTS idx_fpr_analysis_revision ON floor_plan_room(analysis_id, analysis_revision) WHERE deleted_at IS NULL;

-- 3) Scene Revision 头表（§8.4.4）：raw_result 为本 Revision 不可变自动快照（不含预览 pngBase64）
CREATE TABLE IF NOT EXISTS floor_plan_scene_revision (
    analysis_id              VARCHAR(64) NOT NULL,
    analysis_revision        INTEGER NOT NULL,
    parent_analysis_revision INTEGER,
    revision_trigger         VARCHAR(32) NOT NULL,        -- INITIAL_PARSE/TARGET_SCENE_SWITCH/MANUAL_EDIT/REPARSE/LEGACY_IMPORT
    target_scene_id          VARCHAR(128),
    parse_status             VARCHAR(16),                 -- SUCCESS/PARTIAL/NEEDS_REVIEW/FAILED（视觉通道为 NULL）
    revision_status          VARCHAR(24) NOT NULL DEFAULT 'PROCESSING', -- PROCESSING/READY/EDITING/READY_TO_ACTIVATE/ACTIVE/FAILED/SUPERSEDED
    room_revision            INTEGER NOT NULL DEFAULT 1,
    furniture_revision       INTEGER NOT NULL DEFAULT 1,
    room_submit_base         INTEGER,
    room_submit_hash         VARCHAR(64),
    furniture_submit_base    INTEGER,
    furniture_submit_hash    VARCHAR(64),
    parser_version           VARCHAR(64),
    parser_config_hash       VARCHAR(64),
    source_profile_version   VARCHAR(64),
    scene_version            VARCHAR(16),
    unit_resolution          JSONB,
    raw_result               JSONB,                       -- 本 Revision 不可变自动解析快照（不含预览 pngBase64）
    preview_image_id         VARCHAR(64),
    quality_issues           JSONB,
    target_scene_candidates  JSONB,
    drawing_bounds           JSONB,
    preview_bounds           JSONB,
    created_by               VARCHAR(64),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    ready_at                 TIMESTAMPTZ,
    confirmed_at             TIMESTAMPTZ,
    activated_at             TIMESTAMPTZ,
    activated_by             VARCHAR(64),
    PRIMARY KEY (analysis_id, analysis_revision),
    FOREIGN KEY (analysis_id) REFERENCES floor_plan_analysis(analysis_id),
    FOREIGN KEY (preview_image_id) REFERENCES image_assets(image_id)
);
COMMENT ON TABLE floor_plan_scene_revision IS '户型 Scene Revision 头表：每个 Analysis Revision 的自动解析快照/指针/对象乐观版本（设计文档 V1.2.3 §8.4.4）';
COMMENT ON COLUMN floor_plan_scene_revision.raw_result IS '本 Revision 不可变自动解析快照（READY 后冻结；不含预览 pngBase64）';
COMMENT ON COLUMN floor_plan_scene_revision.revision_trigger IS 'Revision 来源：INITIAL_PARSE/TARGET_SCENE_SWITCH/MANUAL_EDIT/REPARSE/LEGACY_IMPORT';

-- 4) Revision scoped 家具对象表（§41：按 Analysis Revision 保存的 Scene Object 表）
CREATE TABLE IF NOT EXISTS floor_plan_furniture (
    furniture_id           VARCHAR(64) PRIMARY KEY,
    analysis_id            VARCHAR(64) NOT NULL,
    analysis_revision      INTEGER NOT NULL,
    source_kind            VARCHAR(16),                   -- INSERT/EXPLODED/MANUAL
    category               VARCHAR(16),                   -- SOFA/BED/TABLE/CHAIR/CABINET/OTHER/UNKNOWN
    semantic_label         VARCHAR(128),
    room_id                VARCHAR(64),
    block_name             VARCHAR(255),
    source_handles         JSONB,
    insert_path            JSONB,
    position               JSONB,                         -- {"x":..,"y":..} 毫米
    rotation_deg           NUMERIC(9,2),
    footprint_polygon      JSONB,                         -- [[x,y],...] 闭合多边形，毫米
    geometry_width_mm      INTEGER,
    geometry_depth_mm      INTEGER,
    annotated_width_mm     INTEGER,
    annotated_depth_mm     INTEGER,
    effective_width_mm     INTEGER,
    effective_depth_mm     INTEGER,
    height_mm              INTEGER,
    dimension_source       VARCHAR(32),
    semantic_source        VARCHAR(32),
    evidence               JSONB,
    quality_flags          JSONB,
    detection_confidence   VARCHAR(8),
    category_confidence    VARCHAR(8),
    confirmation_status    VARCHAR(16) NOT NULL DEFAULT 'AUTO', -- AUTO/CONFIRMED/MANUAL_ADDED/MANUAL_EDITED
    matched_from_object_id VARCHAR(64),
    derived_from_ids       JSONB,
    sort_order             INTEGER NOT NULL DEFAULT 0,
    created_by             VARCHAR(64),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ,
    deleted_at             TIMESTAMPTZ,
    FOREIGN KEY (analysis_id) REFERENCES floor_plan_analysis(analysis_id)
);
COMMENT ON TABLE floor_plan_furniture IS '户型 Scene 家具对象表（Revision scoped；逻辑键 analysis_id + analysis_revision + furniture_id）';
CREATE INDEX IF NOT EXISTS idx_fpf_analysis_revision ON floor_plan_furniture(analysis_id, analysis_revision) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_fpf_analysis_revision_id ON floor_plan_furniture(analysis_id, analysis_revision, furniture_id);

-- 5) CAD 分析反馈表（§28.2 Recognition Diff：只记录变化，不反向修改原始结果）
CREATE TABLE IF NOT EXISTS cad_analysis_feedback (
    feedback_id       VARCHAR(64) PRIMARY KEY,
    analysis_id       VARCHAR(64) NOT NULL,
    analysis_revision INTEGER NOT NULL,
    parser_version    VARCHAR(64),
    object_type       VARCHAR(16) NOT NULL,               -- ROOM/FURNITURE
    object_id         VARCHAR(64),
    field_name        VARCHAR(64),
    predicted_value   TEXT,
    confirmed_value   TEXT,
    feedback_type     VARCHAR(24) NOT NULL,               -- ADD/DELETE/UPDATE/SPLIT/MERGE
    detail            JSONB,
    created_by        VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE cad_analysis_feedback IS 'CAD 分析人工校正 Diff 反馈（ADD/DELETE/UPDATE/SPLIT/MERGE），算法改进材料';
CREATE INDEX IF NOT EXISTS idx_caf_analysis_revision ON cad_analysis_feedback(analysis_id, analysis_revision);

-- 6) 存量回填（§41 存量数据回填与切换）：每条现存分析建 Revision 1（LEGACY_IMPORT，
--    快照复制当时可见的 raw_result/preview_image_id/quality_issues，bounds 从 raw_result 提取）
INSERT INTO floor_plan_scene_revision (
    analysis_id, analysis_revision, revision_trigger, revision_status,
    room_revision, furniture_revision,
    raw_result, preview_image_id, quality_issues, drawing_bounds, preview_bounds,
    created_by, created_at, ready_at, confirmed_at, activated_at, activated_by)
SELECT a.analysis_id, 1, 'LEGACY_IMPORT',
       CASE a.status
           WHEN 'confirmed' THEN 'ACTIVE'
           WHEN 'awaiting_confirm' THEN 'READY'
           WHEN 'failed' THEN 'FAILED'
           ELSE 'PROCESSING' END,
       CASE WHEN a.status = 'confirmed' THEN 2 ELSE 1 END,
       1,
       a.raw_result,
       a.preview_image_id,
       a.quality_issues,
       a.raw_result -> 'drawingBounds',
       COALESCE(a.raw_result -> 'preview' -> 'bounds', a.raw_result -> 'drawingBounds'),
       a.created_by,
       COALESCE(a.created_at, now()),
       CASE WHEN a.status IN ('awaiting_confirm', 'confirmed') THEN a.created_at END,
       CASE WHEN a.status = 'confirmed' THEN a.created_at END,
       CASE WHEN a.status = 'confirmed' THEN a.created_at END,
       CASE WHEN a.status = 'confirmed' THEN a.created_by END
FROM floor_plan_analysis a
WHERE NOT EXISTS (SELECT 1 FROM floor_plan_scene_revision r
                  WHERE r.analysis_id = a.analysis_id AND r.analysis_revision = 1);

-- Revision 指针回填：confirmed→active=1/working=NULL；awaiting_confirm→active=NULL/working=1；
-- pending/analyzing/failed→指针 NULL；全部 latest=1
UPDATE floor_plan_analysis a
SET latest_analysis_revision = 1,
    active_analysis_revision = CASE WHEN a.status = 'confirmed' THEN 1 END,
    working_analysis_revision = CASE WHEN a.status = 'awaiting_confirm' THEN 1 END
WHERE a.latest_analysis_revision IS NULL;

-- 已确认分析的房间 confirmation_status=CONFIRMED（analysis_revision 由列 DEFAULT 落 1）；其余保持 AUTO
UPDATE floor_plan_room r
SET confirmation_status = 'CONFIRMED'
FROM floor_plan_analysis a
WHERE r.analysis_id = a.analysis_id
  AND a.status = 'confirmed'
  AND r.confirmation_status = 'AUTO';
