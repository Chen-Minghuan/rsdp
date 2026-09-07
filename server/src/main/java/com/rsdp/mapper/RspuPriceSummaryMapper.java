package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.RspuPriceSummary;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * RSPU 价格投影汇总 Mapper。
 */
@Mapper
public interface RspuPriceSummaryMapper extends BaseMapper<RspuPriceSummary> {

    /**
     * 原子 upsert 投影行（并发写同一 RSPU 时安全，避免 select-then-insert 主键冲突
     * 导致 PostgreSQL 事务中止）。
     *
     * @param summary 投影行
     * @return 影响行数
     */
    @Insert("INSERT INTO rspu_price_summary (rspu_id, min_factory_price, max_factory_price, active_rsku_count, updated_at) "
        + "VALUES (#{rspuId}, #{minFactoryPrice}, #{maxFactoryPrice}, #{activeRskuCount}, #{updatedAt}) "
        + "ON CONFLICT (rspu_id) DO UPDATE SET "
        + "min_factory_price = EXCLUDED.min_factory_price, "
        + "max_factory_price = EXCLUDED.max_factory_price, "
        + "active_rsku_count = EXCLUDED.active_rsku_count, "
        + "updated_at = EXCLUDED.updated_at")
    int upsert(RspuPriceSummary summary);
}
