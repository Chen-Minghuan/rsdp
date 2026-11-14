package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.FactoryCatalogBlock;
import com.rsdp.entity.FactoryCatalogGrant;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.RspuMaster;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FactoryCatalogBlockMapper;
import com.rsdp.mapper.FactoryCatalogGrantMapper;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.security.SecurityOperatorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工厂可见性统一收口（INTEG-001，V28）。
 *
 * <p>规则（浏览方 = 当前用户关联工厂集合 F）：</p>
 * <pre>
 *   可见 = 自家已报价（无条件）
 *        ∪ 整批放行（归属厂放行给 F）
 *        ∪ 公共库开关 ON（用户 view_full_catalog 且任一关联厂开关开）→ 全量
 *        − 黑名单（F 屏蔽的归属厂，最高优先；但不遮蔽自家已报价产品）
 * </pre>
 *
 * <p>多厂账号取并集语义：自家已报价 = 任一厂有 RSKU；放行 = 放行给任一厂即生效；
 * 黑名单 = 任一厂屏蔽即生效（最严格）；厂级开关 = 任一厂开即具备全库资格。</p>
 *
 * <p>平台员工/设计师/非工厂账号不走本服务（原有口径不变）。</p>
 */
@Service
@RequiredArgsConstructor
public class CatalogVisibilityService {

    private final FactoryCatalogGrantMapper grantMapper;
    private final FactoryCatalogBlockMapper blockMapper;
    private final FactoryMasterMapper factoryMasterMapper;
    private final RskuSupplyMapper rskuSupplyMapper;

    // ==================== 查询过滤 ====================

    /**
     * 对 RSPU 列表查询应用工厂可见性过滤。
     *
     * @param wrapper          查询构造器（表别名 rspu_master）
     * @param viewerFactories  当前用户关联工厂编码（非空，调用方保证）
     * @param fullViewEligible 是否具备全库资格（用户级 view_full_catalog 且厂级开关开）
     */
    public void applyProductVisibility(QueryWrapper<RspuMaster> wrapper,
                                       List<String> viewerFactories, boolean fullViewEligible) {
        String selfSupplied = selfSuppliedExists(viewerFactories);
        String notBlockedOrSelf = notBlockedOrSelfSql(viewerFactories);
        if (fullViewEligible) {
            // 全库：全部 − 黑名单（自家已报价保留）
            wrapper.and(w -> w.exists(selfSupplied).or().apply(notBlockedOrSelf));
        } else {
            // 私有视图：自家已报价 ∪（被放行归属 − 黑名单）
            String grantedOwners = "SELECT owner_factory_code FROM factory_catalog_grant"
                + " WHERE granted_factory_code IN (" + toSqlInList(viewerFactories) + ")";
            wrapper.and(w -> w.exists(selfSupplied)
                .or().nested(n -> n
                    .apply("rspu_master.owner_factory_code IN (" + grantedOwners + ")")
                    .apply(notBlockedOrSelf)));
        }
    }

    /**
     * 判断浏览厂集合是否可见某个产品（详情页用）。
     */
    public boolean canViewProduct(RspuMaster rspu, List<String> viewerFactories, boolean fullViewEligible) {
        if (viewerFactories.isEmpty()) {
            return false;
        }
        if (isSelfSupplied(rspu.getRspuId(), viewerFactories)) {
            return true;
        }
        String owner = rspu.getOwnerFactoryCode();
        if (owner != null && isBlockedByAny(viewerFactories, owner)) {
            return false;
        }
        if (fullViewEligible) {
            return true;
        }
        return owner != null && isGrantedTo(viewerFactories, owner);
    }

    /**
     * 厂级公共库开关状态（任一关联厂开启即具备全库资格）。
     */
    public boolean isPublicCatalogEnabled(List<String> viewerFactories) {
        if (viewerFactories.isEmpty()) {
            return false;
        }
        Long count = factoryMasterMapper.selectCount(new QueryWrapper<FactoryMaster>()
            .in("factory_code", viewerFactories)
            .eq("public_catalog_enabled", true));
        return count != null && count > 0;
    }

    // ==================== 配置管理（放行/黑名单/开关） ====================

    /**
     * 读工厂公共库开关。
     */
    public boolean getPublicCatalogEnabled(String factoryCode) {
        FactoryMaster factory = requireFactory(factoryCode);
        return factory.getPublicCatalogEnabled() == null || Boolean.TRUE.equals(factory.getPublicCatalogEnabled());
    }

    /**
     * 写工厂公共库开关。
     */
    @Transactional
    public void setPublicCatalogEnabled(String factoryCode, boolean enabled) {
        FactoryMaster factory = requireFactory(factoryCode);
        factory.setPublicCatalogEnabled(enabled);
        factoryMasterMapper.updateById(factory);
    }

    /**
     * 列出 owner 厂已放行的目标厂编码。
     */
    public List<String> listGrantedFactoryCodes(String ownerFactoryCode) {
        requireFactory(ownerFactoryCode);
        return grantMapper.selectList(new QueryWrapper<FactoryCatalogGrant>()
                .eq("owner_factory_code", ownerFactoryCode))
            .stream().map(FactoryCatalogGrant::getGrantedFactoryCode).collect(Collectors.toList());
    }

