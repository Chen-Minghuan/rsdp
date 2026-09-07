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
    FOREIGN KEY (image_id) REFERENCES image_assets(image_id)
);
CREATE INDEX IF NOT EXISTS idx_fpa_created_by ON floor_plan_analysis(created_by, created_at) WHERE deleted_at IS NULL;
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
    dimension_source VARCHAR(16),                      -- ocr_text / scale_calc / ai_estimate / manual
    dimension_confidence VARCHAR(8) DEFAULT 'low',     -- high/mid/low
    dimension_text   VARCHAR(128),                     -- 图上尺寸标注原文
    sort_order       INTEGER DEFAULT 0,
    deleted_at       TIMESTAMPTZ,                        -- 人工删除误识别空间
    created_at       TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ,
    FOREIGN KEY (analysis_id) REFERENCES floor_plan_analysis(analysis_id)
);
CREATE INDEX IF NOT EXISTS idx_fpr_analysis ON floor_plan_room(analysis_id) WHERE deleted_at IS NULL;

