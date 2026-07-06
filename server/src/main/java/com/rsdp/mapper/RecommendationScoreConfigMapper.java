package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.RecommendationScoreConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 推荐打分配置 Mapper。
 */
@Mapper
public interface RecommendationScoreConfigMapper extends BaseMapper<RecommendationScoreConfig> {

    /**
     * 根据配置键查询配置。
     *
     * @param configKey 配置键
     * @return 配置
     */
    RecommendationScoreConfig selectByConfigKey(@Param("configKey") String configKey);

    /**
     * 将其他默认配置设为非默认。
     *
     * @param excludeConfigId 排除的配置 ID
     * @return 更新行数
     */
    int clearOtherDefaults(@Param("excludeConfigId") String excludeConfigId);
}