    /**
     * 新增整批放行：owner 厂放行给 target 厂（幂等）。
     */
    @Transactional
    public void addGrant(String ownerFactoryCode, String targetFactoryCode) {
        requireFactory(ownerFactoryCode);
        requireFactory(targetFactoryCode);
        if (ownerFactoryCode.equals(targetFactoryCode)) {
            throw new BusinessException("不能放行给本厂");
        }
        Long exists = grantMapper.selectCount(new QueryWrapper<FactoryCatalogGrant>()
            .eq("owner_factory_code", ownerFactoryCode)
            .eq("granted_factory_code", targetFactoryCode));
        if (exists != null && exists > 0) {
            return;
        }
        FactoryCatalogGrant grant = new FactoryCatalogGrant();
        grant.setOwnerFactoryCode(ownerFactoryCode);
        grant.setGrantedFactoryCode(targetFactoryCode);
        grant.setCreatedBy(SecurityOperatorContext.currentUsername());
        grantMapper.insert(grant);
    }

    /**
     * 移除整批放行。
     */
    @Transactional
    public void removeGrant(String ownerFactoryCode, String targetFactoryCode) {
        grantMapper.delete(new QueryWrapper<FactoryCatalogGrant>()
            .eq("owner_factory_code", ownerFactoryCode)
            .eq("granted_factory_code", targetFactoryCode));
    }

    /**
     * 列出浏览厂的黑名单归属厂编码。
     */
    public List<String> listBlockedFactoryCodes(String factoryCode) {
        requireFactory(factoryCode);
        return blockMapper.selectList(new QueryWrapper<FactoryCatalogBlock>()
                .eq("factory_code", factoryCode))
            .stream().map(FactoryCatalogBlock::getBlockedFactoryCode).collect(Collectors.toList());
    }

    /**
     * 新增黑名单：浏览厂屏蔽 target 厂全部产品（幂等）。
     */
    @Transactional
    public void addBlock(String factoryCode, String targetFactoryCode) {
        requireFactory(factoryCode);
        requireFactory(targetFactoryCode);
        if (factoryCode.equals(targetFactoryCode)) {
            throw new BusinessException("不能屏蔽本厂");
        }
        Long exists = blockMapper.selectCount(new QueryWrapper<FactoryCatalogBlock>()
            .eq("factory_code", factoryCode)
            .eq("blocked_factory_code", targetFactoryCode));
        if (exists != null && exists > 0) {
            return;
        }
        FactoryCatalogBlock block = new FactoryCatalogBlock();
        block.setFactoryCode(factoryCode);
        block.setBlockedFactoryCode(targetFactoryCode);
        block.setCreatedBy(SecurityOperatorContext.currentUsername());
        blockMapper.insert(block);
    }

    /**
     * 移除黑名单。
     */
    @Transactional
    public void removeBlock(String factoryCode, String targetFactoryCode) {
        blockMapper.delete(new QueryWrapper<FactoryCatalogBlock>()
            .eq("factory_code", factoryCode)
            .eq("blocked_factory_code", targetFactoryCode));
    }

    // ==================== 内部 ====================

    private boolean isSelfSupplied(String rspuId, List<String> viewerFactories) {
        // 与 applyProductVisibility 的 selfSuppliedExists 同口径；单独查询保持详情判定可读
        Long count = rskuSupplyMapper.selectCount(new QueryWrapper<com.rsdp.entity.RskuSupply>()
            .eq("rspu_id", rspuId)
            .in("factory_code", viewerFactories)
            .isNull("deleted_at"));
        return count != null && count > 0;
    }

    private boolean isGrantedTo(List<String> viewerFactories, String ownerFactoryCode) {
        Long count = grantMapper.selectCount(new QueryWrapper<FactoryCatalogGrant>()
            .eq("owner_factory_code", ownerFactoryCode)
            .in("granted_factory_code", viewerFactories));
        return count != null && count > 0;
    }

    private boolean isBlockedByAny(List<String> viewerFactories, String ownerFactoryCode) {
        Long count = blockMapper.selectCount(new QueryWrapper<FactoryCatalogBlock>()
            .in("factory_code", viewerFactories)
            .eq("blocked_factory_code", ownerFactoryCode));
        return count != null && count > 0;
    }

    /** 自家已报价 EXISTS 子查询（RSKU 归属口径，与原有 own 视图一致）。 */
    private String selfSuppliedExists(List<String> viewerFactories) {
        return "SELECT 1 FROM rsku_supply rs WHERE rs.rspu_id = rspu_master.rspu_id"
            + " AND rs.factory_code IN (" + toSqlInList(viewerFactories) + ")"
            + " AND rs.deleted_at IS NULL";
    }

    /** 归属厂未被屏蔽（或虽被屏蔽但自家已报价，黑名单不遮蔽自家生意）。 */
    private String notBlockedOrSelfSql(List<String> viewerFactories) {
        String blockedOwners = "SELECT blocked_factory_code FROM factory_catalog_block"
            + " WHERE factory_code IN (" + toSqlInList(viewerFactories) + ")";
        return "(rspu_master.owner_factory_code IS NULL"
            + " OR rspu_master.owner_factory_code NOT IN (" + blockedOwners + ")"
            + " OR EXISTS (" + selfSuppliedExists(viewerFactories) + "))";
    }

    private FactoryMaster requireFactory(String factoryCode) {
        if (!StringUtils.hasText(factoryCode)) {
            throw new BusinessException("工厂编码不能为空");
        }
        FactoryMaster factory = factoryMasterMapper.selectById(factoryCode);
        if (factory == null) {
            throw new ResourceNotFoundException("工厂不存在: " + factoryCode);
        }
        return factory;
    }

    /** 拼接 SQL IN 片段（单引号成对转义）。仅用于工厂编码等内部编码，不用于用户自由文本。 */
    private String toSqlInList(List<String> values) {
        return values.stream()
            .map(v -> "'" + v.replace("'", "''") + "'")
            .collect(Collectors.joining(", "));
    }
}
