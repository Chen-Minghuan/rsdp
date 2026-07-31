-- 搭配方案名称唯一性约束（TC-SEC-012 / TC-PERF-003）
-- 个人方案按创建人维度去重，项目方案按项目维度去重，仅对未删除的 active 方案生效。
-- 若存量 active 方案已存在重复，需先清理后再执行本迁移。
CREATE UNIQUE INDEX IF NOT EXISTS uk_scheme_name_user_active
    ON scheme(scheme_name, created_by)
    WHERE project_id IS NULL AND status = 'active' AND deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_scheme_name_project_active
    ON scheme(scheme_name, project_id)
    WHERE project_id IS NOT NULL AND status = 'active' AND deleted_at IS NULL;
