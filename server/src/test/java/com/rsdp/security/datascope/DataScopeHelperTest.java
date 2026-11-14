package com.rsdp.security.datascope;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.RskuSupplyMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DataScopeHelper} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class DataScopeHelperTest {

    @Mock
    private DataScopeContext dataScopeContext;

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @InjectMocks
    private DataScopeHelper dataScopeHelper;

    @BeforeEach
    void setSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String username, String role) {
        var user = User.withUsername(username).password("").roles(role).build();
        var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void canAccessFactory_allScope_shouldReturnTrue() {
        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.ALL);

        assertThat(dataScopeHelper.canAccessFactory("F001")).isTrue();
        verify(dataScopeContext, never()).currentFactoryCodes();
    }

    @Test
    void canAccessFactory_factoryListWithAccess_shouldReturnTrue() {
        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.FACTORY_LIST);
        when(dataScopeContext.currentFactoryCodes()).thenReturn(List.of("F001", "F002"));

        assertThat(dataScopeHelper.canAccessFactory("F001")).isTrue();
    }

    @Test
    void canAccessFactory_factoryListWithoutAccess_shouldReturnFalse() {
        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.FACTORY_LIST);
        when(dataScopeContext.currentFactoryCodes()).thenReturn(List.of("F001", "F002"));

        assertThat(dataScopeHelper.canAccessFactory("F003")).isFalse();
    }

    @Test
    void canAccessFactory_publicOnly_shouldReturnFalse() {
        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.PUBLIC_ONLY);

        assertThat(dataScopeHelper.canAccessFactory("F001")).isFalse();
    }

    @Test
    void canAccessFactory_nullFactoryCode_shouldReturnFalse() {
        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.FACTORY_LIST);

        assertThat(dataScopeHelper.canAccessFactory(null)).isFalse();
    }

    @Test
    void canAccessRskuFactory_shouldDelegateToCanAccessFactory() {
        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.FACTORY_LIST);
        when(dataScopeContext.currentFactoryCodes()).thenReturn(List.of("F001"));

        assertThat(dataScopeHelper.canAccessRskuFactory("F001")).isTrue();
        assertThat(dataScopeHelper.canAccessRskuFactory("F002")).isFalse();
    }

    @Test
    void canAccessRspu_allScope_shouldReturnTrue() {
        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.ALL);

        assertThat(dataScopeHelper.canAccessRspu("RSPU-001")).isTrue();
        verify(rskuSupplyMapper, never()).selectCount(any(QueryWrapper.class));
    }

    @Test
    void canAccessRspu_factoryListWithQuote_shouldReturnTrue() {
        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.FACTORY_LIST);
        when(dataScopeContext.currentFactoryCodes()).thenReturn(List.of("F001", "F002"));
        when(rskuSupplyMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        assertThat(dataScopeHelper.canAccessRspu("RSPU-001")).isTrue();

        ArgumentCaptor<QueryWrapper<RskuSupply>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(rskuSupplyMapper).selectCount(captor.capture());
        String sqlSegment = captor.getValue().getSqlSegment();
        assertThat(sqlSegment).contains("rspu_id");
        assertThat(captor.getValue().getParamNameValuePairs().values())
            .contains("F001", "F002");
    }

    @Test
    void canAccessRspu_factoryListWithoutQuote_shouldReturnFalse() {
        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.FACTORY_LIST);
        when(dataScopeContext.currentFactoryCodes()).thenReturn(List.of("F001", "F002"));
        when(rskuSupplyMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        assertThat(dataScopeHelper.canAccessRspu("RSPU-001")).isFalse();
    }

    @Test
    void canAccessRspu_factoryListEmptyCodes_shouldReturnFalse() {
        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.FACTORY_LIST);
        when(dataScopeContext.currentFactoryCodes()).thenReturn(List.of());

        assertThat(dataScopeHelper.canAccessRspu("RSPU-001")).isFalse();
        verify(rskuSupplyMapper, never()).selectCount(any(QueryWrapper.class));
    }

    @Test
    void canAccessRspu_publicOnly_shouldReturnFalse() {
        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.PUBLIC_ONLY);

        assertThat(dataScopeHelper.canAccessRspu("RSPU-001")).isFalse();
    }

    @Test
    void assertCanAccessRspu_withoutAccess_shouldThrow() {
        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.PUBLIC_ONLY);

        assertThatThrownBy(() -> dataScopeHelper.assertCanAccessRspu("RSPU-001"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("只能维护本厂已报价的产品");
    }

    @Test
    void assertCanAccessRspu_withAccess_shouldNotThrow() {
        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.ALL);

        dataScopeHelper.assertCanAccessRspu("RSPU-001");
    }

    @Test
    void isOnlyAssociatedFactoryForRspu_allScope_shouldReturnTrue() {
        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.ALL);

        assertThat(dataScopeHelper.isOnlyAssociatedFactoryForRspu("RSPU-001")).isTrue();
    }

    @Test
    void isOnlyAssociatedFactoryForRspu_noOtherFactories_shouldReturnTrue() {
        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.FACTORY_LIST);
        when(dataScopeContext.currentFactoryCodes()).thenReturn(List.of("F001"));
        when(rskuSupplyMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        assertThat(dataScopeHelper.isOnlyAssociatedFactoryForRspu("RSPU-001")).isTrue();
    }

    @Test
    void isOnlyAssociatedFactoryForRspu_withOtherFactories_shouldReturnFalse() {
        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.FACTORY_LIST);
        when(dataScopeContext.currentFactoryCodes()).thenReturn(List.of("F001"));
        when(rskuSupplyMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        assertThat(dataScopeHelper.isOnlyAssociatedFactoryForRspu("RSPU-001")).isFalse();
    }

    @Test
    void applyRskuScope_allScope_shouldNotFilter() {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Object> wrapper =
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();

        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.ALL);

        dataScopeHelper.applyRskuScope(wrapper);

        assertThat(wrapper.getSqlSegment()).doesNotContain("1 = 0");
        assertThat(wrapper.getSqlSegment()).doesNotContain("factory_code");
    }

    @Test
    void applyRskuScope_factoryListWithCodes_shouldAddInFilter() {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Object> wrapper =
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();

        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.FACTORY_LIST);
        when(dataScopeContext.currentFactoryCodes()).thenReturn(List.of("F001", "F002"));

        dataScopeHelper.applyRskuScope(wrapper);

        String sqlSegment = wrapper.getSqlSegment();
        assertThat(sqlSegment).contains("factory_code");
        assertThat(wrapper.getParamNameValuePairs().values())
            .contains("F001", "F002");
    }

    @Test
    void applyRskuScope_factoryListEmptyCodes_shouldAlwaysFalse() {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Object> wrapper =
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();

        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.FACTORY_LIST);
        when(dataScopeContext.currentFactoryCodes()).thenReturn(List.of());

        dataScopeHelper.applyRskuScope(wrapper);

        assertThat(wrapper.getSqlSegment()).contains("1 = 0");
    }

    @Test
    void applyRskuScope_selfCreated_shouldAlwaysFalse() {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Object> wrapper =
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();

        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.SELF_CREATED);

        dataScopeHelper.applyRskuScope(wrapper);

        assertThat(wrapper.getSqlSegment()).contains("1 = 0");
    }

    // ==================== INTEG-001：录入归属（谁录入谁负责） ====================

    @Test
    void assertCanManageRspu_platformStaff_shouldPass() {
        authenticate("admin", "ADMIN");
        com.rsdp.entity.RspuMaster rspu = new com.rsdp.entity.RspuMaster();
        rspu.setRspuId("RSPU-1");
        rspu.setOwnerType("FACTORY");
        rspu.setOwnerFactoryCode("F001");
        dataScopeHelper.assertCanManageRspu(rspu);
    }

    @Test
    void assertCanManageRspu_ownerFactoryAdmin_shouldPass() {
        authenticate("factory", "FACTORY_ADMIN");
        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.FACTORY_LIST);
        when(dataScopeContext.currentFactoryCodes()).thenReturn(List.of("F001"));
        com.rsdp.entity.RspuMaster rspu = new com.rsdp.entity.RspuMaster();
        rspu.setRspuId("RSPU-1");
        rspu.setOwnerType("FACTORY");
        rspu.setOwnerFactoryCode("F001");
        dataScopeHelper.assertCanManageRspu(rspu);
    }

    @Test
    void assertCanManageRspu_otherFactoryOwned_shouldThrow() {
        authenticate("factory", "FACTORY_ADMIN");
        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.FACTORY_LIST);
        when(dataScopeContext.currentFactoryCodes()).thenReturn(List.of("F001"));
        com.rsdp.entity.RspuMaster rspu = new com.rsdp.entity.RspuMaster();
        rspu.setRspuId("RSPU-2");
        rspu.setOwnerType("FACTORY");
        rspu.setOwnerFactoryCode("F002");
        assertThatThrownBy(() -> dataScopeHelper.assertCanManageRspu(rspu))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("只能维护本厂录入的产品");
    }

    @Test
    void assertCanManageRspu_platformOwned_shouldThrowForFactoryAdmin() {
        authenticate("factory", "FACTORY_ADMIN");
        when(dataScopeContext.currentDataScope()).thenReturn(DataScope.FACTORY_LIST);
        when(dataScopeContext.currentFactoryCodes()).thenReturn(List.of("F001"));
        com.rsdp.entity.RspuMaster rspu = new com.rsdp.entity.RspuMaster();
        rspu.setRspuId("RSPU-3");
        rspu.setOwnerType("PLATFORM");
        assertThatThrownBy(() -> dataScopeHelper.assertCanManageRspu(rspu))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("只能维护本厂录入的产品");
    }

    @Test
    void applyOwnershipOnCreate_factoryAdmin_shouldStampFactory() {
        authenticate("factory", "FACTORY_ADMIN");
        when(dataScopeContext.currentFactoryCodes()).thenReturn(List.of("F001", "F002"));
        com.rsdp.entity.RspuMaster rspu = new com.rsdp.entity.RspuMaster();
        dataScopeHelper.applyOwnershipOnCreate(rspu, "F002");
        assertThat(rspu.getOwnerType()).isEqualTo("FACTORY");
        assertThat(rspu.getOwnerFactoryCode()).isEqualTo("F002");
    }

    @Test
    void applyOwnershipOnCreate_factoryAdminWithoutPreferred_shouldUseFirstFactory() {
        authenticate("factory", "FACTORY_ADMIN");
        when(dataScopeContext.currentFactoryCodes()).thenReturn(List.of("F001"));
        com.rsdp.entity.RspuMaster rspu = new com.rsdp.entity.RspuMaster();
        dataScopeHelper.applyOwnershipOnCreate(rspu, null);
        assertThat(rspu.getOwnerType()).isEqualTo("FACTORY");
        assertThat(rspu.getOwnerFactoryCode()).isEqualTo("F001");
    }

    @Test
    void applyOwnershipOnCreate_platformStaff_shouldStampPlatform() {
        authenticate("admin", "ADMIN");
        com.rsdp.entity.RspuMaster rspu = new com.rsdp.entity.RspuMaster();
        dataScopeHelper.applyOwnershipOnCreate(rspu, "F001");
        assertThat(rspu.getOwnerType()).isEqualTo("PLATFORM");
        assertThat(rspu.getOwnerFactoryCode()).isNull();
    }
}
