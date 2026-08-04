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

import java.util.HashSet;
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
            // 注意：缓存值需为非 final 具体类型（Redis 序列化器按 NON_FINAL 写类型信息），
            // 不能返回 Collections.emptySet()，否则反序列化后类型错乱导致调用方 ClassCastException
            return new HashSet<>();
        }

        List<Long> permissionIds = sysRolePermissionMapper.selectList(
            new QueryWrapper<SysRolePermission>().in("role_id", roleIds)
        ).stream().map(SysRolePermission::getPermissionId).distinct().toList();

        if (permissionIds.isEmpty()) {
            return new HashSet<>();
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
