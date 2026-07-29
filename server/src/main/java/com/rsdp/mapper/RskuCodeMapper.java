package com.rsdp.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RskuCodeMapper {

    /**
     * 原子递增并返回 RSKU 编码流水号。
     *
     * @param rspuCode     RSPU 业务编码
     * @param factoryCode  工厂代码
     * @param materialCode 材质码
     * @return 下一个流水号
     */
    Long allocateSequence(@Param("rspuCode") String rspuCode,
                          @Param("factoryCode") String factoryCode,
                          @Param("materialCode") String materialCode);
}
