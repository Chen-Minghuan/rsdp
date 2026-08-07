package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.RskuSupply;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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

    /**
     * 查询指定 RSPU 列表中工厂具备对应产品等级能力的 RSKU。
     *
     * <p>过滤条件：</p>
     * <ul>
     *   <li>RSKU 未软删除</li>
     *   <li>关联工厂未软删除</li>
     *   <li>product_level 为空，或工厂能力表包含该等级</li>
     * </ul>
     *
     * @param rspuIds RSPU ID 列表
     * @return 具备能力的 RSKU 列表
     */
    List<RskuSupply> selectCapableByRspuIds(@Param("rspuIds") List<String> rspuIds);

    /**
     * 恢复指定 RSPU 下被级联软删的 RSKU 报价（回收站还原用）。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Update("UPDATE rsku_supply SET deleted_at = NULL WHERE rspu_id = #{rspuId} AND deleted_at IS NOT NULL")
    int restoreByRspuId(String rspuId);

    /**
     * 物理删除指定 RSPU 下的全部 RSKU 报价（回收站彻底删除用）。
     *
     * @param rspuId RSPU ID
     * @return 影响行数
     */
    @Delete("DELETE FROM rsku_supply WHERE rspu_id = #{rspuId}")
    int physicalDeleteByRspuId(String rspuId);
}
