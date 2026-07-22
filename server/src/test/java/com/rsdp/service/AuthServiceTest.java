package com.rsdp.service;

import com.rsdp.dto.request.LoginRequest;
import com.rsdp.dto.request.RegisterRequest;
import com.rsdp.dto.response.LoginResponse;
import com.rsdp.dto.response.RegisterResponse;
import com.rsdp.entity.SysUser;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private InviteService inviteService;

    @Mock
    private AuditLogService auditLogService;

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

    @Test
    void register_success_shouldCreateViewerWithInviteCode() {
        when(sysUserMapper.selectByUsername("newbie")).thenReturn(null);
        when(passwordEncoder.encode("secret123")).thenReturn("hash");
        when(inviteService.generateUniqueInviteCode()).thenReturn("ABCD2345");

        RegisterRequest request = new RegisterRequest();
        request.setUsername("newbie");
        request.setPassword("secret123");

        RegisterResponse response = authService.register(request);

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper).insert(captor.capture());
        assertThat(captor.getValue().getInviteCode()).isEqualTo("ABCD2345");
        assertThat(captor.getValue().getCertifiedDesigner()).isFalse();
        assertThat(captor.getValue().getNickname()).isEqualTo("newbie");
        verify(userRoleService).assignRoleByCode(any(), eq("VIEWER"));
        verify(inviteService, never()).bindInviter(any(), any());
        verify(auditLogService).logCreate(eq("sys_user"), any(), any(SysUser.class), eq("newbie"));
        assertThat(response.getInviteCode()).isEqualTo("ABCD2345");
    }

    @Test
    void register_withInviteCode_shouldBindInviter() {
        when(sysUserMapper.selectByUsername("newbie")).thenReturn(null);
        when(passwordEncoder.encode("secret123")).thenReturn("hash");
        when(inviteService.generateUniqueInviteCode()).thenReturn("ABCD2345");

        RegisterRequest request = new RegisterRequest();
        request.setUsername("newbie");
        request.setPassword("secret123");
        request.setInviteCode("INV99999");

        authService.register(request);

        verify(inviteService).bindInviter(any(SysUser.class), eq("INV99999"));
        // invite_record.invitee_id 外键要求用户先入库：insert 必须先于 bindInviter
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(sysUserMapper, inviteService);
        inOrder.verify(sysUserMapper).insert(any(SysUser.class));
        inOrder.verify(inviteService).bindInviter(any(SysUser.class), eq("INV99999"));
        // bindInviter 只改内存中的 invited_by，需 update 持久化
        inOrder.verify(sysUserMapper).updateById(any(SysUser.class));
    }

    @Test
    void register_duplicateUsername_shouldThrow() {
        when(sysUserMapper.selectByUsername("admin")).thenReturn(new SysUser());

        RegisterRequest request = new RegisterRequest();
        request.setUsername("admin");
        request.setPassword("secret123");

        assertThatThrownBy(() -> authService.register(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("用户名已存在");
        verify(sysUserMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void register_auditSnapshotShouldExcludePasswordHash() {
        when(sysUserMapper.selectByUsername("newbie")).thenReturn(null);
        when(passwordEncoder.encode("secret123")).thenReturn("$2a$10$bcryptHashValue");
        when(inviteService.generateUniqueInviteCode()).thenReturn("ABCD2345");

        RegisterRequest request = new RegisterRequest();
        request.setUsername("newbie");
        request.setPassword("secret123");

        authService.register(request);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(auditLogService).logCreate(eq("sys_user"), any(), captor.capture(), eq("newbie"));
        SysUser snapshot = (SysUser) captor.getValue();
        // 审计快照不得携带 BCrypt 密码哈希
        assertThat(snapshot.getPasswordHash()).isNull();
        assertThat(snapshot.getUsername()).isEqualTo("newbie");
    }

    @Test
    void register_usernameConflictOnInsert_shouldThrowBusinessException() {
        // 并发兜底：先查通过，插入时用户名被抢注
        when(sysUserMapper.selectByUsername("newbie")).thenReturn(null, new SysUser());
        when(passwordEncoder.encode("secret123")).thenReturn("hash");
        when(inviteService.generateUniqueInviteCode()).thenReturn("ABCD2345");
        doThrow(new DataIntegrityViolationException("duplicate key: sys_user_username_key"))
            .when(sysUserMapper).insert(any(SysUser.class));

        RegisterRequest request = new RegisterRequest();
        request.setUsername("newbie");
        request.setPassword("secret123");

        assertThatThrownBy(() -> authService.register(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("用户名已存在");
    }

    @Test
    void register_inviteCodeConflictOnInsert_shouldRetryWithNewCode() {
        // 并发兜底：首次插入撞邀请码唯一索引，换新码后成功
        when(sysUserMapper.selectByUsername("newbie")).thenReturn(null);
        when(passwordEncoder.encode("secret123")).thenReturn("hash");
        when(inviteService.generateUniqueInviteCode()).thenReturn("AAAA2222", "BBBB3333");
        when(sysUserMapper.insert(any(SysUser.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate key: idx_sys_user_invite_code"))
            .thenReturn(1);

        RegisterRequest request = new RegisterRequest();
        request.setUsername("newbie");
        request.setPassword("secret123");

        RegisterResponse response = authService.register(request);

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper, times(2)).insert(captor.capture());
        assertThat(captor.getValue().getInviteCode()).isEqualTo("BBBB3333");
        assertThat(response.getInviteCode()).isEqualTo("BBBB3333");
    }

    @Test
    void getCurrentUser_lazyInviteCodeConflict_shouldRetryWithNewCode() {
        SysUser user = new SysUser();
        user.setUserId("USER-001");
        user.setUsername("admin");
        user.setNickname("管理员");
        when(sysUserMapper.selectByUsername("admin")).thenReturn(user);
        when(userRoleService.getRoleCodesByUserId("USER-001")).thenReturn(List.of("ADMIN"));
        when(permissionService.getPermissionsByUserId("USER-001")).thenReturn(Set.of());
        when(userFactoryService.getFactoryCodesByUserId("USER-001")).thenReturn(List.of());
        when(inviteService.generateUniqueInviteCode()).thenReturn("AAAA2222", "BBBB3333");
        when(sysUserMapper.updateById(any(SysUser.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate key: idx_sys_user_invite_code"))
            .thenReturn(1);

        LoginResponse response = authService.getCurrentUser("admin");

        verify(sysUserMapper, times(2)).updateById(any(SysUser.class));
        assertThat(response.getInviteCode()).isEqualTo("BBBB3333");
    }
}
