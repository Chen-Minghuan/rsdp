-- RSDP V16 数据库迁移：字典别名表（dict_alias）
-- 背景：工厂 Excel 品类列为中文品名（茶桌/主椅/方凳…），与字典码（FS/SF/TB）不一致。
--       采用「分层解析 + 用户确认 + 别名自学习」方案：AI 归一并经用户确认的映射写回本表，
--       后续导入直接命中别名，不再调 AI。按 dict_type 通用化，风格/材质等字段可复用。
-- 决策文档：D:\文档\学习文档\项目中决策问题复盘\roomVip项目决策关键决策\2026-07-22-Excel导入品类中文名归一化方案选型.md
-- 幂等：IF NOT EXISTS / 唯一约束天然防重，可重复执行。

CREATE TABLE IF NOT EXISTS dict_alias (
    id          BIGSERIAL PRIMARY KEY,
    dict_type   VARCHAR(32) NOT NULL,              -- 字典类型，如 category/style/material
    alias_name  VARCHAR(64) NOT NULL,              -- 别名（工厂方言叫法，如「茶桌」「主椅」）
    dict_code   VARCHAR(16) NOT NULL,              -- 归一后的字典码，如 TB / FS
    source      VARCHAR(16) NOT NULL DEFAULT 'ai_confirmed',  -- ai_confirmed / manual
    created_by  VARCHAR(64),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_dict_alias UNIQUE (dict_type, alias_name)
);

CREATE INDEX IF NOT EXISTS idx_dict_alias_type ON dict_alias(dict_type);

COMMENT ON TABLE dict_alias IS '字典别名库：工厂方言叫法 → 字典码的持久化映射（导入确认后自学习积累）';
