package com.rsdp.security.datascope;

import com.rsdp.service.UserFactoryService;
import com.rsdp.service.UserRoleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DataScopeContext} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class DataScopeContextTest {

    @Mock
    private UserRoleService userRoleService;

    @Mock
    private UserFactoryService userFactoryService;

    @InjectMocks
    private DataScopeContext dataScopeContext;

    @BeforeEach
    void setSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        dataScopeContext.clearCache();
    }

    private void authenticate(String username, String role) {
        var user = User.withUsername(username).password("").roles(role).build();
        var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void currentDataScope_adminShouldReturnAll() {
        authenticate("admin", "ADMIN");
        when(userRoleService.getRoleCodesByUsername("admin")).thenReturn(List.of("ADMIN"));

        assertThat(dataScopeContext.currentDataScope()).isEqualTo(DataScope.ALL);
    }

    @Test
    void currentDataScope_factoryAdminShouldReturnFactoryList() {
        authenticate("factory", "FACTORY_ADMIN");
        when(userRoleService.getRoleCodesByUsername("factory")).thenReturn(List.of("FACTORY_ADMIN"));

        assertThat(dataScopeContext.currentDataScope()).isEqualTo(DataScope.FACTORY_LIST);
    }

    @Test
    void currentDataScope_designerShouldReturnAll() {
        authenticate("designer", "DESIGNER");
        when(userRoleService.getRoleCodesByUsername("designer")).thenReturn(List.of("DESIGNER"));

        assertThat(dataScopeContext.currentDataScope()).isEqualTo(DataScope.ALL);
    }

    @Test
    void currentDataScope_anonymousShouldReturnPublicOnly() {
        assertThat(dataScopeContext.currentDataScope()).isEqualTo(DataScope.PUBLIC_ONLY);
    }

    @Test
    void currentFactoryCodes_shouldReturnUserFactories() {
        authenticate("factory", "FACTORY_ADMIN");
        when(userFactoryService.getFactoryCodesByUsername("factory")).thenReturn(List.of("F001", "F002"));

        assertThat(dataScopeContext.currentFactoryCodes()).containsExactly("F001", "F002");
    }

    @Test
    void currentDataScope_shouldCacheRoleQueryWithinRequest() {
        authenticate("factory", "FACTORY_ADMIN");
        when(userRoleService.getRoleCodesByUsername("factory")).thenReturn(List.of("FACTORY_ADMIN"));

        assertThat(dataScopeContext.currentDataScope()).isEqualTo(DataScope.FACTORY_LIST);
        assertThat(dataScopeContext.currentDataScope()).isEqualTo(DataScope.FACTORY_LIST);

        verify(userRoleService, times(1)).getRoleCodesByUsername("factory");
    }

    @Test
    void currentFactoryCodes_shouldCacheFactoryQueryWithinRequest() {
        authenticate("factory", "FACTORY_ADMIN");
        when(userFactoryService.getFactoryCodesByUsername("factory")).thenReturn(List.of("F001", "F002"));

        assertThat(dataScopeContext.currentFactoryCodes()).containsExactly("F001", "F002");
        assertThat(dataScopeContext.currentFactoryCodes()).containsExactly("F001", "F002");

        verify(userFactoryService, times(1)).getFactoryCodesByUsername("factory");
    }

    @Test
    void clearCache_shouldForceRequery() {
        authenticate("factory", "FACTORY_ADMIN");
        when(userRoleService.getRoleCodesByUsername("factory")).thenReturn(List.of("FACTORY_ADMIN"));
        when(userFactoryService.getFactoryCodesByUsername("factory")).thenReturn(List.of("F001"));

        dataScopeContext.currentDataScope();
        dataScopeContext.currentFactoryCodes();
        dataScopeContext.clearCache();

        dataScopeContext.currentDataScope();
        dataScopeContext.currentFactoryCodes();

        verify(userRoleService, times(2)).getRoleCodesByUsername("factory");
        verify(userFactoryService, times(2)).getFactoryCodesByUsername("factory");
    }
}
