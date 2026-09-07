package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.entity.SchemeItem;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.SchemeItemMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 方案售价合计计算服务（方案/项目总价销售价口径改造：方式 A 响应层实时换算，无数据库迁移）。
 *
 * <p>口径：对方案明细绑定的具体 RSKU 逐个经
 * {@link PricingService#resolveSalePrice(RspuMaster, RskuSupply)} 解析标准售价
 * （建议销售价 → 成本×品类倍率 → 成本×全局倍率），乘以数量求和；未定价项跳过求和。
 * 合计口径与 {@code scheme.total_price} 一致（含方案全部明细，不做数据范围过滤），
 * 售价合计全角色可见。RSPU/RSKU 一律批量加载，避免循环单查。</p>
 */
@Service
@RequiredArgsConstructor
public class SchemeSalePriceService {

    private final SchemeItemMapper schemeItemMapper;
    private final RspuMapper rspuMapper;
    private final RskuSupplyMapper rskuSupplyMapper;
    private final PricingService pricingService;

    /**
     * 批量计算多个方案的售价合计（方案列表/项目聚合场景）。
     *
     * @param schemeIds 方案 ID 集合
     * @return schemeId → 售价合计（无明细或全部未定价的方案不出现）
     */
    public Map<String, BigDecimal> batchTotalSalePrices(Collection<String> schemeIds) {
        if (schemeIds == null || schemeIds.isEmpty()) {
            return Map.of();
        }
        List<SchemeItem> items = schemeItemMapper.selectList(
            new QueryWrapper<SchemeItem>().in("scheme_id", schemeIds));
        if (items.isEmpty()) {
            return Map.of();
        }
        Map<String, RspuMaster> rspuMap = batchRspuMap(
            items.stream().map(SchemeItem::getRspuId).distinct().toList());
        Map<String, RskuSupply> rskuMap = batchRskuMap(
            items.stream().map(SchemeItem::getRskuId).distinct().toList());

        Map<String, BigDecimal> result = new HashMap<>();
        for (SchemeItem item : items) {
            BigDecimal salePrice = pricingService.resolveSalePrice(
                rspuMap.get(item.getRspuId()), rskuMap.get(item.getRskuId()));
            if (salePrice == null) {
                // 未定价项跳过求和
                continue;
            }
            result.merge(item.getSchemeId(),
                salePrice.multiply(BigDecimal.valueOf(effectiveQuantity(item))),
                BigDecimal::add);
        }
        return result;
    }

    /**
     * 基于已批量加载的 RSPU/RSKU 映射，计算单方案明细的售价合计（方案详情场景，
     * 复用详情组装已有的批量查询结果，未定价项跳过）。
     *
     * @param items    方案明细（含全部明细，不做数据范围过滤，与 scheme.total_price 口径一致）
     * @param rspuMap  rspuId → RSPU 主档
     * @param rskuMap  rskuId → 供应单元
     * @return 售价合计（无已定价项时为 0）
     */
    public BigDecimal sumItemsSalePrice(List<SchemeItem> items,
                                        Map<String, RspuMaster> rspuMap,
                                        Map<String, RskuSupply> rskuMap) {
        BigDecimal total = BigDecimal.ZERO;
        for (SchemeItem item : items) {
            BigDecimal salePrice = pricingService.resolveSalePrice(
                rspuMap.get(item.getRspuId()), rskuMap.get(item.getRskuId()));
            if (salePrice == null) {
                continue;
            }
            total = total.add(salePrice.multiply(BigDecimal.valueOf(effectiveQuantity(item))));
        }
        return total;
    }

    /**
     * 解析单个方案明细项的标准售价（全角色可见）。
     *
     * @param rspu 产品主档（可空）
     * @param rsku 供应单元（可空）
     * @return 标准售价；三级链均解析不出时返回 null（未定价）
     */
    public BigDecimal salePriceOf(RspuMaster rspu, RskuSupply rsku) {
        return pricingService.resolveSalePrice(rspu, rsku);
    }

    /**
     * 有效数量：空或小于等于 0 时按 1 计（与方案创建/更新时的聚合口径一致）。
     *
     * @param item 方案明细
     * @return 有效数量
     */
    private int effectiveQuantity(SchemeItem item) {
        return item.getQuantity() != null && item.getQuantity() > 0 ? item.getQuantity() : 1;
    }

    private Map<String, RspuMaster> batchRspuMap(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        return rspuMapper.selectList(
            new QueryWrapper<RspuMaster>().in("rspu_id", rspuIds)
        ).stream().collect(Collectors.toMap(RspuMaster::getRspuId, r -> r, (a, b) -> a));
    }

    private Map<String, RskuSupply> batchRskuMap(List<String> rskuIds) {
        if (rskuIds.isEmpty()) {
            return Map.of();
        }
        return rskuSupplyMapper.selectList(
            new QueryWrapper<RskuSupply>().in("rsku_id", rskuIds)
        ).stream().collect(Collectors.toMap(RskuSupply::getRskuId, r -> r, (a, b) -> a));
    }
}
