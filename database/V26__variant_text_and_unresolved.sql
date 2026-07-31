-- ============================================================================
-- V19 迁移：变体原文列 + 唯一索引"码或原文"语义 + 未归一值采集表
-- ============================================================================
-- 背景（决策复盘：docs/../D盘 roomVip项目决策关键决策/2026-07-23-工厂方言治理）：
--   工厂报价单的尺寸/颜色/材质叫法是自由文本（方言），无法提前全部录入字典。
--   原"导入时强制字典码校验"导致「尺寸码不存在」整行失败，变体都建不了。
-- 决策：
--   1. 变体身份"原文为主、码为辅"：*_code 降级为可空的归一化索引字段；
--   2. 方言归一"导入后异步治理"：导入不阻断，未归一值采集后由运营渐进归一；
--   3. 分层模型：原文必存（*_text）、编码尽力（*_code 可空）、治理渐进（dict_unresolved_value）。
-- 变更内容：
--   1. rspu_variant 新增 size_text/color_text/material_text 工厂原文列；
--   2. uk_variant_attrs 唯一索引从"纯码三元组"改为"码或原文"（COALESCE(code, text, '')），
--      存量 *_text 全空时新旧索引计算值完全一致，迁移零冲突；
--   3. 新增 dict_unresolved_value 未归一值采集表（Phase 3 治理页面的数据源）。
-- 幂等：全部使用 IF NOT EXISTS / DO 块守卫，可重复执行。
-- 同步约定：V1__init_db.sql 与 reset_db.sql 已同步更新。
-- ============================================================================

-- 1. 变体原文列（工厂叫法原文，事实层）
ALTER TABLE rspu_variant ADD COLUMN IF NOT EXISTS size_text VARCHAR(64);       -- 尺寸/规格原文，如 "贵妃A位" "495*650*1180"
ALTER TABLE rspu_variant ADD COLUMN IF NOT EXISTS color_text VARCHAR(64);      -- 颜色原文
ALTER TABLE rspu_variant ADD COLUMN IF NOT EXISTS material_text VARCHAR(128);  -- 材质原文，如 "A级布" "半皮"

-- 2. 变体唯一索引改造：纯码三元组 → 码或原文（有码按码、无码按原文）
--    存量 *_text 为 NULL 时 COALESCE(code, text, '') 与旧索引 COALESCE(code, '') 等值，平滑替换
DROP INDEX IF EXISTS uk_variant_attrs;
CREATE UNIQUE INDEX IF NOT EXISTS uk_variant_attrs
    ON rspu_variant (
        rspu_id,
        COALESCE(size_code, size_text, ''),
        COALESCE(color_code, color_text, ''),
        COALESCE(material_code, material_text, '')
    )
    WHERE deleted_at IS NULL;

-- 3. 未归一值采集表：导入/录入时字典解析未命中的原文自动计数采集，
--    供运营在治理页面归并（写 dict_alias 自学习）或忽略
CREATE TABLE IF NOT EXISTS dict_unresolved_value (
    id BIGSERIAL PRIMARY KEY,
    dict_type VARCHAR(32) NOT NULL,                 -- 字典类型：size/color/material（可扩展 style 等）
    raw_value VARCHAR(128) NOT NULL,                -- 未归一的工厂原文
    occurrence_count INT NOT NULL DEFAULT 1,        -- 累计出现次数
    first_seen_at TIMESTAMP NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMP NOT NULL DEFAULT now(),
    last_batch_id VARCHAR(64),                      -- 最近出现的导入批次
    last_username VARCHAR(64),                      -- 最近操作人
    status VARCHAR(16) NOT NULL DEFAULT 'pending',  -- pending/resolved/ignored
    resolved_code VARCHAR(16),                      -- 归并后的字典码（resolved 时填写）
    resolved_by VARCHAR(64),
    resolved_at TIMESTAMP,
    CONSTRAINT uk_dict_unresolved UNIQUE (dict_type, raw_value)
);

CREATE INDEX IF NOT EXISTS idx_dict_unresolved_status ON dict_unresolved_value(status, dict_type);
