package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.SysRolePermission;
import com.rsdp.entity.SysUserRole;
import com.rsdp.mapper.SysPermissionMapper;
import com.rsdp.mapper.SysRolePermissionMapper;
import com.rsdp.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限查询服务。
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final SysPermissionMapper sysPermissionMapper;

    /**
     * 获取用户拥有的所有权限编码。
     *
     * @param userId 用户 ID
     * @return 权限编码集合
     */
    @Cacheable(value = "userPermissions", key = "#userId")
    public Set<String> getPermissionsByUserId(String userId) {
        List<Long> roleIds = sysUserRoleMapper.selectRoleIdsByUserId(userId);
        if (roleIds.isEmpty()) {
            return Collections.emptySet();
        }

        List<Long> permissionIds = sysRolePermissionMapper.selectList(
            new QueryWrapper<SysRolePermission>().in("role_id", roleIds)
        ).stream().map(SysRolePermission::getPermissionId).distinct().toList();

        if (permissionIds.isEmpty()) {
            return Collections.emptySet();
        }

        return sysPermissionMapper.selectBatchIds(permissionIds).stream()
            .map(p -> p.getPermissionCode())
            .collect(Collectors.toSet());
    }

    /**
     * 清除用户权限缓存。
     *
     * @param userId 用户 ID
     */
    @CacheEvict(value = "userPermissions", key = "#userId")
    public void clearPermissionCache(String userId) {
    }
}
