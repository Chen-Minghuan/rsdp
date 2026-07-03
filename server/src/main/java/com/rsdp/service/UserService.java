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

        if (request.getNickname() != null) {
            user.setNickname(request.getNickname());
        }
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);

        userRoleService.assignRole(userId, role.getRoleId());
        userFactoryService.resetFactories(userId, request.getFactoryCodes());

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
        user.setStatus(status);
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
        return toResponse(user);
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
