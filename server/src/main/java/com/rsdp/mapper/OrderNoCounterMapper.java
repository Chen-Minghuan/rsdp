package com.rsdp.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 订单号每日序号计数器 Mapper。
 *
 * <p>使用 PostgreSQL {@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING}
 * 保证日期维度下的订单序号原子递增，避免软删除场景下 {@code COUNT+1} 与唯一索引冲突。</p>
 */
@Mapper
public interface OrderNoCounterMapper {

    /**
     * 原子递增并返回指定日期前缀的下一个订单序号。
     *
     * @param datePart 日期前缀，如 20260720
     * @return 下一个序号
     */
    Long allocateSequence(@Param("datePart") String datePart);
}
