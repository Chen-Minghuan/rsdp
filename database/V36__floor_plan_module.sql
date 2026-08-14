-- V36: 户型图分析模块（管理端，方案 docs/05-status/户型图空间搭配链路完整方案v3.0.md §3）
-- 幂等可重复执行；三处同步：本文件 + V1__init_db.sql + reset_db.sql
-- 注：相对方案 DDL 增加 floor_plan_analysis.deleted_at —— 接口 5 软删（@TableLogic 模式）需要该列

-- 户型图分析批次（一次上传一条）
CREATE TABLE IF NOT EXISTS floor_plan_analysis (
    analysis_id      VARCHAR(64) PRIMARY KEY,          -- FPA-<UUID>（IdGenerator）
    image_id         VARCHAR(64) NOT NULL,             -- 户型原图，指向 image_assets
    status           VARCHAR(16) NOT NULL DEFAULT 'pending',  -- pending/analyzing/awaiting_confirm/confirmed/failed
    task_id          VARCHAR(64),                      -- 关联 async_task
    raw_result       JSONB,                            -- AI 原始识别结果（不动，留档）
    confirmed_rooms  JSONB,                            -- 人工校正后的空间列表（最终生效数据）
    scale_ratio      DECIMAL(10,4),                    -- 识别/人工确认的比例尺（像素:实际mm），可空
    source           VARCHAR(16) NOT NULL DEFAULT 'admin',  -- admin（管理端）/ public（官网匿名）
    error_message    TEXT,
    created_by       VARCHAR(64),                      -- 官网匿名来源可空
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP,
    deleted_at       TIMESTAMP,                        -- 软删（接口 5，@TableLogic 模式）
    FOREIGN KEY (image_id) REFERENCES image_assets(image_id)
);
CREATE INDEX IF NOT EXISTS idx_fpa_created_by ON floor_plan_analysis(created_by, created_at) WHERE deleted_at IS NULL;

-- 空间识别明细（每个识别出的空间一行，人工校正就地更新）
CREATE TABLE IF NOT EXISTS floor_plan_room (
    room_id          VARCHAR(64) PRIMARY KEY,          -- FPR-<UUID>
    analysis_id      VARCHAR(64) NOT NULL,
    room_type        VARCHAR(32) NOT NULL,             -- 引用 room_type 字典（LIVING_ROOM/BEDROOM/...）
    bbox             JSONB,                            -- {x, y, w, h} 归一化坐标 [0,1]
    width_mm         INTEGER,                          -- 开间（人工校正后为准）
    depth_mm         INTEGER,                          -- 进深
    area_m2          DECIMAL(8,2),
    dimension_source VARCHAR(16),                      -- ocr_text / scale_calc / ai_estimate / manual
    dimension_confidence VARCHAR(8) DEFAULT 'low',     -- high/mid/low
    dimension_text   VARCHAR(128),                     -- 图上尺寸标注原文（如 "4200×3800"）
    sort_order       INTEGER DEFAULT 0,
    deleted_at       TIMESTAMP,                        -- 人工删除误识别空间
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP,
    FOREIGN KEY (analysis_id) REFERENCES floor_plan_analysis(analysis_id)
);
CREATE INDEX IF NOT EXISTS idx_fpr_analysis ON floor_plan_room(analysis_id) WHERE deleted_at IS NULL;

-- 搭配方案与户型分析的关联（方案溯源）
ALTER TABLE scheme ADD COLUMN IF NOT EXISTS analysis_id VARCHAR(64);
COMMENT ON COLUMN scheme.analysis_id IS '来源户型图分析批次，可空';
