package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.DesignerProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 设计师画像 Mapper。
 */
@Mapper
public interface DesignerProfileMapper extends BaseMapper<DesignerProfile> {

    /**
     * 根据用户 ID 查询设计师画像。
     *
     * @param userId 用户 ID
     * @return 设计师画像
     */
    DesignerProfile selectByUserId(@Param("userId") String userId);
}
