package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.DesignOrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 设计订单明细 Mapper。
 */
@Mapper
public interface DesignOrderItemMapper extends BaseMapper<DesignOrderItem> {

    /**
     * 批量插入订单明细。
     *
     * @param items 明细列表
     * @return 插入行数
     */
    int insertBatch(@Param("items") List<DesignOrderItem> items);

    /**
     * 批量插入订单明细（空集合安全）。
     *
     * @param items 明细列表
     * @return 插入行数
     */
    default int insertBatchSafe(List<DesignOrderItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        return insertBatch(items);
    }
}
