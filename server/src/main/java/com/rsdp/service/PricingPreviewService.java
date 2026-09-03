package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rsdp.common.PageResult;
import com.rsdp.dto.response.PricingPreviewItemResponse;
import com.rsdp.dto.response.PricingPreviewSummaryResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.PricingRule;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.CategoryDictMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.security.datascope.DataScopeHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 定价试算服务：按"在售且成本最低的 RSKU"为基准（与产品列表"最低出厂价"口径一致）
 * 逐产品解析标准售价与来源，输出试算清单与总览计数。
 *
 * <p>成本与毛利率按现有 factory_price 权限掩码：无权限角色不返回。
 * 全程批量查询（RSPU 分页 + 批量取最低成本 RSKU + 批量取品类名称），循环内不逐条查库。</p>
 */
@Service
@RequiredArgsConstructor
public class PricingPreviewService {

    /** 未软删且已定价（factory_price 非空）RSKU 的存在性子查询（@TableLogic 对原生 SQL 不生效，需显式带 deleted_at 条件）。 */
    private static final String PRICED_RSKU_SUBQUERY =
        "SELECT 1 FROM rsku_supply s WHERE s.rspu_id = rspu_master.rspu_id "
            + "AND s.factory_price IS NOT NULL AND s.deleted_at IS NULL";

    private final RspuMapper rspuMapper;
    private final RskuSupplyMapper rskuSupplyMapper;
    private final CategoryDictMapper categoryDictMapper;
    private final PricingService pricingService;
    private final PricingRuleService pricingRuleService;
    private final DataScopeHelper dataScopeHelper;

    /**
     * 定价试算清单（分页）。
     *
     * @param categoryCode 品类编码筛选（可空）
     * @param source       售价来源筛选（MANUAL/CATEGORY_RULE/GLOBAL/NONE，可空；非法值抛业务异常）
     * @param keyword      关键词（匹配商品名称/定位标签/业务编码，可空）
     * @param page         页码
     * @param size         每页条数
     * @return 分页试算清单
     */
    public PageResult<PricingPreviewItemResponse> preview(String categoryCode, String source,
                                                          String keyword, long page, long size) {
        Set<String> ruleCategories = pricingRuleService.listAllRules().keySet();
        QueryWrapper<RspuMaster> wrapper = buildQuery(categoryCode, keyword, source, ruleCategories);
        if (wrapper == null) {
            // CATEGORY_RULE 筛选但无任何品类规则：结果恒为空
            return PageResult.of(0, page, size, List.of());
        }
        wrapper.orderByAsc("rspu_id");
        Page<RspuMaster> result = rspuMapper.selectPage(Page.of(page, size), wrapper);

        List<RspuMaster> rspus = result.getRecords();
        List<String> rspuIds = rspus.stream().map(RspuMaster::getRspuId).toList();
        Map<String, RskuSupply> minCostRskuMap = batchMinCostRsku(rspuIds);
        Map<String, String> categoryNames = batchCategoryNames(rspus.stream()
            .map(RspuMaster::getCategoryCode).toList());

        List<PricingPreviewItemResponse> rows = rspus.stream()
            .map(rspu -> buildRow(rspu, minCostRskuMap.get(rspu.getRspuId()), categoryNames))
            .toList();
        return PageResult.of(result.getTotal(), page, size, rows);
    }

    /**
     * 定价试算总览计数（全部在售产品，按售价来源分组 + 低于成本数）。
     *
     * @return 总览计数
     */
    public PricingPreviewSummaryResponse summary() {
        // 轻量列全量拉取（在售产品），批量取最低成本 RSKU 后内存分组计数
        List<RspuMaster> rspus = rspuMapper.selectList(new QueryWrapper<RspuMaster>()
            .select("rspu_id", "retail_price", "category_code")
            .eq("status", "active"));
        Map<String, RskuSupply> minCostRskuMap = batchMinCostRsku(
            rspus.stream().map(RspuMaster::getRspuId).toList());

        PricingPreviewSummaryResponse summary = new PricingPreviewSummaryResponse();
        for (RspuMaster rspu : rspus) {
            PricingService.SalePriceDetail detail =
                pricingService.resolveSalePriceDetail(rspu, minCostRskuMap.get(rspu.getRspuId()));
            switch (detail.source()) {
                case PricingService.SOURCE_MANUAL -> summary.setManual(summary.getManual() + 1);
                case PricingService.SOURCE_CATEGORY_RULE -> summary.setCategoryRule(summary.getCategoryRule() + 1);
                case PricingService.SOURCE_GLOBAL -> summary.setGlobal(summary.getGlobal() + 1);
                default -> summary.setUnpriced(summary.getUnpriced() + 1);
            }
            if (PricingService.isBelowCost(detail.salePrice(), detail.cost())) {
                summary.setBelowCost(summary.getBelowCost() + 1);
            }
        }
        summary.setTotal(rspus.size());
        return summary;
    }

