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
     * 获取当前登录用户的数据范围（委托 {@link DataScopeContext#currentDataScope()}，
     * 带请求级缓存）。
     *
     * @return 数据范围
     */
    public DataScope currentDataScope() {
        return dataScopeContext.currentDataScope();
    }

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
     * 校验当前用户是否能查看指定工厂的出厂价（factory_price）。
     *
     * <p>出厂价是全系统最高敏感字段（落库 AES 加密），可见性与数据范围（DataScope）解耦，
     * 按角色单独判定：
     * <ul>
     *   <li>平台运营人员（ADMIN/EDITOR）可见全部出厂价；</li>
     *   <li>工厂管理员仅可见本厂的出厂价（自己报的价）；</li>
     *   <li>设计师、普通用户等其他角色一律不可见（响应中掩码为 null）。</li>
     * </ul>
     *
     * @param factoryCode 工厂编码
     * @return 是否可见出厂价
     */
    public boolean canViewFactoryPrice(String factoryCode) {
        if (com.rsdp.security.SecurityOperatorContext.isPlatformStaff()) {
            return true;
        }
        if (com.rsdp.security.SecurityOperatorContext.isCurrentUserFactoryAdmin()) {
            return factoryCode != null && dataScopeContext.currentFactoryCodes().contains(factoryCode);
        }
        return false;
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
            return factoryCode != null && dataScopeContext.currentFactoryCodes().contains(factoryCode);
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
     * 断言当前用户可管理指定 RSPU 的元数据/上下架（INTEG-001：谁录入谁负责）。
     *
     * <p>规则：
     * <ul>
     *   <li>平台运营人员（ADMIN/EDITOR）始终可管理；</li>
     *   <li>工厂管理员仅可管理归属本厂录入的产品（owner_type=FACTORY 且 owner_factory_code 属于本厂）；</li>
     *   <li>平台录入（owner_type=PLATFORM，含存量未回填行）的产品工厂账号不可管理；</li>
     *   <li>其他角色不可管理。</li>
     * </ul>
     *
     * @param rspu 产品主档（须含 ownerType/ownerFactoryCode）
     */
    public void assertCanManageRspu(com.rsdp.entity.RspuMaster rspu) {
        if (com.rsdp.security.SecurityOperatorContext.isPlatformStaff()) {
            return;
        }
        if (dataScopeContext.currentDataScope() == DataScope.FACTORY_LIST) {
            java.util.List<String> codes = dataScopeContext.currentFactoryCodes();
            if ("FACTORY".equals(rspu.getOwnerType())
                && rspu.getOwnerFactoryCode() != null
                && codes.contains(rspu.getOwnerFactoryCode())) {
                return;
            }
            throw new BusinessException("只能维护本厂录入的产品（上下架谁录入谁负责）: " + rspu.getRspuId());
        }
        throw new BusinessException("无权维护产品: " + rspu.getRspuId());
    }

    /**
     * 新建 RSPU 时写入录入归属（INTEG-001）。
     *
     * <p>平台员工录入归 PLATFORM；工厂管理员录入归其工厂（preferredFactoryCode 须已
     * 通过归属校验，如 factory-entry 的 validateFactoryEntryOwnership；为空时取关联厂首个）。</p>
     *
     * @param rspu                 待插入的产品主档
     * @param preferredFactoryCode 录入请求指定的工厂编码（可空）
     */
    public void applyOwnershipOnCreate(com.rsdp.entity.RspuMaster rspu, String preferredFactoryCode) {
        String owner = null;
        if (!com.rsdp.security.SecurityOperatorContext.isPlatformStaff()
            && com.rsdp.security.SecurityOperatorContext.isCurrentUserFactoryAdmin()) {
            java.util.List<String> codes = dataScopeContext.currentFactoryCodes();
            if (preferredFactoryCode != null && codes.contains(preferredFactoryCode)) {
                owner = preferredFactoryCode;
            } else if (!codes.isEmpty()) {
                owner = codes.get(0);
            }
        }
        if (owner != null) {
            rspu.setOwnerType("FACTORY");
            rspu.setOwnerFactoryCode(owner);
        } else {
            rspu.setOwnerType("PLATFORM");
            rspu.setOwnerFactoryCode(null);
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
