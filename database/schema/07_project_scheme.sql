-- ============================================================
-- RSDP 基线 DDL · 07 项目与方案域（07_project_scheme.sql）
-- 包含表：project, scheme, scheme_item, scheme_candidate, favorite_folder, user_favorite, template_tag, product_collection, product_collection_item
-- 执行顺序：schema/ 目录按文件名 01 → 12 → 99 依次执行（编号即执行顺序，基线由原 V1__init_db.sql 按域拆分而来）
-- 同步约定：新增/修改本域表结构时须同步 ops/reset_db.sql，约定详见 database/README.md
-- ============================================================
-- 搭配方案主表
CREATE TABLE IF NOT EXISTS scheme (
    scheme_id VARCHAR(64) PRIMARY KEY,
    scheme_name VARCHAR(128) NOT NULL,
    room_type VARCHAR(32),                           -- 空间类型字典码
    budget_limit DECIMAL(18, 2),                     -- 预算上限
    total_price DECIMAL(18, 2),                      -- 方案总价
    factory_count INTEGER,                           -- 涉及工厂数
    max_lead_time_days INTEGER,                      -- 最长交期
    item_count INTEGER,                              -- 方案项数
    status VARCHAR(16) DEFAULT 'active',
    project_id VARCHAR(64),                          -- 所属设计项目（V4 并入）
    is_template BOOLEAN NOT NULL DEFAULT false,      -- 是否为方案模板（V4 并入）
    template_tags TEXT,                              -- 模板标签 JSON 数组（V4 并入）
    analysis_id VARCHAR(64),                         -- 来源户型图分析批次，可空（V36 并入）
    canvas_layout JSONB,                             -- 画布布局（搭配画布，V41 并入）：{"<schemeItemId>":{x,y,scale,z}}
    share_enabled BOOLEAN NOT NULL DEFAULT false,    -- 方案分享开关（V42 并入）
    share_expire_at TIMESTAMPTZ,                       -- 方案分享过期时间（V42 并入，NULL=永久有效）
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_scheme_created_by ON scheme(created_by, status);
-- 方案列表索引（V43）：listSchemes eq status + orderByDesc created_at，部分谓词与 @TableLogic 软删一致
CREATE INDEX IF NOT EXISTS idx_scheme_status_created ON scheme(status, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_scheme_project ON scheme(project_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_scheme_template ON scheme(is_template) WHERE is_template = true AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_scheme_name_user_active
    ON scheme(scheme_name, created_by)
    WHERE project_id IS NULL AND status = 'active' AND deleted_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_scheme_name_project_active
    ON scheme(scheme_name, project_id)
    WHERE project_id IS NOT NULL AND status = 'active' AND deleted_at IS NULL;

-- 搭配方案项表
CREATE TABLE IF NOT EXISTS scheme_item (
    scheme_item_id BIGSERIAL PRIMARY KEY,
    scheme_id VARCHAR(64) NOT NULL,
    rspu_id VARCHAR(64) NOT NULL,
    rsku_id VARCHAR(64) NOT NULL,
    factory_code VARCHAR(16) NOT NULL,
    factory_price TEXT,                              -- 加密存储
    lead_time_days INTEGER,
    moq INTEGER,
    quantity INTEGER DEFAULT 1,
    sort_order INTEGER DEFAULT 0,
    space_tag VARCHAR(32),                                  -- 空间覆盖标签（场景字典码，可空=跟随产品场景推导，V40 并入）
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    FOREIGN KEY (scheme_id) REFERENCES scheme(scheme_id),
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (rsku_id) REFERENCES rsku_supply(rsku_id),
    FOREIGN KEY (factory_code) REFERENCES factory_master(factory_code),
    CONSTRAINT chk_scheme_item_quantity CHECK (quantity >= 1)
);
CREATE INDEX IF NOT EXISTS idx_scheme_item_scheme ON scheme_item(scheme_id);
-- 方案明细排序索引（V43）：getSchemeDetail eq scheme_id + orderByAsc sort_order；
-- (scheme_id, sort_order) 无唯一约束、sort_order 可重复，加主键 scheme_item_id 第三列保证排序稳定
CREATE INDEX IF NOT EXISTS idx_scheme_item_scheme_sort ON scheme_item(scheme_id, sort_order, scheme_item_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_scheme_item_rspu ON scheme_item(rspu_id);

-- 产品集（管理员维护的主流搭配集合）
CREATE TABLE IF NOT EXISTS product_collection (
    collection_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    collection_code VARCHAR(32) UNIQUE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    category_codes JSONB,
    style_codes JSONB,
    target_segments JSONB,
    is_featured BOOLEAN DEFAULT false,
    sort_order INT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ
    -- created_by 外键跨域（09_user_team 在 07 之后执行），在 cross_domain_fk.sql 补加
);
CREATE INDEX IF NOT EXISTS idx_product_collection_status ON product_collection(status);
CREATE INDEX IF NOT EXISTS idx_product_collection_featured ON product_collection(is_featured, sort_order);

-- 产品集与 RSPU 关联
CREATE TABLE IF NOT EXISTS product_collection_item (
    id BIGSERIAL PRIMARY KEY,
    collection_id UUID NOT NULL,
    rspu_id VARCHAR(64) NOT NULL,
    sort_order INT DEFAULT 0,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (collection_id, rspu_id),
    FOREIGN KEY (collection_id) REFERENCES product_collection(collection_id) ON DELETE CASCADE,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id)
);
CREATE INDEX IF NOT EXISTS idx_collection_item_collection ON product_collection_item(collection_id);
CREATE INDEX IF NOT EXISTS idx_collection_item_rspu ON product_collection_item(rspu_id);

-- AI 推荐候选清单
CREATE TABLE IF NOT EXISTS scheme_candidate (
    candidate_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recommend_request_id UUID NOT NULL,
    rspu_id VARCHAR(64) NOT NULL,
    rsku_id VARCHAR(64),
    score DECIMAL(5,4),
    ai_reason TEXT,
    match_factors JSONB,
    status VARCHAR(16) DEFAULT 'pending',
    created_by VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ,
    FOREIGN KEY (rspu_id) REFERENCES rspu_master(rspu_id),
    FOREIGN KEY (rsku_id) REFERENCES rsku_supply(rsku_id)
);
CREATE INDEX IF NOT EXISTS idx_scheme_candidate_request ON scheme_candidate(recommend_request_id, status);
CREATE INDEX IF NOT EXISTS idx_scheme_candidate_rspu ON scheme_candidate(rspu_id);
CREATE INDEX IF NOT EXISTS idx_scheme_candidate_created_by ON scheme_candidate(created_by, status);

-- 收藏夹（V4 并入）：用户级产品收藏，支持分组
CREATE TABLE IF NOT EXISTS user_favorite (
    favorite_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,                       -- sys_user 外键跨域（09 在 07 之后执行），在 cross_domain_fk.sql 补加
    rspu_id VARCHAR(64) NOT NULL REFERENCES rspu_master(rspu_id),
    group_name VARCHAR(64),
    folder_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, rspu_id)
);
CREATE INDEX IF NOT EXISTS idx_favorite_user ON user_favorite(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_favorite_folder ON user_favorite(folder_id);

-- 收藏夹文件夹（V14 并入）
CREATE TABLE IF NOT EXISTS favorite_folder (
    folder_id   VARCHAR(64) PRIMARY KEY,
    user_id     VARCHAR(64) NOT NULL,                       -- sys_user 外键跨域（09 在 07 之后执行），在 cross_domain_fk.sql 补加
    folder_name VARCHAR(64) NOT NULL,
    sort_order  INT NOT NULL DEFAULT 0,
    deleted_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_favorite_folder_user ON favorite_folder(user_id) WHERE deleted_at IS NULL;

-- 模板标签（V14 并入）：受控字典，scheme.template_tags 存名称 JSON，以名称为业务键
CREATE TABLE IF NOT EXISTS template_tag (
    tag_id     VARCHAR(64) PRIMARY KEY,
    tag_name   VARCHAR(64) NOT NULL UNIQUE,
    sort_order INT NOT NULL DEFAULT 0,
    enabled    BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 设计项目（V4 并入）
CREATE TABLE IF NOT EXISTS project (
    project_id VARCHAR(64) PRIMARY KEY,
    project_name VARCHAR(128) NOT NULL,
    project_type VARCHAR(32),
    company_name VARCHAR(128),
    owner_id VARCHAR(64) NOT NULL,                       -- sys_user 外键跨域（09 在 07 之后执行），在 cross_domain_fk.sql 补加
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    remark VARCHAR(512),
    share_enabled BOOLEAN NOT NULL DEFAULT false,
    share_expire_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_project_owner ON project(owner_id) WHERE deleted_at IS NULL;

