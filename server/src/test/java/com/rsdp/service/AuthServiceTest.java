package com.rsdp.service;

import com.rsdp.dto.request.LoginRequest;
import com.rsdp.dto.response.LoginResponse;
import com.rsdp.entity.SysUser;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuthService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private UserRoleService userRoleService;

    @Mock
    private PermissionService permissionService;

    @Mock
    private UserFactoryService userFactoryService;

    @Mock
    private LoginAttemptService loginAttemptService;

    @InjectMocks
    private AuthService authService;

    @Test
    void login_success_shouldReturnToken() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
            .thenReturn(authentication);

        SysUser user = new SysUser();
        user.setUserId("USER-001");
        user.setUsername("admin");
        user.setNickname("管理员");
        when(sysUserMapper.selectByUsername("admin")).thenReturn(user);
        when(userRoleService.getRoleCodesByUserId("USER-001")).thenReturn(List.of("ADMIN"));
        when(permissionService.getPermissionsByUserId("USER-001")).thenReturn(Set.of("admin:user:manage"));
        when(userFactoryService.getFactoryCodesByUserId("USER-001")).thenReturn(List.of());
        when(jwtUtil.generateToken("USER-001", "admin", "管理员", "ADMIN", List.of("admin:user:manage"), 0)).thenReturn("jwt-token");

        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");

        LoginResponse response = authService.login(request, "127.0.0.1");

        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getRole()).isEqualTo("ADMIN");
        assertThat(response.getRoles()).containsExactly("ADMIN");
        assertThat(response.getPermissions()).containsExactly("admin:user:manage");
        assertThat(response.getViewFullCatalog()).isNull();
        verify(sysUserMapper).updateById(user);
    }

    @Test
    void login_success_shouldReturnViewFullCatalog() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
            .thenReturn(authentication);

        SysUser user = new SysUser();
        user.setUserId("USER-002");
        user.setUsername("factory");
        user.setNickname("工厂管理员");
        user.setViewFullCatalog(true);
        when(sysUserMapper.selectByUsername("factory")).thenReturn(user);
        when(userRoleService.getRoleCodesByUserId("USER-002")).thenReturn(List.of("FACTORY_ADMIN"));
        when(permissionService.getPermissionsByUserId("USER-002")).thenReturn(Set.of("product:read", "product:create"));
        when(userFactoryService.getFactoryCodesByUserId("USER-002")).thenReturn(List.of("F001"));
        when(jwtUtil.generateToken(eq("USER-002"), eq("factory"), eq("工厂管理员"), eq("FACTORY_ADMIN"), any(), anyInt())).thenReturn("jwt-token-2");

        LoginRequest request = new LoginRequest();
        request.setUsername("factory");
        request.setPassword("factory123");

        LoginResponse response = authService.login(request, "127.0.0.1");

        assertThat(response.getViewFullCatalog()).isTrue();
    }

    @Test
    void login_badCredentials_shouldThrowBusinessException() {
        when(authenticationManager.authenticate(any()))
            .thenThrow(new BadCredentialsException("bad creds"));

        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("wrong");

        assertThatThrownBy(() -> authService.login(request, "127.0.0.1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("用户名或密码错误");

        verify(sysUserMapper, never()).selectByUsername(any());
    }

    @Test
    void login_blocked_shouldThrowBusinessException() {
        when(loginAttemptService.isBlocked("127.0.0.1", "admin")).thenReturn(true);

        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");

        assertThatThrownBy(() -> authService.login(request, "127.0.0.1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("登录尝试次数过多");

        verify(authenticationManager, never()).authenticate(any());
    }
}
