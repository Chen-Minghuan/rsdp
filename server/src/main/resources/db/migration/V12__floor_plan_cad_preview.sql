-- V12：CAD 解析生成与房间多边形同坐标系的规范预览图。
-- 原始上传文件仍由 image_id 引用；preview_image_id 仅指向派生 PNG，避免混淆原件与展示产物。
ALTER TABLE floor_plan_analysis
    ADD COLUMN IF NOT EXISTS preview_image_id VARCHAR(64);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'floor_plan_analysis_preview_image_id_fkey'
    ) THEN
        ALTER TABLE floor_plan_analysis
            ADD CONSTRAINT floor_plan_analysis_preview_image_id_fkey
            FOREIGN KEY (preview_image_id) REFERENCES image_assets(image_id);
    END IF;
END $$;

COMMENT ON COLUMN floor_plan_analysis.preview_image_id IS
    'CAD 解析生成的规范 PNG 预览（与房间 polygon 共用 drawingBounds）；视觉识别通道为空';
