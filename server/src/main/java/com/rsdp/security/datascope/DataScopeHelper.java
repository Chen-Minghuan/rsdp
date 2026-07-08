package com.rsdp.security.datascope;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.RskuSupplyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 数据权限辅助工具。
 *
 * <p>根据当前用户数据范围，自动为 QueryWrapper 拼接数据权限条件，并提供 RSPU/RSKU/工厂级
 * 写操作前的所有权校验。</p>
 */
@Component
@RequiredArgsConstructor
public class DataScopeHelper {

    private final DataScopeContext dataScopeContext;
    private final RskuSupplyMapper rskuSupplyMapper;

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
     * 校验当前用户是否能操作指定工厂的 RSKU/工厂资料。
     *
     * @param factoryCode 工厂编码
     * @return 是否有权限
     */
    public boolean canAccessFactory(String factoryCode) {
        DataScope scope = dataScopeContext.currentDataScope();
        if (scope == DataScope.ALL) {
            return true;
        }
        if (scope == DataScope.FACTORY_LIST) {
            return dataScopeContext.currentFactoryCodes().contains(factoryCode);
        }
        return false;
    }

    /**
     * 校验当前用户是否能操作指定工厂的 RSKU。
     *
     * <p>与 {@link #canAccessFactory(String)} 语义相同，保留旧名以便兼容现有调用。</p>
     *
     * @param factoryCode 工厂编码
     * @return 是否有权限
     */
    public boolean canAccessRskuFactory(String factoryCode) {
        return canAccessFactory(factoryCode);
    }

    /**
     * 校验当前用户是否能维护指定 RSPU。
     *
     * <p>规则：
     * <ul>
     *   <li>平台运营人员（ADMIN/EDITOR）始终可维护。</li>
     *   <li>工厂管理员仅当本厂已对该 RSPU 有 RSKU 报价记录时可维护。</li>
     *   <li>其他角色不可维护。</li>
     * </ul>
     *
     * @param rspuId RSPU ID
     * @return 是否可维护
     */
    public boolean canAccessRspu(String rspuId) {
        DataScope scope = dataScopeContext.currentDataScope();
        if (scope == DataScope.ALL) {
            return true;
        }
        if (scope == DataScope.FACTORY_LIST) {
            List<String> factoryCodes = dataScopeContext.currentFactoryCodes();
            if (factoryCodes.isEmpty()) {
                return false;
            }
            Long count = rskuSupplyMapper.selectCount(
                new QueryWrapper<RskuSupply>()
                    .eq("rspu_id", rspuId)
                    .in("factory_code", factoryCodes)
                    .isNull("deleted_at")
            );
            return count != null && count > 0;
        }
        return false;
    }

    /**
     * 断言当前用户可维护指定 RSPU，否则抛出业务异常。
     *
     * @param rspuId RSPU ID
     */
    public void assertCanAccessRspu(String rspuId) {
        if (!canAccessRspu(rspuId)) {
            throw new BusinessException("只能维护本厂已报价的产品: " + rspuId);
        }
    }

    /**
     * 判断指定 RSPU 是否仅由当前用户关联的工厂供应。
     *
     * <p>用于删除前的二次保护：若还有其他工厂报价，则不应由单个工厂管理员删除。</p>
     *
     * @param rspuId RSPU ID
     * @return true 表示只有当前用户关联工厂供应（或当前用户为平台运营人员）
     */
    public boolean isOnlyAssociatedFactoryForRspu(String rspuId) {
        DataScope scope = dataScopeContext.currentDataScope();
        if (scope == DataScope.ALL) {
            return true;
        }
        if (scope != DataScope.FACTORY_LIST) {
            return false;
        }
        List<String> factoryCodes = dataScopeContext.currentFactoryCodes();
        if (factoryCodes.isEmpty()) {
            return false;
        }
        Long others = rskuSupplyMapper.selectCount(
            new QueryWrapper<RskuSupply>()
                .eq("rspu_id", rspuId)
                .notIn("factory_code", factoryCodes)
                .isNull("deleted_at")
        );
        return others == null || others == 0;
    }
}