    /**
     * 组装试算行：成本/毛利率按 factory_price 权限掩码。
     */
    private PricingPreviewItemResponse buildRow(RspuMaster rspu, RskuSupply minCostRsku,
                                                Map<String, String> categoryNames) {
        PricingService.SalePriceDetail detail = pricingService.resolveSalePriceDetail(rspu, minCostRsku);
        boolean canViewCost = minCostRsku != null
            && dataScopeHelper.canViewFactoryPrice(minCostRsku.getFactoryCode());

        PricingPreviewItemResponse row = new PricingPreviewItemResponse();
        row.setRspuId(rspu.getRspuId());
        row.setRspuCode(rspu.getRspuCode());
        row.setProductName(StringUtils.hasText(rspu.getProductName())
            ? rspu.getProductName() : rspu.getPositioningLabel());
        row.setCategoryCode(rspu.getCategoryCode());
        row.setCategoryName(categoryNames.get(rspu.getCategoryCode()));
        row.setSalePrice(detail.salePrice());
        row.setPriceSource(detail.source());
        row.setAppliedMultiplier(detail.appliedMultiplier());
        row.setBelowCost(PricingService.isBelowCost(detail.salePrice(), detail.cost()));
        if (canViewCost) {
            row.setCostPrice(detail.cost());
            if (detail.salePrice() != null && detail.cost() != null && detail.salePrice().signum() > 0) {
                row.setMarginRate(detail.salePrice().subtract(detail.cost())
                    .divide(detail.salePrice(), 4, RoundingMode.HALF_UP));
            }
        }
        return row;
    }

    /**
     * 构建试算查询：在售产品 + 品类/关键词筛选 + 售价来源筛选（下推 SQL 保证分页计数正确）。
     *
     * @return 查询条件；CATEGORY_RULE 筛选但无品类规则时返回 {@code null}（结果恒为空）
     */
    private QueryWrapper<RspuMaster> buildQuery(String categoryCode, String keyword, String source,
                                                Set<String> ruleCategories) {
        QueryWrapper<RspuMaster> wrapper = new QueryWrapper<>();
        wrapper.eq("status", "active");
        if (StringUtils.hasText(categoryCode)) {
            wrapper.eq("category_code", categoryCode.trim());
        }
        if (StringUtils.hasText(keyword)) {
            String like = "%" + keyword.trim() + "%";
            wrapper.and(w -> w.like("product_name", like)
                .or().like("positioning_label", like)
                .or().like("rspu_code", like));
        }
        if (!StringUtils.hasText(source)) {
            return wrapper;
        }
        switch (source.trim()) {
            case PricingService.SOURCE_MANUAL -> wrapper.isNotNull("retail_price");
            case PricingService.SOURCE_CATEGORY_RULE -> {
                if (ruleCategories.isEmpty()) {
                    return null;
                }
                wrapper.isNull("retail_price")
                    .in("category_code", ruleCategories)
                    .exists(PRICED_RSKU_SUBQUERY);
            }
            case PricingService.SOURCE_GLOBAL -> {
                wrapper.isNull("retail_price");
                if (!ruleCategories.isEmpty()) {
                    wrapper.and(w -> w.notIn("category_code", ruleCategories).or().isNull("category_code"));
                }
                wrapper.exists(PRICED_RSKU_SUBQUERY);
            }
            case PricingService.SOURCE_NONE -> wrapper.isNull("retail_price")
                .notExists(PRICED_RSKU_SUBQUERY);
            default -> throw new BusinessException("非法售价来源筛选: " + source + "（支持 MANUAL/CATEGORY_RULE/GLOBAL/NONE）");
        }
        return wrapper;
    }

    /**
     * 批量查询各 RSPU 成本最低且已定价的 RSKU（未软删）。
     *
     * @param rspuIds RSPU ID 列表
     * @return RSPU ID → 最低成本 RSKU 映射
     */
    private Map<String, RskuSupply> batchMinCostRsku(List<String> rspuIds) {
        if (rspuIds == null || rspuIds.isEmpty()) {
            return Map.of();
        }
        List<RskuSupply> rskus = rskuSupplyMapper.selectList(new QueryWrapper<RskuSupply>()
            .in("rspu_id", rspuIds)
            .isNotNull("factory_price"));
        return rskus.stream()
            .filter(r -> r.getFactoryPrice() != null)
            .collect(Collectors.groupingBy(
                RskuSupply::getRspuId,
                Collectors.collectingAndThen(
                    Collectors.minBy(Comparator.comparing(RskuSupply::getFactoryPrice)),
                    opt -> opt.orElse(null)
                )
            ));
    }

    /**
     * 批量查询品类名称（category_dict dict_type=category）。
     *
     * @param categoryCodes 品类编码列表
     * @return 品类编码 → 品类名称映射
     */
    private Map<String, String> batchCategoryNames(List<String> categoryCodes) {
        List<String> codes = categoryCodes.stream().filter(StringUtils::hasText).distinct().toList();
        if (codes.isEmpty()) {
            return Map.of();
        }
        return categoryDictMapper.selectList(new QueryWrapper<CategoryDict>()
                .eq("dict_type", "category")
                .in("dict_code", codes))
            .stream()
            .collect(Collectors.toMap(CategoryDict::getDictCode, CategoryDict::getDictName, (a, b) -> a));
    }
}
