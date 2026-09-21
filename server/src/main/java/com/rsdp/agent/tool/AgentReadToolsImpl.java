package com.rsdp.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.agent.domain.MarketingProductQueryService;
import com.rsdp.agent.domain.ProductSearchCriteria;
import com.rsdp.agent.domain.ProductSearchItem;
import com.rsdp.agent.domain.ProductSearchResult;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuRelation;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuRelationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link AgentReadTools} 实现：转发到领域查询层 / 关系表 / 产品主档（全部只读）。
 */
@Component
@RequiredArgsConstructor
public class AgentReadToolsImpl implements AgentReadTools {

    private final MarketingProductQueryService productQueryService;
    private final RspuRelationMapper relationMapper;
    private final RspuMapper rspuMapper;

    @Override
    public ProductSearchResult searchProducts(ProductSearchCriteria criteria) {
        return productQueryService.search(criteria);
    }

    @Override
    public List<RspuRelation> listCompanionRelations(String anchorRspuId) {
        return relationMapper.selectList(new QueryWrapper<RspuRelation>()
            .eq("anchor_rspu_id", anchorRspuId)
            .in("relation_type", "official", "ai_verified")
            .eq("status", "active")
            .orderByAsc("sort_order"));
    }

    @Override
    public Set<String> listExcludedRspuIds(String anchorRspuId) {
        return relationMapper.selectList(new QueryWrapper<RspuRelation>()
                .eq("anchor_rspu_id", anchorRspuId)
                .eq("relation_type", "exclude")
                .eq("status", "active"))
            .stream()
            .map(RspuRelation::getRelatedRspuId)
            .collect(Collectors.toSet());
    }

    @Override
    public Map<String, RspuMaster> findProducts(Collection<String> rspuIds) {
        if (rspuIds == null || rspuIds.isEmpty()) {
            return Map.of();
        }
        return rspuMapper.selectList(new QueryWrapper<RspuMaster>().in("rspu_id", rspuIds))
            .stream()
            .collect(Collectors.toMap(RspuMaster::getRspuId, Function.identity(), (a, b) -> a));
    }

    @Override
    public List<ProductSearchItem> findProductItems(Collection<String> rspuIds) {
        return productQueryService.findItemsByIds(rspuIds);
    }
}
