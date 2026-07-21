package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.ImageAssets;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ImageAssetsMapper extends BaseMapper<ImageAssets> {

    /**
     * 批量插入图片资产。
     *
     * @param assets 图片资产列表
     * @return 插入条数
     */
    int insertBatch(@Param("list") List<ImageAssets> assets);
}
