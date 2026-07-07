package com.rsdp.service;

import com.rsdp.dto.request.LoginRequest;
import com.rsdp.dto.response.LoginResponse;
import com.rsdp.entity.SysUser;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 认证服务。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final SysUserMapper sysUserMapper;
    private final JwtUtil jwtUtil;
    private final UserRoleService userRoleService;
    private final PermissionService permissionService;
    private final UserFactoryService userFactoryService;

    /**
     * 用户登录。
     *
     * @param request 登录请求
     * @return 登录响应，包含 JWT
     */
    public LoginResponse login(LoginRequest request) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );
            if (!authentication.isAuthenticated()) {
                throw new BusinessException("登录失败");
            }
        } catch (BadCredentialsException e) {
            throw new BusinessException("用户名或密码错误");
        }

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
            factoryCodes
        );
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
            factoryCodes
        );
    }

    private String getPrimaryRoleCode(String userId) {
        List<String> roleCodes = userRoleService.getRoleCodesByUserId(userId);
        return roleCodes.isEmpty() ? "USER" : roleCodes.get(0);
    }
}
