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
        when(jwtUtil.generateToken("USER-001", "admin", "管理员", "ADMIN", List.of("admin:user:manage"))).thenReturn("jwt-token");

        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");

        LoginResponse response = authService.login(request);

        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getRole()).isEqualTo("ADMIN");
        assertThat(response.getRoles()).containsExactly("ADMIN");
        assertThat(response.getPermissions()).containsExactly("admin:user:manage");
        verify(sysUserMapper).updateById(user);
    }

    @Test
    void login_badCredentials_shouldThrowBusinessException() {
        when(authenticationManager.authenticate(any()))
            .thenThrow(new BadCredentialsException("bad creds"));

        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("wrong");

        assertThatThrownBy(() -> authService.login(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("用户名或密码错误");

        verify(sysUserMapper, never()).selectByUsername(any());
    }
}
