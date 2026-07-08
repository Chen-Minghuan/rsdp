package com.rsdp.security.datascope;

import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.service.UserFactoryService;
import com.rsdp.service.UserRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 当前用户数据范围上下文。
 *
 * <p>对 {@link #currentDataScope()} 与 {@link #currentFactoryCodes()} 增加请求级
 * ThreadLocal 缓存，避免同一请求内多次查询用户角色/工厂关联。</p>
 */
@Component
@RequiredArgsConstructor
public class DataScopeContext {

    private static final Set<String> ADMIN_ROLES = Set.of("ADMIN");

    private final UserRoleService userRoleService;
    private final UserFactoryService userFactoryService;

    private final ThreadLocal<DataScopeCache> cacheHolder = new ThreadLocal<>();

    /**
     * 获取当前登录用户的数据范围。
     *
     * @return 数据范围
     */
    public DataScope currentDataScope() {
        DataScopeCache cache = getCache();
        if (cache.scope != null) {
            return cache.scope;
        }
        DataScope scope = computeDataScope();
        cache.scope = scope;
        return scope;
    }

    private DataScope computeDataScope() {
        String username = SecurityOperatorContext.currentUsername();
        if ("anonymous".equals(username)) {
            return DataScope.PUBLIC_ONLY;
        }

        List<String> roleCodes = userRoleService.getRoleCodesByUsername(username);
        if (roleCodes.isEmpty()) {
            return DataScope.PUBLIC_ONLY;
        }

        if (roleCodes.stream().anyMatch(ADMIN_ROLES::contains)) {
            return DataScope.ALL;
        }

        if (roleCodes.contains("FACTORY_ADMIN")) {
            return DataScope.FACTORY_LIST;
        }

        // DESIGNER 角色暂时按 ALL 只读处理；后续若需仅看自己创建的数据，
        // 需在 rsku_supply 等表补充 created_by 字段并改为 SELF_CREATED
        if (roleCodes.contains("DESIGNER")) {
            return DataScope.ALL;
        }

        return DataScope.PUBLIC_ONLY;
    }

    /**
     * 获取当前登录用户关联的工厂编码列表。
     *
     * @return 工厂编码列表
     */
    public List<String> currentFactoryCodes() {
        DataScopeCache cache = getCache();
        if (cache.factoryCodes != null) {
            return cache.factoryCodes;
        }
        List<String> factoryCodes = computeFactoryCodes();
        cache.factoryCodes = factoryCodes;
        return factoryCodes;
    }

    private List<String> computeFactoryCodes() {
        String username = SecurityOperatorContext.currentUsername();
        if ("anonymous".equals(username)) {
            return List.of();
        }
        return userFactoryService.getFactoryCodesByUsername(username);
    }

    private DataScopeCache getCache() {
        DataScopeCache cache = cacheHolder.get();
        if (cache == null) {
            cache = new DataScopeCache();
            cacheHolder.set(cache);
        }
        return cache;
    }

    /**
     * 清理当前请求的数据范围缓存。
     *
     * <p>应在请求结束时（如 JWT 过滤器的 {@code finally} 块）调用，防止 ThreadLocal
     * 在线程复用场景下泄漏旧请求的数据。</p>
     */
    public void clearCache() {
        cacheHolder.remove();
    }

    /**
     * 请求级数据范围缓存。
     */
    private static final class DataScopeCache {
        private DataScope scope;
        private List<String> factoryCodes;
    }
}
