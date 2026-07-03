package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.SysUserFactory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户工厂关联 Mapper。
 */
@Mapper
public interface SysUserFactoryMapper extends BaseMapper<SysUserFactory> {

    /**
     * 查询用户关联的所有工厂编码。
     *
     * @param userId 用户 ID
     * @return 工厂编码列表
     */
    default List<String> selectFactoryCodesByUserId(@Param("userId") String userId) {
        return selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysUserFactory>()
                .eq("user_id", userId)
        ).stream().map(SysUserFactory::getFactoryCode).toList();
    }
}
