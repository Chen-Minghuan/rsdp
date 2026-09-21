package com.rsdp.agent.domain;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.security.datascope.DataScopeHelper;
import com.rsdp.service.PricingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RSPU → RSKU 确定性解析（报价/方案导出的选价口径）。
 *
 * <p>每 RSPU 取价：{@code selectCapableByRspuIds} → 工厂数据范围过滤 →
 * {@link PricingService#resolveSalePriceDetail} 可定价中取标准售价最低
 * （与 AiMatchingService.batchMinSalePrices 同口径）。无法解析的产品不进结果，
 * 由调用方标记「暂不可报价」。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentRskuResolver {

    /** 解析结果：选中的 RSKU + 售价解析详情（cost 仅限服务端使用，不得出参）。 */
    public record ResolvedRsku(RskuSupply rsku, PricingService.SalePriceDetail price) {
    }

    private final RskuSupplyMapper rskuSupplyMapper;
    private final RspuMapper rspuMapper;
    private final DataScopeHelper dataScopeHelper;
    private final PricingService pricingService;

    /**
     * 批量解析各 RSPU 的最低售价 RSKU。
     *
     * @param rspuIds 产品 ID 集合
     * @return rspuId → 解析结果（不可解析的产品不出现在 Map 中）
     */
    public Map<String, ResolvedRsku> resolveMinPriceRsku(Collection<String> rspuIds) {
        if (rspuIds == null || rspuIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = rspuIds.stream().filter(StringUtils::hasText).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, RspuMaster> rspuMap = rspuMapper.selectList(
                new QueryWrapper<RspuMaster>().in("rspu_id", ids))
            .stream().collect(Collectors.toMap(RspuMaster::getRspuId, r -> r, (a, b) -> a));

        Map<String, ResolvedRsku> result = new HashMap<>();
        for (RskuSupply rsku : rskuSupplyMapper.selectCapableByRspuIds(ids)) {
            if (!dataScopeHelper.canAccessFactory(rsku.getFactoryCode())) {
                continue;
            }
            PricingService.SalePriceDetail detail =
                pricingService.resolveSalePriceDetail(rspuMap.get(rsku.getRspuId()), rsku);
            if (detail == null || detail.salePrice() == null) {
                continue;
            }
            result.merge(rsku.getRspuId(), new ResolvedRsku(rsku, detail),
                (a, b) -> a.price().salePrice().compareTo(b.price().salePrice()) <= 0 ? a : b);
        }
        return result;
    }
}
