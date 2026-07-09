package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.RspuFactoryMapping;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RspuFactoryMappingMapper extends BaseMapper<RspuFactoryMapping> {

    /**
     * 查询某 RSPU 下按发货地筛选的可用工厂映射。
     */
    @Select("""
        SELECT m.* FROM rspu_factory_mapping m
        LEFT JOIN factory_warehouse w ON m.shipping_warehouse_id = w.warehouse_id
        WHERE m.rspu_id = #{rspuId}
          AND m.status = 'active'
          AND (#{province} IS NULL OR w.province = #{province})
        ORDER BY m.is_primary DESC, m.created_at
        """)
    List<RspuFactoryMapping> selectActiveByRspuAndProvince(@Param("rspuId") String rspuId,
                                                           @Param("province") String province);
}
