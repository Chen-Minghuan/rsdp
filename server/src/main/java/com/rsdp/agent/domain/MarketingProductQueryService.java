package com.rsdp.agent.domain;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.agent.config.MarketingAgentProperties;
import com.rsdp.dto.Dimensions;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuVariant;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuVariantMapper;
import com.rsdp.service.DictAliasService;
import com.rsdp.service.DictResolverService;
import com.rsdp.util.SizeSpecParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 营销 Agent 产品查询服务（领域查询层）。
 *
 * <p>Agent/Tool 只面向 {@link ProductSearchCriteria} / {@link ProductSearchResult}，
 * 不感知 MyBatis/pgvector；可见性与审核过滤统一走 {@link ProductVisibilityPolicy}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketingProductQueryService {

    /** 字典类型（与 DictService 允许的字典类型一致）。 */
    private static final String DICT_TYPE_STYLE = "style";
    private static final String DICT_TYPE_MATERIAL = "material";
    private static final String DICT_TYPE_COLOR = "color";
    private static final String DICT_TYPE_CATEGORY = "category";

    /** 尺寸硬过滤场景下的 SQL 预取上限（先粗筛再内存精筛）。 */
    private static final int SIZE_FILTER_PREFETCH_LIMIT = 500;

    private final RspuMapper rspuMapper;
    private final RspuVariantMapper rspuVariantMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final DictAliasService dictAliasService;
    private final DictResolverService dictResolverService;
    private final ProductVisibilityPolicy visibilityPolicy;
    private final MarketingAgentProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 按条件检索在售产品。
     *
     * <p>流程：别名归一 → 可见性 + SQL 条件下推 → 尺寸硬过滤（SQL 粗筛 + 内存精筛）
     * → 补主图/尺寸原文/命中条件 → topN 截断。</p>
     *
     * @param criteria 检索条件
     * @return 检索结果（items 已截断，totalMatched 为截断前总数）
     */
    public ProductSearchResult search(ProductSearchCriteria criteria) {
        if (criteria == null) {
            criteria = new ProductSearchCriteria();
        }
        int topN = criteria.getTopN() != null && criteria.getTopN() > 0
            ? criteria.getTopN() : properties.getSearchTopN();

        // 1. 别名归一：方言叫法 → 字典码（未命中别名表时保留原文参与模糊匹配）
        String styleCode = resolveDictCode(DICT_TYPE_STYLE, criteria.getStyle());
        String materialCode = resolveDictCode(DICT_TYPE_MATERIAL, criteria.getMaterial());
        String colorCode = resolveDictCode(DICT_TYPE_COLOR, criteria.getColor());

        // 2. 构建查询：可见性收口 + 条件下推
        boolean hasSizeFilter = criteria.getMaxWidthMm() != null || criteria.getMinWidthMm() != null;
        QueryWrapper<RspuMaster> wrapper = new QueryWrapper<>();
        visibilityPolicy.applyScope(wrapper);
        if (StringUtils.hasText(criteria.getCategoryCode())) {
            // LLM 抽取的是品类词（如 沙发/座椅），rspu_master.category_code 存的是字典码（SF/FS），
            // 先经 category_dict 名称/别名归一；归一失败保留原文（等值不命中，等价空结果）
            String categoryCode = normalizeCategoryCode(criteria.getCategoryCode().trim());
            wrapper.eq("category_code", categoryCode);
        }
        if (StringUtils.hasText(styleCode)) {
            // MVP 简化：sixDimTags JSON 文本包含匹配；v2 改 JSONB 路径查询（six_dim_tags @> ...）
            // 注意：jsonb 列无 LIKE 运算符（jsonb ~~ unknown 报错），必须显式 ::text 转换
            wrapper.apply("six_dim_tags::text LIKE {0}", "%" + styleCode + "%");
        }
        if (StringUtils.hasText(materialCode)) {
            // MVP 简化：materialTags JSON 文本包含匹配；v2 改 JSONB 查询
            // 注意：jsonb 列无 LIKE 运算符（jsonb ~~ unknown 报错），必须显式 ::text 转换
            wrapper.apply("material_tags::text LIKE {0}", "%" + materialCode + "%");
        }
        if (StringUtils.hasText(criteria.getColor())) {
            // color_primary_name 存的是颜色名文本：字典归一命中时同时匹配字典码与原文，
            // 未命中时按原文模糊匹配
            String colorText = criteria.getColor().trim();
            if (StringUtils.hasText(colorCode) && !colorCode.equals(colorText)) {
                String code = colorCode;
                wrapper.and(w -> w.like("color_primary_name", colorText)
                    .or().like("color_primary_name", code));
            } else {
                wrapper.like("color_primary_name", colorText);
            }
        }
        if (criteria.getBudgetMax() != null) {
            wrapper.le("retail_price", criteria.getBudgetMax());
        }
        if (StringUtils.hasText(criteria.getKeyword())) {
            String like = "%" + criteria.getKeyword().trim() + "%";
            wrapper.and(w -> w.like("product_name", like)
                .or().like("positioning_label", like));
        }

        List<RspuMaster> rspus;
        int totalMatched;
        if (hasSizeFilter) {
            // 3. 尺寸硬过滤：SQL 粗筛（有未删除变体的产品）→ 内存按变体尺寸精筛
            wrapper.exists("SELECT 1 FROM rspu_variant v WHERE v.rspu_id = rspu_master.rspu_id"
                + " AND v.deleted_at IS NULL");
            wrapper.orderByDesc("created_at").last("LIMIT " + SIZE_FILTER_PREFETCH_LIMIT);
            rspus = filterByWidthMm(rspuMapper.selectList(wrapper), criteria);
            totalMatched = rspus.size();
            rspus = rspus.stream().limit(topN).toList();
        } else {
            Long total = rspuMapper.selectCount(wrapper);
            totalMatched = total != null ? total.intValue() : 0;
            wrapper.orderByDesc("created_at").last("LIMIT " + topN);
            rspus = rspuMapper.selectList(wrapper);
        }
        if (rspus.isEmpty()) {
            ProductSearchResult empty = new ProductSearchResult();
            empty.setItems(List.of());
            empty.setTotalMatched(totalMatched);
            return empty;
        }

        // 4. 装配：代表规格 sizeText、主图、命中条件
        List<String> rspuIds = rspus.stream().map(RspuMaster::getRspuId).toList();
        Map<String, String> sizeTextMap = representativeSizeTexts(rspuIds);
        Map<String, String> primaryImageMap = batchPrimaryImageUrls(rspuIds);
        List<String> matchedConditions = describeMatchedConditions(criteria);

        List<ProductSearchItem> items = rspus.stream().map(rspu -> {
            ProductSearchItem item = new ProductSearchItem();
            item.setRspuId(rspu.getRspuId());
            item.setProductName(StringUtils.hasText(rspu.getProductName())
                ? rspu.getProductName() : rspu.getPositioningLabel());
            item.setCategoryPath(rspu.getCategoryPath());
            item.setPrimaryImageUrl(primaryImageMap.get(rspu.getRspuId()));
            item.setColorPrimaryName(rspu.getColorPrimaryName());
            item.setMaterial(rspu.getMaterialTags());
            item.setSizeText(sizeTextMap.get(rspu.getRspuId()));
            item.setRetailPrice(rspu.getRetailPrice());
            item.setMatchedConditions(matchedConditions);
            return item;
        }).toList();

        ProductSearchResult result = new ProductSearchResult();
        result.setItems(items);
        result.setTotalMatched(totalMatched);
        return result;
    }

    /**
     * 别名归一：DictAliasService 命中时返回字典码，未命中时返回原文（参与模糊匹配）。
     *
     * @param dictType 字典类型（style/material/color）
     * @param value    用户输入（字典码或方言叫法），可空
     * @return 归一后的匹配值；输入为空返回 null
     */
    private String resolveDictCode(String dictType, String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        Map<String, String> resolved = dictAliasService.resolveAliases(dictType, List.of(trimmed));
        return resolved.getOrDefault(trimmed, trimmed);
    }

    /**
     * 品类归一：LLM 输出品类词（沙发/座椅）或字典码（SF/FS）均可接受，
     * 经 {@link DictResolverService#resolveCodeByName}（标准名/英文名/别名）转字典码；
     * 未命中保留原文。
     */
    private String normalizeCategoryCode(String categoryText) {
        String resolved = dictResolverService.resolveCodeByName(DICT_TYPE_CATEGORY, categoryText);
        return StringUtils.hasText(resolved) ? resolved : categoryText;
    }

    /**
     * 尺寸硬过滤（内存精筛）：按 FloorPlanMatchingService.resolveDimsMm 同口径解析
     * 代表宽度（dimensions JSON 优先，sizeText 经 SizeSpecParser 兜底，取全部变体最大宽），
     * 仅保留代表宽度落在 [minWidthMm, maxWidthMm] 内的产品；尺寸不可解析的产品在硬过滤下排除。
     *
     * @param rspus    SQL 粗筛结果
     * @param criteria 检索条件（含宽度区间）
     * @return 精筛后的产品列表
     */
    private List<RspuMaster> filterByWidthMm(List<RspuMaster> rspus, ProductSearchCriteria criteria) {
        if (rspus.isEmpty()) {
            return rspus;
        }
        List<String> rspuIds = rspus.stream().map(RspuMaster::getRspuId).toList();
        Map<String, List<RspuVariant>> variantMap = rspuVariantMapper.selectList(
                new QueryWrapper<RspuVariant>().in("rspu_id", rspuIds))
            .stream().collect(Collectors.groupingBy(RspuVariant::getRspuId));
        List<RspuMaster> filtered = new ArrayList<>();
        for (RspuMaster rspu : rspus) {
            Integer widthMm = resolveRepresentativeWidthMm(variantMap.getOrDefault(rspu.getRspuId(), List.of()));
            if (widthMm == null) {
                continue;
            }
            if (criteria.getMaxWidthMm() != null && widthMm > criteria.getMaxWidthMm()) {
                continue;
            }
            if (criteria.getMinWidthMm() != null && widthMm < criteria.getMinWidthMm()) {
                continue;
            }
            filtered.add(rspu);
        }
        return filtered;
    }

    /**
     * 解析 RSPU 代表宽度（mm）：全部变体中最大宽度（主规格假设）。
     * dimensions JSON 优先，缺失时 sizeText 经 {@link SizeSpecParser#parseFirst} 兜底；
     * 全部不可解析返回 null。与 FloorPlanMatchingService.resolveDimsMm 口径一致。
     *
     * @param variants RSPU 的变体列表
     * @return 代表宽度（mm），不可解析返回 null
     */
    private Integer resolveRepresentativeWidthMm(List<RspuVariant> variants) {
        return variants.stream()
            .map(this::resolveVariantWidthMm)
            .filter(w -> w != null && w > 0)
            .max(Comparator.naturalOrder())
            .orElse(null);
    }

    /** 解析单个变体宽度（mm）：dimensions JSON 优先，sizeText 兜底。 */
    private Integer resolveVariantWidthMm(RspuVariant variant) {
        int[] dims = parseDimensionsJson(variant.getDimensions());
        if (dims == null) {
            dims = parseSizeText(variant.getSizeText());
        }
        return dims != null ? dims[0] : null;
    }

    /** 解析 dimensions JSON（{"w":2380,"d":840,"h":910,"unit":"mm"}）为 [w,d] mm。 */
    private int[] parseDimensionsJson(String dimensionsJson) {
        if (!StringUtils.hasText(dimensionsJson)) {
            return null;
        }
        try {
            Dimensions dims = objectMapper.readValue(dimensionsJson, Dimensions.class);
            return toMm(dims.getW(), dims.getD(), dims.getUnit());
        } catch (Exception e) {
            log.warn("解析变体 dimensions 失败，按无尺寸处理，dimensions={}", dimensionsJson, e);
            return null;
        }
    }

    /** 从 sizeText 解析尺寸（SizeSpecParser 首个结构化规格）。 */
    private int[] parseSizeText(String sizeText) {
        if (!StringUtils.hasText(sizeText)) {
            return null;
        }
        SizeSpecParser.SizeSpec spec = SizeSpecParser.parseFirst(sizeText);
        if (spec == null || spec.dimensions() == null) {
            return null;
        }
        Dimensions dims = spec.dimensions();
        return toMm(dims.getW(), dims.getD(), dims.getUnit());
    }

    /** 宽深按单位换算为 mm；无单位按 mm 处理（产品库尺寸惯例），宽度缺失返回 null。 */
    private int[] toMm(Integer w, Integer d, String unit) {
        if (w == null || w <= 0) {
            return null;
        }
        double factor = 1.0;
        if (StringUtils.hasText(unit)) {
            factor = switch (unit.toLowerCase()) {
                case "cm" -> 10.0;
                case "m" -> 1000.0;
                case "inch" -> 25.4;
                default -> 1.0;
            };
        }
        int widthMm = (int) Math.round(w * factor);
        Integer depthMm = d != null && d > 0 ? (int) Math.round(d * factor) : null;
        return new int[] {widthMm, depthMm != null ? depthMm : 0};
    }

    /**
     * 批量取各 RSPU 代表规格的 sizeText（宽度最大的变体原文；无变体时缺失）。
     */
    private Map<String, String> representativeSizeTexts(List<String> rspuIds) {
        Map<String, String> result = new HashMap<>();
        Map<String, List<RspuVariant>> variantMap = rspuVariantMapper.selectList(
                new QueryWrapper<RspuVariant>().in("rspu_id", rspuIds))
            .stream().collect(Collectors.groupingBy(RspuVariant::getRspuId));
        for (Map.Entry<String, List<RspuVariant>> entry : variantMap.entrySet()) {
            entry.getValue().stream()
                .filter(v -> StringUtils.hasText(v.getSizeText()))
                .max(Comparator.comparing(v -> {
                    Integer w = resolveVariantWidthMm(v);
                    return w != null ? w : 0;
                }))
                .ifPresent(v -> result.put(entry.getKey(), v.getSizeText()));
        }
        return result;
    }

    /**
     * 批量查询各 RSPU 的主图 URL（image_assets 主图，/api/v1/images/{imageId}，
     * 与 PricingPreviewService 同口径）。
     */
    private Map<String, String> batchPrimaryImageUrls(List<String> rspuIds) {
        return imageAssetsMapper.selectList(new QueryWrapper<ImageAssets>()
                .in("rspu_id", rspuIds)
                .eq("is_primary", true))
            .stream()
            .collect(Collectors.toMap(
                ImageAssets::getRspuId,
                img -> "/api/v1/images/" + img.getImageId(),
                (a, b) -> a
            ));
    }

    /** 生成命中条件描述（SQL 已下推的条件对全部命中项成立，供 evidence 使用）。 */
    private List<String> describeMatchedConditions(ProductSearchCriteria criteria) {
        List<String> conditions = new ArrayList<>();
        if (StringUtils.hasText(criteria.getCategoryCode())) {
            conditions.add("品类=" + criteria.getCategoryCode().trim());
        }
        if (StringUtils.hasText(criteria.getStyle())) {
            conditions.add("风格=" + criteria.getStyle().trim());
        }
        if (StringUtils.hasText(criteria.getMaterial())) {
            conditions.add("材质=" + criteria.getMaterial().trim());
        }
        if (StringUtils.hasText(criteria.getColor())) {
            conditions.add("颜色=" + criteria.getColor().trim());
        }
        if (criteria.getBudgetMax() != null) {
            conditions.add("预算≤" + criteria.getBudgetMax().stripTrailingZeros().toPlainString());
        }
        if (criteria.getMinWidthMm() != null && criteria.getMaxWidthMm() != null) {
            conditions.add("宽度" + criteria.getMinWidthMm() + "~" + criteria.getMaxWidthMm() + "mm");
        } else if (criteria.getMaxWidthMm() != null) {
            conditions.add("宽度≤" + criteria.getMaxWidthMm() + "mm");
        } else if (criteria.getMinWidthMm() != null) {
            conditions.add("宽度≥" + criteria.getMinWidthMm() + "mm");
        }
        if (StringUtils.hasText(criteria.getKeyword())) {
            conditions.add("关键词=" + criteria.getKeyword().trim());
        }
        return conditions;
    }
}
