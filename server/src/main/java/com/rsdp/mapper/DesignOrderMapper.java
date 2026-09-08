package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.DesignOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 设计订单 Mapper。
 */
@Mapper
public interface DesignOrderMapper extends BaseMapper<DesignOrder> {

    /**
     * 统计引用指定方案的订单数（含软删订单——订单是业务凭证，外键不看 deleted_at）。
     *
     * <p>方案彻底删除的前置校验：大于 0 时禁止物理删除。</p>
     *
     * @param schemeId 方案 ID
     * @return 引用订单数
     */
    @Select("SELECT COUNT(*) FROM design_order WHERE scheme_id = #{schemeId}")
    long countSchemeRefsAny(String schemeId);
}
