-- V14：户型分析批次归属项目 + 名称辨识 + 质量提示列提升
-- 背景：历史列表按项目分组/缩略图展示需要 project 归属；quality_issues 由 raw_result 现场解析冗余提升为列，支持过滤统计
-- 同步约定：database/schema/06_floor_plan.sql + cross_domain_fk.sql 与 database/ops/reset_db.sql 已同步修改
ALTER TABLE floor_plan_analysis
    ADD COLUMN IF NOT EXISTS project_id VARCHAR(64),      -- 归属项目，可空（官网匿名/独立识别无项目）
    ADD COLUMN IF NOT EXISTS source_name VARCHAR(128),    -- 户型名称/备注，便于历史列表辨识
    ADD COLUMN IF NOT EXISTS quality_issues JSONB;        -- 解析质量提示冗余列（写入口在识别完成时，raw_result 不动）

COMMENT ON COLUMN floor_plan_analysis.project_id IS '归属项目（可空：官网匿名/未归属）；FK 见 cross_domain_fk.sql';
COMMENT ON COLUMN floor_plan_analysis.source_name IS '户型名称/备注（如"滨江华府 3-2-1 东边套"），历史列表辨识用';
COMMENT ON COLUMN floor_plan_analysis.quality_issues IS '解析质量提示数组（由 raw_result 冗余提升，落库时写入，支持过滤/统计）';

-- 项目维度历史查询索引（部分索引对齐既有风格）
CREATE INDEX IF NOT EXISTS idx_fpa_project
    ON floor_plan_analysis (project_id, created_at DESC)
    WHERE deleted_at IS NULL;

-- 外键（project 表在 07 域，基线中后置到 cross_domain_fk.sql；增量脚本此处直接幂等补加）
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_floor_plan_analysis_project'
    ) THEN
        ALTER TABLE floor_plan_analysis
            ADD CONSTRAINT fk_floor_plan_analysis_project
            FOREIGN KEY (project_id) REFERENCES project(project_id);
    END IF;
END $$;
