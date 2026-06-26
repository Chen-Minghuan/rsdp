package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.PriceHistory;
import com.rsdp.mapper.PriceHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 价格历史服务。
 */
@Service
@RequiredArgsConstructor
public class PriceHistoryService {

    private final PriceHistoryMapper priceHistoryMapper;

    /**
     * 查询某 RSKU 的价格历史，按时间倒序。
     *
     * @param rskuId RSKU ID
     * @return 价格历史列表
     */
    public List<PriceHistory> listByRsku(String rskuId) {
        return priceHistoryMapper.selectList(
            new QueryWrapper<PriceHistory>()
                .eq("rsku_id", rskuId)
                .orderByDesc("created_at")
        );
    }
}
