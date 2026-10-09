-- V22：户型家具 P1（Scene-aware Block Fallback 后续）——候选清洗/去重分组/视觉语义分组字段
-- 契约来源：rsdp-cad-parser P1（candidateStatus/duplicateGroupId/duplicateStatus/duplicateCount/
-- visionGroupId/geometryFingerprint），vision_confidence 由后端视觉语义回填。
-- 设计约定：REJECTED/DUPLICATE 仅标记不物理删除，供人工复核与 Scene 修正后恢复。

ALTER TABLE floor_plan_furniture
    ADD COLUMN IF NOT EXISTS candidate_status      VARCHAR(16) NOT NULL DEFAULT 'VALID',
    ADD COLUMN IF NOT EXISTS geometry_fingerprint  VARCHAR(16),
    ADD COLUMN IF NOT EXISTS duplicate_group_id    VARCHAR(16),
    ADD COLUMN IF NOT EXISTS duplicate_status      VARCHAR(16),
    ADD COLUMN IF NOT EXISTS duplicate_count       INTEGER,
    ADD COLUMN IF NOT EXISTS vision_group_id       VARCHAR(16),
    ADD COLUMN IF NOT EXISTS vision_confidence     NUMERIC(4,3);

COMMENT ON COLUMN floor_plan_furniture.candidate_status IS 'P1-A 候选清洗状态：VALID / REJECTED（尺寸或几何无效，仅标记不删除）';
COMMENT ON COLUMN floor_plan_furniture.geometry_fingerprint IS 'P1 块定义几何指纹（局部坐标量化哈希），匿名块名碰撞防护';
COMMENT ON COLUMN floor_plan_furniture.duplicate_group_id IS 'P1-B 去重组 ID（同块+同几何+同旋转+同位置网格的多实例成组）';
COMMENT ON COLUMN floor_plan_furniture.duplicate_status IS 'P1-B 组内角色：PRIMARY（代表）/ DUPLICATE（重复副本，抑制展示不删除）';
COMMENT ON COLUMN floor_plan_furniture.duplicate_count IS 'P1-B 组内实例总数';
COMMENT ON COLUMN floor_plan_furniture.vision_group_id IS 'P1-C 视觉语义分组 ID（同块+同尺寸+同指纹，共享一次视觉识别回填全部实例）';
COMMENT ON COLUMN floor_plan_furniture.vision_confidence IS 'P1-C 视觉语义识别置信度（0~1，qwen3-vl-plus 返回）';

CREATE INDEX IF NOT EXISTS idx_fpf_vision_group ON floor_plan_furniture(analysis_id, analysis_revision, vision_group_id) WHERE deleted_at IS NULL;
