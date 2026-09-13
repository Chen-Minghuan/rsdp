-- ============================================================
-- V6 下线从未写入的半成品列（阶段 4.5 决策）：
-- ① ai_recognition.model_version / prompt_version——零写入零消费，项目无 prompt 版本体系，
--    需要时带真实版本方案再加
-- ② image_assets.quality_score——零写入零消费，无评分器
-- 与 database/schema/04_image_ai.sql + ops/reset_db.sql 对应段保持一致（幂等写法）
-- ============================================================

ALTER TABLE ai_recognition DROP COLUMN IF EXISTS model_version;
ALTER TABLE ai_recognition DROP COLUMN IF EXISTS prompt_version;
ALTER TABLE image_assets DROP COLUMN IF EXISTS quality_score;
