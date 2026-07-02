package com.rsdp.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 变体编码流水号计数器 Mapper。
 */
@Mapper
public interface VariantCodeMapper {

    /**
     * 原子递增并返回指定 RSPU 下的变体流水号。
     *
     * @param rspuId RSPU ID
     * @return 下一个流水号
     */
    Long allocateSequence(@Param("rspuId") String rspuId);
}
