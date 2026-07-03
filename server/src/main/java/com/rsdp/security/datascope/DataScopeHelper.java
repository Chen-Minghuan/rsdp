package com.rsdp.security.datascope;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 数据权限辅助工具。
 *
 * <p>根据当前用户数据范围，自动为 QueryWrapper 拼接数据权限条件。</p>
 */
@Component
@RequiredArgsConstructor
public class DataScopeHelper {

    private final DataScopeContext dataScopeContext;

    /**
     * 对 RSKU 查询应用数据权限。
     *
     * @param wrapper QueryWrapper
     */
    public void applyRskuScope(QueryWrapper<?> wrapper) {
        DataScope scope = dataScopeContext.currentDataScope();
        switch (scope) {
            case ALL -> {
                // 不过滤
            }
            case FACTORY_LIST -> {
                List<String> factoryCodes = dataScopeContext.currentFactoryCodes();
                if (factoryCodes.isEmpty()) {
                    wrapper.apply("1 = 0");
                } else {
                    wrapper.in("factory_code", factoryCodes);
                }
            }
            case SELF_CREATED, PUBLIC_ONLY -> wrapper.apply("1 = 0");
        }
    }

    /**
     * 校验当前用户是否能操作指定工厂的 RSKU。
     *
     * @param factoryCode 工厂编码
     * @return 是否有权限
     */
    public boolean canAccessRskuFactory(String factoryCode) {
        DataScope scope = dataScopeContext.currentDataScope();
        if (scope == DataScope.ALL) {
            return true;
        }
        if (scope == DataScope.FACTORY_LIST) {
            return dataScopeContext.currentFactoryCodes().contains(factoryCode);
        }
        return false;
    }
}
