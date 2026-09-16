package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 系统用户 Mapper。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 按用户名查询用户。
     *
     * @param username 用户名
     * @return 用户实体
     */
    default SysUser selectByUsername(@Param("username") String username) {
        return selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysUser>()
                .eq("username", username)
        );
    }

    /**
     * 查询平台运营角色（ADMIN/EDITOR）的启用用户，作为留资跟进人候选。
     *
     * @return 用户列表（userId/username/nickname）
     */
    @org.apache.ibatis.annotations.Select("SELECT u.user_id, u.username, u.nickname FROM sys_user u"
        + " WHERE u.status = 'active' AND EXISTS (SELECT 1 FROM sys_user_role ur"
        + " JOIN sys_role r ON r.role_id = ur.role_id"
        + " WHERE ur.user_id = u.user_id AND r.role_code IN ('ADMIN', 'EDITOR'))"
        + " ORDER BY u.username")
    java.util.List<SysUser> selectPlatformOperators();

    /**
     * 按用户 ID 查询启用状态的设计师（DESIGNER 角色）用户，用于留资归属校验。
     *
     * @param userId 用户 ID
     * @return 用户实体；不存在/非设计师/已停用返回 {@code null}
     */
    @org.apache.ibatis.annotations.Select("SELECT u.user_id, u.username, u.nickname FROM sys_user u"
        + " WHERE u.user_id = #{userId} AND u.status = 'active' AND EXISTS (SELECT 1 FROM sys_user_role ur"
        + " JOIN sys_role r ON r.role_id = ur.role_id"
        + " WHERE ur.user_id = u.user_id AND r.role_code = 'DESIGNER')")
    SysUser selectActiveDesignerById(@Param("userId") String userId);
}
