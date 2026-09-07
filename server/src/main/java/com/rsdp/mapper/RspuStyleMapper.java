package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.RspuStyle;
import org.apache.ibatis.annotations.Mapper;

/**
 * RSPU 多风格关联 Mapper。
 *
 * <p>rspu_style 为 (rspu_id, style_code) 复合主键表，继承 {@link CompositeKeyMapper}
 * 禁用按伪主键单列的 updateById/deleteById/selectById；整组重建（delete by rspu_id + insert）
 * 按 rspu_id 构造 QueryWrapper 即可，单行读写使用下方复合键方法。</p>
 */
@Mapper
public interface RspuStyleMapper extends CompositeKeyMapper<RspuStyle> {

    /**
     * 按复合主键 (rspu_id, style_code) 查询单条关联。
     *
     * @param rspuId    RSPU ID
     * @param styleCode 风格字典码
     * @return 关联实体，不存在时为 null
     */
    default RspuStyle selectByCompositeKey(String rspuId, String styleCode) {
        return selectOne(new QueryWrapper<RspuStyle>()
            .eq("rspu_id", rspuId)
            .eq("style_code", styleCode));
    }

    /**
     * 按复合主键 (rspu_id, style_code) 更新（SET 取实体非空字段）。
     *
     * @param entity 关联实体，rspuId/styleCode 定位行
     * @return 影响行数
     */
    default int updateByCompositeKey(RspuStyle entity) {
        return update(entity, new QueryWrapper<RspuStyle>()
            .eq("rspu_id", entity.getRspuId())
            .eq("style_code", entity.getStyleCode()));
    }

    /**
     * 按复合主键 (rspu_id, style_code) 删除单条关联。
     *
     * @param rspuId    RSPU ID
     * @param styleCode 风格字典码
     * @return 影响行数
     */
    default int deleteByCompositeKey(String rspuId, String styleCode) {
        return delete(new QueryWrapper<RspuStyle>()
            .eq("rspu_id", rspuId)
            .eq("style_code", styleCode));
    }
}
