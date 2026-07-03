package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户角色关联 Mapper。
 */
@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {

    /**
     * 查询用户的所有角色 ID。
     *
     * @param userId 用户 ID
     * @return 角色 ID 列表
     */
    default List<Long> selectRoleIdsByUserId(@Param("userId") String userId) {
        return selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysUserRole>()
                .eq("user_id", userId)
        ).stream().map(SysUserRole::getRoleId).toList();
    }
}
