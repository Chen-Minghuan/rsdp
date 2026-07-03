package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.SysRole;
import com.rsdp.entity.SysUser;
import com.rsdp.entity.SysUserRole;
import com.rsdp.mapper.SysRoleMapper;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * 用户角色服务。
 */
@Service
@RequiredArgsConstructor
public class UserRoleService {

    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserMapper sysUserMapper;
    private final PermissionService permissionService;

    /**
     * 设置用户角色（先删除再插入，第一批只支持单角色）。
     *
     * @param userId 用户 ID
     * @param roleId 角色 ID
     */
    @Transactional
    public void assignRole(String userId, Long roleId) {
        sysUserRoleMapper.delete(
            new QueryWrapper<SysUserRole>().eq("user_id", userId)
        );
        if (roleId != null) {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(userId);
            userRole.setRoleId(roleId);
            sysUserRoleMapper.insert(userRole);
        }
        permissionService.clearPermissionCache(userId);
    }

    /**
     * 按角色编码分配角色。
     *
     * @param userId   用户 ID
     * @param roleCode 角色编码
     */
    @Transactional
    public void assignRoleByCode(String userId, String roleCode) {
        SysRole role = sysRoleMapper.selectByRoleCode(roleCode);
        if (role == null) {
            throw new IllegalArgumentException("角色不存在: " + roleCode);
        }
        assignRole(userId, role.getRoleId());
    }

    /**
     * 获取用户的角色 ID 列表。
     *
     * @param userId 用户 ID
     * @return 角色 ID 列表
     */
    public List<Long> getRoleIdsByUserId(String userId) {
        return sysUserRoleMapper.selectRoleIdsByUserId(userId);
    }

    /**
     * 获取用户的角色编码列表。
     *
     * @param userId 用户 ID
     * @return 角色编码列表
     */
    public List<String> getRoleCodesByUserId(String userId) {
        List<Long> roleIds = getRoleIdsByUserId(userId);
        if (roleIds.isEmpty()) {
            return Collections.emptyList();
        }
        return sysRoleMapper.selectBatchIds(roleIds).stream()
            .map(SysRole::getRoleCode)
            .toList();
    }

    /**
     * 按用户名获取角色编码列表。
     *
     * @param username 用户名
     * @return 角色编码列表
     */
    public List<String> getRoleCodesByUsername(String username) {
        SysUser user = sysUserMapper.selectByUsername(username);
        return user == null ? Collections.emptyList() : getRoleCodesByUserId(user.getUserId());
    }
}
