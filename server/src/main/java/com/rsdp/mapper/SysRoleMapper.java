package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 角色 Mapper。
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /**
     * 按角色编码查询角色。
     *
     * @param roleCode 角色编码
     * @return 角色实体
     */
    default SysRole selectByRoleCode(@Param("roleCode") String roleCode) {
        return selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysRole>()
                .eq("role_code", roleCode)
        );
    }
}
