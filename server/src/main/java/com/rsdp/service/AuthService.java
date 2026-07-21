package com.rsdp.service;

import com.rsdp.dto.request.LoginRequest;
import com.rsdp.dto.request.RegisterRequest;
import com.rsdp.dto.response.LoginResponse;
import com.rsdp.dto.response.RegisterResponse;
import com.rsdp.entity.SysUser;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.util.IdGenerator;
import com.rsdp.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 认证服务。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 公开注册默认角色（rooom TOURIST 映射）。 */
    private static final String DEFAULT_REGISTER_ROLE = "VIEWER";

    private final AuthenticationManager authenticationManager;
    private final SysUserMapper sysUserMapper;
    private final JwtUtil jwtUtil;
    private final UserRoleService userRoleService;
    private final PermissionService permissionService;
    private final UserFactoryService userFactoryService;
    private final LoginAttemptService loginAttemptService;
    private final PasswordEncoder passwordEncoder;
    private final InviteService inviteService;
    private final AuditLogService auditLogService;

    /**
     * 用户登录。
     *
     * @param request 登录请求
     * @param ip      客户端 IP
     * @return 登录响应，包含 JWT
     */
    public LoginResponse login(LoginRequest request, String ip) {
        if (loginAttemptService.isBlocked(ip, request.getUsername())) {
            throw new BusinessException("登录尝试次数过多，请稍后再试");
        }

        try {
            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );
            if (!authentication.isAuthenticated()) {
                loginAttemptService.recordFailure(ip, request.getUsername());
                throw new BusinessException("登录失败");
            }
        } catch (BadCredentialsException e) {
            loginAttemptService.recordFailure(ip, request.getUsername());
            throw new BusinessException("用户名或密码错误");
        }

        loginAttemptService.recordSuccess(ip, request.getUsername());

        SysUser user = sysUserMapper.selectByUsername(request.getUsername());
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        user.setLastLoginAt(LocalDateTime.now());
        sysUserMapper.updateById(user);

        String role = getPrimaryRoleCode(user.getUserId());
        List<String> roles = userRoleService.getRoleCodesByUserId(user.getUserId());
        List<String> permissions = permissionService.getPermissionsByUserId(user.getUserId()).stream().toList();
        List<String> factoryCodes = userFactoryService.getFactoryCodesByUserId(user.getUserId());
        int tokenVersion = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        String token = jwtUtil.generateToken(user.getUserId(), user.getUsername(), user.getNickname(), role, permissions, tokenVersion);
        return new LoginResponse(
            token,
            "Bearer",
            user.getUserId(),
            user.getUsername(),
            user.getNickname(),
            role,
            roles,
            permissions,
            user.getViewFullCatalog(),
            factoryCodes,
            user.getInviteCode(),
            user.getCertifiedDesigner(),
            user.getCompanyId()
        );
    }

    /**
     * 公开注册：创建 VIEWER 角色账号，生成永久邀请码；携带有效邀请码时绑定邀请归因。
     *
     * @param request 注册请求
     * @return 注册响应（含新用户自己的邀请码）
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String username = request.getUsername().trim();
        if (sysUserMapper.selectByUsername(username) != null) {
            throw new BusinessException("用户名已存在");
        }

        SysUser user = new SysUser();
        user.setUserId(IdGenerator.userId());
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setNickname(StringUtils.hasText(request.getNickname()) ? request.getNickname().trim() : username);
        user.setStatus("active");
        user.setViewFullCatalog(false);
        user.setCertifiedDesigner(false);
        user.setInviteCode(inviteService.generateUniqueInviteCode());
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        if (StringUtils.hasText(request.getInviteCode())) {
            inviteService.bindInviter(user, request.getInviteCode());
        }
        sysUserMapper.insert(user);
        userRoleService.assignRoleByCode(user.getUserId(), DEFAULT_REGISTER_ROLE);
        auditLogService.logCreate("sys_user", user.getUserId(), user, username);
        return new RegisterResponse(user.getUserId(), user.getUsername(), user.getNickname(), user.getInviteCode());
    }

    /**
     * 获取当前登录用户信息。
     *
     * @param username 用户名
     * @return 用户信息（不含 JWT）
     */
    public LoginResponse getCurrentUser(String username) {
        SysUser user = sysUserMapper.selectByUsername(username);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        List<String> roles = userRoleService.getRoleCodesByUserId(user.getUserId());
        List<String> permissions = permissionService.getPermissionsByUserId(user.getUserId()).stream().toList();
        List<String> factoryCodes = userFactoryService.getFactoryCodesByUserId(user.getUserId());
        return new LoginResponse(
            null,
            "Bearer",
            user.getUserId(),
            user.getUsername(),
            user.getNickname(),
            getPrimaryRoleCode(user.getUserId()),
            roles,
            permissions,
            user.getViewFullCatalog(),
            factoryCodes,
            user.getInviteCode(),
            user.getCertifiedDesigner(),
            user.getCompanyId()
        );
    }

    private String getPrimaryRoleCode(String userId) {
        List<String> roleCodes = userRoleService.getRoleCodesByUserId(userId);
        return roleCodes.isEmpty() ? "USER" : roleCodes.get(0);
    }
}
