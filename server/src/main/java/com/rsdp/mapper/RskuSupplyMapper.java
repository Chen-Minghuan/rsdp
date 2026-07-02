package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.RskuSupply;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RskuSupplyMapper extends BaseMapper<RskuSupply> {

    /**
     * 批量插入 RSKU 报价。
     *
     * @param rskus 报价列表
     * @return 插入行数
     */
    int insertBatch(@Param("rskus") List<RskuSupply> rskus);

    /**
     * 批量插入（空集合安全）。
     *
     * @param rskus 报价列表
     * @return 插入行数
     */
    default int insertBatchSafe(List<RskuSupply> rskus) {
        if (rskus == null || rskus.isEmpty()) {
            return 0;
        }
        return insertBatch(rskus);
    }
}
