package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.FactoryCatalogGrant;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.RspuMaster;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.FactoryCatalogBlockMapper;
import com.rsdp.mapper.FactoryCatalogGrantMapper;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 工厂可见性规则（INTEG-001）三工厂正反用例：
 * F1=浏览厂，F2=放行来源厂，F3=黑名单厂。
 */
@ExtendWith(MockitoExtension.class)
class CatalogVisibilityServiceTest {

    @Mock
    private FactoryCatalogGrantMapper grantMapper;
    @Mock
    private FactoryCatalogBlockMapper blockMapper;
    @Mock
    private FactoryMasterMapper factoryMasterMapper;
    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @InjectMocks
    private CatalogVisibilityService service;

    private static final List<String> F1 = List.of("F1");

    private RspuMaster productOf(String rspuId, String ownerFactoryCode) {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setStatus("active");
        if (ownerFactoryCode != null) {
            rspu.setOwnerType("FACTORY");
            rspu.setOwnerFactoryCode(ownerFactoryCode);
        } else {
            rspu.setOwnerType("PLATFORM");
        }
        return rspu;
    }

    // ==================== 详情判定（canViewProduct） ====================

    @Test
    void ownSuppliedProductAlwaysVisible() {
        when(rskuSupplyMapper.selectCount(any())).thenReturn(1L);
        // 即使归属厂被拉黑，自家已报价产品仍可见（黑名单不遮蔽自家生意）
        assertThat(service.canViewProduct(productOf("P1", "F3"), F1, true)).isTrue();
    }

    @Test
    void fullViewSeesAllExceptBlocked() {
        when(rskuSupplyMapper.selectCount(any())).thenReturn(0L);
        when(blockMapper.selectCount(any())).thenReturn(0L);
        assertThat(service.canViewProduct(productOf("P2", "F2"), F1, true)).isTrue();
        // 平台产品（无归属厂）在全库视图可见
        assertThat(service.canViewProduct(productOf("P3", null), F1, true)).isTrue();
    }

    @Test
    void blockedFactoryHiddenEvenInFullView() {
        when(rskuSupplyMapper.selectCount(any())).thenReturn(0L);
        when(blockMapper.selectCount(any())).thenReturn(1L);
        assertThat(service.canViewProduct(productOf("P4", "F3"), F1, true)).isFalse();
    }

    @Test
    void toggleOffSeesOnlyGranted() {
        when(rskuSupplyMapper.selectCount(any())).thenReturn(0L);
        lenient().when(blockMapper.selectCount(any())).thenReturn(0L);
        // 放行厂产品可见
        when(grantMapper.selectCount(any())).thenReturn(1L);
        assertThat(service.canViewProduct(productOf("P5", "F2"), F1, false)).isTrue();
        // 未放行厂产品不可见
        when(grantMapper.selectCount(any())).thenReturn(0L);
        assertThat(service.canViewProduct(productOf("P6", "F9"), F1, false)).isFalse();
        // 平台产品在开关关闭时也不可见（只认自家与放行）
        assertThat(service.canViewProduct(productOf("P7", null), F1, false)).isFalse();
    }

    @Test
    void blockWinsOverGrant() {
        // 黑名单优先于放行：同厂既被放行又被拉黑 → 不可见
        when(rskuSupplyMapper.selectCount(any())).thenReturn(0L);
        when(blockMapper.selectCount(any())).thenReturn(1L);
        lenient().when(grantMapper.selectCount(any())).thenReturn(1L);
        assertThat(service.canViewProduct(productOf("P8", "F2"), F1, false)).isFalse();
        assertThat(service.canViewProduct(productOf("P8", "F2"), F1, true)).isFalse();
    }

    // ==================== 列表 SQL（applyProductVisibility） ====================

    @Test
    void fullViewSqlKeepsSelfSuppliedAndExcludesBlocked() {
        QueryWrapper<RspuMaster> wrapper = new QueryWrapper<>();
        service.applyProductVisibility(wrapper, F1, true);
        String sql = wrapper.getSqlSegment();
        // 自家已报价保留 + 黑名单排除（归属厂未拉黑或自家已报价）
        assertThat(sql).contains("rsku_supply");
        assertThat(sql).contains("factory_catalog_block");
        assertThat(sql).contains("'F1'");
        assertThat(sql).doesNotContain("factory_catalog_grant");
    }

    @Test
    void ownViewSqlIncludesGrantsAndBlocks() {
        QueryWrapper<RspuMaster> wrapper = new QueryWrapper<>();
        service.applyProductVisibility(wrapper, F1, false);
        String sql = wrapper.getSqlSegment();
        assertThat(sql).contains("rsku_supply");
        assertThat(sql).contains("factory_catalog_grant");
        assertThat(sql).contains("factory_catalog_block");
        assertThat(sql).contains("granted_factory_code");
        assertThat(sql).contains("blocked_factory_code");
    }

    // ==================== 配置管理 ====================

    @Test
    void addGrantRejectsSelf() {
        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F1");
        when(factoryMasterMapper.selectById("F1")).thenReturn(factory);
        assertThatThrownBy(() -> service.addGrant("F1", "F1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不能放行给本厂");
    }

    @Test
    void addBlockRejectsSelf() {
        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F1");
        when(factoryMasterMapper.selectById("F1")).thenReturn(factory);
        assertThatThrownBy(() -> service.addBlock("F1", "F1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不能屏蔽本厂");
    }

    @Test
    void addGrantIdempotentWhenExists() {
        FactoryMaster f1 = new FactoryMaster();
        f1.setFactoryCode("F1");
        FactoryMaster f2 = new FactoryMaster();
        f2.setFactoryCode("F2");
        when(factoryMasterMapper.selectById("F1")).thenReturn(f1);
        when(factoryMasterMapper.selectById("F2")).thenReturn(f2);
        when(grantMapper.selectCount(any())).thenReturn(1L);
        service.addGrant("F1", "F2");
        org.mockito.Mockito.verify(grantMapper, org.mockito.Mockito.never()).insert(any(FactoryCatalogGrant.class));
    }

    @Test
    void publicCatalogEnabledRequiresAnyFactoryOn() {
        when(factoryMasterMapper.selectCount(any())).thenReturn(0L);
        assertThat(service.isPublicCatalogEnabled(F1)).isFalse();
        when(factoryMasterMapper.selectCount(any())).thenReturn(1L);
        assertThat(service.isPublicCatalogEnabled(F1)).isTrue();
        assertThat(service.isPublicCatalogEnabled(List.of())).isFalse();
    }

}