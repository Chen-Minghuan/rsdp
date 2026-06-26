package com.rsdp.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RspuCodeMapper {

    /**
     * 原子递增并返回 RSPU 编码流水号。
     *
     * @param categoryCode 品类码
     * @param styleCode    风格码
     * @return 下一个流水号
     */
    Long allocateSequence(@Param("categoryCode") String categoryCode,
                          @Param("styleCode") String styleCode);
}
