package com.rsdp.security;

/**
 * 系统权限字符串常量。
 *
 * <p>格式：{@code 资源:操作}。权限判断统一使用这些常量，避免硬编码扩散。</p>
 */
public final class Permissions {

    private Permissions() {
    }

    // 产品
    public static final String PRODUCT_READ = "product:read";
    public static final String PRODUCT_CREATE = "product:create";
    public static final String PRODUCT_UPDATE = "product:update";
    public static final String PRODUCT_DELETE = "product:delete";
    public static final String PRODUCT_REVIEW = "product:review";
    public static final String PRODUCT_IMPORT = "product:import";

    // 工厂
    public static final String FACTORY_READ = "factory:read";
    public static final String FACTORY_CREATE = "factory:create";
    public static final String FACTORY_UPDATE = "factory:update";
    public static final String FACTORY_DELETE = "factory:delete";

    // RSKU 报价
    public static final String RSKU_READ = "rsku:read";
    public static final String RSKU_CREATE = "rsku:create";
    public static final String RSKU_UPDATE = "rsku:update";
    public static final String RSKU_DELETE = "rsku:delete";
    public static final String RSKU_IMPORT = "rsku:import";

    // 报价单
    public static final String QUOTE_READ = "quote:read";
    public static final String QUOTE_GENERATE = "quote:generate";
    public static final String QUOTE_EXPORT = "quote:export";

    // 搭配方案
    public static final String SCHEME_READ = "scheme:read";
    public static final String SCHEME_CREATE = "scheme:create";
    public static final String SCHEME_UPDATE = "scheme:update";
    public static final String SCHEME_DELETE = "scheme:delete";

    // 字典
    public static final String DICT_CREATE = "dict:create";

    // 用户管理
    public static final String USER_READ = "user:read";
    public static final String USER_CREATE = "user:create";
    public static final String USER_UPDATE = "user:update";
    public static final String USER_DELETE = "user:delete";
    public static final String USER_RESET_PASSWORD = "user:reset-password";

    // 产品集
    public static final String COLLECTION_READ = "collection:read";
    public static final String COLLECTION_CREATE = "collection:create";
    public static final String COLLECTION_UPDATE = "collection:update";
    public static final String COLLECTION_DELETE = "collection:delete";

    // 工厂产品能力
    public static final String CAPABILITY_READ = "capability:read";
    public static final String CAPABILITY_CREATE = "capability:create";
    public static final String CAPABILITY_UPDATE = "capability:update";
    public static final String CAPABILITY_DELETE = "capability:delete";

    // 设计师画像
    public static final String DESIGNER_PROFILE_READ = "designer:profile:read";
    public static final String DESIGNER_PROFILE_UPDATE = "designer:profile:update";

    // 推荐打分配置
    public static final String RECOMMENDATION_SCORE_CONFIG_READ = "recommendation:score:config:read";
    public static final String RECOMMENDATION_SCORE_CONFIG_UPDATE = "recommendation:score:config:update";

    // AI 推荐候选
    public static final String SCHEME_CANDIDATE_READ = "scheme:candidate:read";
    public static final String SCHEME_CANDIDATE_CREATE = "scheme:candidate:create";
    public static final String SCHEME_CANDIDATE_UPDATE = "scheme:candidate:update";
    public static final String SCHEME_CANDIDATE_DELETE = "scheme:candidate:delete";

    // 设计项目
    public static final String PROJECT_READ = "project:read";
    public static final String PROJECT_CREATE = "project:create";
    public static final String PROJECT_UPDATE = "project:update";
    public static final String PROJECT_DELETE = "project:delete";

    // 管理后台
    public static final String ADMIN_ASYNC_METRICS = "admin:async-metrics";
    public static final String ADMIN_VECTOR_BACKFILL = "admin:vector-backfill";
}
