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
 */
@Component
@RequiredArgsConstructor
public class DataScopeContext {

    private static final Set<String> ADMIN_ROLES = Set.of("ADMIN");

    private final UserRoleService userRoleService;
    private final UserFactoryService userFactoryService;

    /**
     * 获取当前登录用户的数据范围。
     *
     * @return 数据范围
     */
    public DataScope currentDataScope() {
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

        if (roleCodes.contains("FACTORY_SALES")) {
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
        String username = SecurityOperatorContext.currentUsername();
        if ("anonymous".equals(username)) {
            return List.of();
        }
        return userFactoryService.getFactoryCodesByUsername(username);
    }
}
