-- ============================================================
-- RSDP 基线 DDL · 06 户型图域（06_floor_plan.sql）
-- 包含表：floor_plan_analysis, floor_plan_room, floor_plan_scene_revision, floor_plan_furniture, cad_analysis_feedback
-- 执行顺序：schema/ 目录按文件名 01 → 12 → 99 依次执行（编号即执行顺序，基线由原 V1__init_db.sql 按域拆分而来）
-- 同步约定：新增/修改本域表结构时须同步 ops/reset_db.sql，约定详见 database/README.md
-- ============================================================
-- 户型图分析批次表（V36 并入：一次上传一条）
CREATE TABLE IF NOT EXISTS floor_plan_analysis (
    analysis_id      VARCHAR(64) PRIMARY KEY,
    image_id         VARCHAR(64) NOT NULL,             -- 户型原图，指向 image_assets
    status           VARCHAR(16) NOT NULL DEFAULT 'pending',  -- pending/analyzing/awaiting_confirm/confirmed/failed
    task_id          VARCHAR(64),                      -- 关联 async_task
    raw_result       JSONB,                            -- AI 原始识别结果（不动，留档）
    confirmed_rooms  JSONB,                            -- 人工校正后的空间列表（最终生效数据）
    scale_ratio      DECIMAL(10,4),                    -- 识别/人工确认的比例尺（像素:实际mm），可空
    source           VARCHAR(16) NOT NULL DEFAULT 'admin',  -- admin（管理端）/ public（官网匿名）
    error_message    TEXT,
    created_by       VARCHAR(64),                      -- 官网匿名来源可空
    created_at       TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ,
    deleted_at       TIMESTAMPTZ,
    preview_image_id VARCHAR(64),                      -- V12：CAD 规范预览，指向 image_assets；视觉通道为空
    project_id       VARCHAR(64),                      -- V14：归属项目，可空（官网匿名/未归属）；project 在 07 域，FK 在 cross_domain_fk.sql 补加
    source_name      VARCHAR(128),                     -- V14：户型名称/备注，历史列表辨识用
    quality_issues   JSONB,                            -- V14：解析质量提示冗余列（由 raw_result 提升，落库时写入）
    active_analysis_revision  INTEGER,                 -- V20：当前业务正式使用的 Scene Revision（仅 Scene Activation 切换）
    working_analysis_revision INTEGER,                 -- V20：当前正在编辑/确认的工作 Revision，可空
    latest_analysis_revision  INTEGER,                 -- V20：已创建的最大 Revision 序号
    cad_object_key     VARCHAR(256),                   -- V20：CAD 原文件存储对象键（target-scene 重解析用；视觉通道为空）
    FOREIGN KEY (image_id) REFERENCES image_assets(image_id),
    FOREIGN KEY (preview_image_id) REFERENCES image_assets(image_id)
);
CREATE INDEX IF NOT EXISTS idx_fpa_created_by ON floor_plan_analysis(created_by, created_at) WHERE deleted_at IS NULL;
COMMENT ON COLUMN floor_plan_analysis.preview_image_id IS 'CAD 解析生成的规范 PNG 预览（与房间 polygon 共用 drawingBounds）；视觉识别通道为空';
COMMENT ON COLUMN floor_plan_analysis.project_id IS '归属项目（可空：官网匿名/未归属）；FK 见 cross_domain_fk.sql';
COMMENT ON COLUMN floor_plan_analysis.source_name IS '户型名称/备注（如"滨江华府 3-2-1 东边套"），历史列表辨识用';
COMMENT ON COLUMN floor_plan_analysis.quality_issues IS '解析质量提示数组（由 raw_result 冗余提升，落库时写入，支持过滤/统计）';
COMMENT ON COLUMN floor_plan_analysis.active_analysis_revision IS '当前业务正式使用的 Scene Revision（仅 Scene Activation 切换）';
COMMENT ON COLUMN floor_plan_analysis.working_analysis_revision IS '当前正在编辑/确认的工作 Revision，可空';
COMMENT ON COLUMN floor_plan_analysis.latest_analysis_revision IS '已创建的最大 Revision 序号';
COMMENT ON COLUMN floor_plan_analysis.cad_object_key IS 'CAD 原文件存储对象键（target-scene 切换重解析读取用；视觉通道为空）';
-- 项目维度历史查询索引（V14）
CREATE INDEX IF NOT EXISTS idx_fpa_project ON floor_plan_analysis(project_id, created_at DESC) WHERE deleted_at IS NULL;
-- 平台运营列表索引（V43）：运营查询无 created_by 条件、仅 orderByDesc created_at，左前缀索引用不上
CREATE INDEX IF NOT EXISTS idx_fpa_created ON floor_plan_analysis(created_at DESC) WHERE deleted_at IS NULL;

