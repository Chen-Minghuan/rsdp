package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.PricingRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 品类级加价倍率 Mapper。
 */
@Mapper
public interface PricingRuleMapper extends BaseMapper<PricingRule> {
}
