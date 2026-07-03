package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 权限 Mapper。
 */
@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    /**
     * 按权限编码查询权限。
     *
     * @param permissionCode 权限编码
     * @return 权限实体
     */
    default SysPermission selectByPermissionCode(@Param("permissionCode") String permissionCode) {
        return selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysPermission>()
                .eq("permission_code", permissionCode)
        );
    }
}