-- 户型图空间识别明细表（V36 并入：每个识别出的空间一行，人工校正就地更新）
CREATE TABLE IF NOT EXISTS floor_plan_room (
    room_id          VARCHAR(64) PRIMARY KEY,
    analysis_id      VARCHAR(64) NOT NULL,
    room_type        VARCHAR(32) NOT NULL,             -- 引用 room_type 字典（LIVING_ROOM/BEDROOM/...）
    bbox             JSONB,                            -- {x, y, w, h} 归一化坐标 [0,1]
    width_mm         INTEGER,                          -- 开间（人工校正后为准）
    depth_mm         INTEGER,                          -- 进深
    area_m2          DECIMAL(8,2),
    dimension_source VARCHAR(16),                      -- ocr_text / scale_calc / ai_estimate / manual / cad_geometry
    dimension_confidence VARCHAR(8) DEFAULT 'low',     -- high/mid/low
    dimension_text   VARCHAR(128),                     -- 图上尺寸标注原文
    sort_order       INTEGER DEFAULT 0,
    deleted_at       TIMESTAMPTZ,                        -- 人工删除误识别空间
    created_at       TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ,
    label            VARCHAR(128),                     -- 空间标签原文（V11 增量并入；未命名空间"未命名空间 N"）
    polygon          JSONB,                            -- 房间外环顶点，毫米坐标 [[x,y],...]（V11，CAD 通道）
    centroid         JSONB,                            -- 质心 {"x":..,"y":..}（V11，CAD 通道）
    geometry_source  VARCHAR(32),                      -- 几何来源（V11）：ai_vision / cad_geometry
    analysis_revision INTEGER NOT NULL DEFAULT 1,      -- V20：所属 Scene Revision（逻辑键 analysis_id + analysis_revision + room_id）
    confirmation_status VARCHAR(16) NOT NULL DEFAULT 'AUTO', -- V20：AUTO（自动识别未确认）/ CONFIRMED（已人工确认）
    FOREIGN KEY (analysis_id) REFERENCES floor_plan_analysis(analysis_id)
);
COMMENT ON COLUMN floor_plan_room.label IS '空间标签原文（CAD 通道落库；未命名空间为"未命名空间 N"）';
COMMENT ON COLUMN floor_plan_room.polygon IS '房间外环顶点，毫米坐标 [[x,y],...]，CAD 解析通道落库';
COMMENT ON COLUMN floor_plan_room.centroid IS '房间质心 {"x":..,"y":..}（毫米），CAD 解析通道落库';
COMMENT ON COLUMN floor_plan_room.geometry_source IS '几何来源：ai_vision / cad_geometry';
COMMENT ON COLUMN floor_plan_room.analysis_revision IS '所属 Scene Revision（逻辑键 analysis_id + analysis_revision + room_id）';
COMMENT ON COLUMN floor_plan_room.confirmation_status IS '确认状态：AUTO（自动识别未确认）/ CONFIRMED（已人工确认）';
CREATE INDEX IF NOT EXISTS idx_fpr_analysis ON floor_plan_room(analysis_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_fpr_analysis_revision ON floor_plan_room(analysis_id, analysis_revision) WHERE deleted_at IS NULL;

-- Scene Revision 头表（V20，设计文档 V1.2.3 §8.4.4）：raw_result 为本 Revision 不可变自动快照（不含预览 pngBase64）
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

-- Revision scoped 家具对象表（V20，§41：按 Analysis Revision 保存的 Scene Object 表）
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
    candidate_status      VARCHAR(16) NOT NULL DEFAULT 'VALID', -- P1-A：VALID/REJECTED（仅标记不删除）
    geometry_fingerprint  VARCHAR(16),                    -- P1 块定义几何指纹
    duplicate_group_id    VARCHAR(16),                    -- P1-B 去重组 ID
    duplicate_status      VARCHAR(16),                    -- P1-B：PRIMARY/DUPLICATE
    duplicate_count       INTEGER,                        -- P1-B 组内实例总数
    vision_group_id       VARCHAR(16),                    -- P1-C 视觉语义分组 ID
    vision_confidence     NUMERIC(4,3),                   -- P1-C 视觉识别置信度（0~1）
    FOREIGN KEY (analysis_id) REFERENCES floor_plan_analysis(analysis_id)
);
COMMENT ON COLUMN floor_plan_furniture.candidate_status IS 'P1-A 候选清洗状态：VALID / REJECTED（尺寸或几何无效，仅标记不删除）';
COMMENT ON COLUMN floor_plan_furniture.geometry_fingerprint IS 'P1 块定义几何指纹（局部坐标量化哈希），匿名块名碰撞防护';
COMMENT ON COLUMN floor_plan_furniture.duplicate_group_id IS 'P1-B 去重组 ID（同块+同几何+同旋转+同位置网格的多实例成组）';
COMMENT ON COLUMN floor_plan_furniture.duplicate_status IS 'P1-B 组内角色：PRIMARY（代表）/ DUPLICATE（重复副本，抑制展示不删除）';
COMMENT ON COLUMN floor_plan_furniture.duplicate_count IS 'P1-B 组内实例总数';
COMMENT ON COLUMN floor_plan_furniture.vision_group_id IS 'P1-C 视觉语义分组 ID（同块+同尺寸+同指纹，共享一次视觉识别回填全部实例）';
COMMENT ON COLUMN floor_plan_furniture.vision_confidence IS 'P1-C 视觉语义识别置信度（0~1，qwen3-vl-plus 返回）';
COMMENT ON TABLE floor_plan_furniture IS '户型 Scene 家具对象表（Revision scoped；逻辑键 analysis_id + analysis_revision + furniture_id）';
CREATE INDEX IF NOT EXISTS idx_fpf_analysis_revision ON floor_plan_furniture(analysis_id, analysis_revision) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_fpf_analysis_revision_id ON floor_plan_furniture(analysis_id, analysis_revision, furniture_id);
CREATE INDEX IF NOT EXISTS idx_fpf_vision_group ON floor_plan_furniture(analysis_id, analysis_revision, vision_group_id) WHERE deleted_at IS NULL;

-- CAD 分析反馈表（V20，§28.2 Recognition Diff：只记录变化，不反向修改原始结果）
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

