package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rsdp.dto.request.UserCreateRequest;
import com.rsdp.dto.request.UserUpdateRequest;
import com.rsdp.dto.response.UserResponse;
import com.rsdp.entity.SysRole;
import com.rsdp.entity.SysUser;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.SysRoleMapper;
import com.rsdp.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 用户管理服务。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final UserRoleService userRoleService;
    private final UserFactoryService userFactoryService;

    /**
     * 分页查询用户列表。
     *
     * @param page     页码
     * @param size     每页大小
     * @param keyword  关键词（用户名/昵称）
     * @return 用户分页结果
     */
    @Transactional(readOnly = true)
    public Page<UserResponse> listUsers(int page, int size, String keyword) {
        QueryWrapper<SysUser> wrapper = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like("username", keyword).or().like("nickname", keyword));
        }
        wrapper.orderByDesc("created_at");
        Page<SysUser> userPage = sysUserMapper.selectPage(new Page<>(page, size), wrapper);

        List<UserResponse> records = userPage.getRecords().stream()
            .map(this::toResponse)
            .collect(Collectors.toList());

        Page<UserResponse> result = new Page<>(userPage.getCurrent(), userPage.getSize(), userPage.getTotal());
        result.setRecords(records);
        return result;
    }

    /**
     * 创建用户。
     *
     * @param request 创建请求
     * @return 用户响应
     */
    @Transactional
    public UserResponse createUser(UserCreateRequest request) {
        if (sysUserMapper.selectByUsername(request.getUsername()) != null) {
            throw new BusinessException("用户名已存在");
        }

        SysRole role = sysRoleMapper.selectByRoleCode(request.getRoleCode());
        if (role == null) {
            throw new BusinessException("角色不存在: " + request.getRoleCode());
        }

        SysUser user = new SysUser();
        user.setUserId("USER-" + UUID.randomUUID().toString().toUpperCase().replace("-", ""));
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname());
        user.setStatus("active");
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.insert(user);

        userRoleService.assignRole(user.getUserId(), role.getRoleId());
        userFactoryService.resetFactories(user.getUserId(), request.getFactoryCodes());

        return toResponse(user);
    }

    /**
     * 编辑用户。
     *
     * @param userId  用户 ID
     * @param request 编辑请求
     * @return 用户响应
     */
    @Transactional
    public UserResponse updateUser(String userId, UserUpdateRequest request) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        SysRole role = sysRoleMapper.selectByRoleCode(request.getRoleCode());
        if (role == null) {
            throw new BusinessException("角色不存在: " + request.getRoleCode());
        }

        // 禁止修改当前登录用户自己的角色
        String currentUserId = getCurrentUserId();
        if (userId.equals(currentUserId)) {
            List<String> existingRoleCodes = userRoleService.getRoleCodesByUserId(userId);
            String existingRoleCode = existingRoleCodes.isEmpty() ? null : existingRoleCodes.get(0);
            if (!request.getRoleCode().equals(existingRoleCode)) {
                throw new BusinessException("不能修改当前登录用户自己的角色");
            }
        }

        // 角色变更时递增 token_version，使旧 token 失效
        List<String> existingRoleCodes = userRoleService.getRoleCodesByUserId(userId);
        String existingRoleCode = existingRoleCodes.isEmpty() ? null : existingRoleCodes.get(0);
        boolean roleChanged = !request.getRoleCode().equals(existingRoleCode);

        if (request.getNickname() != null) {
            user.setNickname(request.getNickname());
        }
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);

        userRoleService.assignRole(userId, role.getRoleId());
        userFactoryService.resetFactories(userId, request.getFactoryCodes());

        if (roleChanged) {
            incrementTokenVersion(userId);
        }

        return toResponse(user);
    }

    /**
     * 重置密码。
     *
     * @param userId      用户 ID
     * @param newPassword 新密码
     */
    @Transactional
    public void resetPassword(String userId, String newPassword) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
        incrementTokenVersion(userId);
    }

    /**
     * 切换用户状态。
     *
     * @param userId 用户 ID
     * @param status 状态
     * @return 用户响应
     */
    @Transactional
    public UserResponse updateStatus(String userId, String status) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        // 禁止禁用当前登录用户自己
        if ("disabled".equals(status) && userId.equals(getCurrentUserId())) {
            throw new BusinessException("不能禁用当前登录用户");
        }

        user.setStatus(status);
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);

        // 禁用用户时使旧 token 失效
        if ("disabled".equals(status)) {
            incrementTokenVersion(userId);
        }

        return toResponse(user);
    }

    /**
     * 递增用户 token 版本号，使已签发的 JWT 失效。
     *
     * @param userId 用户 ID
     */
    private void incrementTokenVersion(String userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            return;
        }
        Integer version = user.getTokenVersion();
        user.setTokenVersion(version == null ? 1 : version + 1);
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
    }

    /**
     * 获取当前登录用户 ID。
     *
     * @return 当前用户 ID；未登录返回 null
     */
    private String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        String username = principal.toString();
        SysUser user = sysUserMapper.selectByUsername(username);
        return user == null ? null : user.getUserId();
    }

    private UserResponse toResponse(SysUser user) {
        UserResponse response = new UserResponse();
        response.setUserId(user.getUserId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setStatus(user.getStatus());
        response.setLastLoginAt(user.getLastLoginAt());
        response.setCreatedAt(user.getCreatedAt());
        response.setUpdatedAt(user.getUpdatedAt());

        List<String> roleCodes = userRoleService.getRoleCodesByUserId(user.getUserId());
        if (!roleCodes.isEmpty()) {
            String roleCode = roleCodes.get(0);
            response.setRoleCode(roleCode);
            SysRole role = sysRoleMapper.selectByRoleCode(roleCode);
            if (role != null) {
                response.setRoleName(role.getRoleName());
            }
        }

        response.setFactoryCodes(userFactoryService.getFactoryCodesByUserId(user.getUserId()));
        return response;
    }
}
