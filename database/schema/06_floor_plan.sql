-- ============================================================
-- RSDP 基线 DDL · 06 户型图域（06_floor_plan.sql）
-- 包含表：floor_plan_analysis, floor_plan_room
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
    FOREIGN KEY (image_id) REFERENCES image_assets(image_id),
    FOREIGN KEY (preview_image_id) REFERENCES image_assets(image_id)
);
CREATE INDEX IF NOT EXISTS idx_fpa_created_by ON floor_plan_analysis(created_by, created_at) WHERE deleted_at IS NULL;
COMMENT ON COLUMN floor_plan_analysis.preview_image_id IS 'CAD 解析生成的规范 PNG 预览（与房间 polygon 共用 drawingBounds）；视觉识别通道为空';
COMMENT ON COLUMN floor_plan_analysis.project_id IS '归属项目（可空：官网匿名/未归属）；FK 见 cross_domain_fk.sql';
COMMENT ON COLUMN floor_plan_analysis.source_name IS '户型名称/备注（如"滨江华府 3-2-1 东边套"），历史列表辨识用';
COMMENT ON COLUMN floor_plan_analysis.quality_issues IS '解析质量提示数组（由 raw_result 冗余提升，落库时写入，支持过滤/统计）';
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
    FOREIGN KEY (analysis_id) REFERENCES floor_plan_analysis(analysis_id)
);
COMMENT ON COLUMN floor_plan_room.label IS '空间标签原文（CAD 通道落库；未命名空间为"未命名空间 N"）';
COMMENT ON COLUMN floor_plan_room.polygon IS '房间外环顶点，毫米坐标 [[x,y],...]，CAD 解析通道落库';
COMMENT ON COLUMN floor_plan_room.centroid IS '房间质心 {"x":..,"y":..}（毫米），CAD 解析通道落库';
COMMENT ON COLUMN floor_plan_room.geometry_source IS '几何来源：ai_vision / cad_geometry';
CREATE INDEX IF NOT EXISTS idx_fpr_analysis ON floor_plan_room(analysis_id) WHERE deleted_at IS NULL;

