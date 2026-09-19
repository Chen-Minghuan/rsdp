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
import com.rsdp.service.EmbeddingService;
import com.rsdp.service.vector.ProductVectorStore;
import com.rsdp.service.vector.VectorHit;
import com.rsdp.util.SizeSpecParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final EmbeddingService embeddingService;
    private final ProductVectorStore productVectorStore;

    /**
     * 按条件检索在售产品。
     *
     * <p>流程：别名归一 → 可见性 + SQL 条件下推 → 尺寸硬过滤（SQL 粗筛 + 内存精筛）
     * → 补主图/尺寸原文/命中条件 → topN 截断。</p>
     *
     * <p>P2 向量召回 + RRF：开启 {@code vectorRecallEnabled} 且检索文本非空时，
     * 额外走向量通道（embedText → pgvector → rspu 聚合 → 可见性过滤），
     * 与结构化通道做 RRF(k=60) 融合；向量通道不可用（无文本/异常/空结果）时
     * 自动退化为纯结构化检索，行为与 P1 一致。</p>
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
        // LLM 抽取的是品类词（如 沙发/座椅），rspu_master.category_code 存的是字典码（SF/FS），
        // 先经 category_dict 名称/别名归一；归一失败保留原文（等值不命中，等价空结果）
        String categoryCode = StringUtils.hasText(criteria.getCategoryCode())
            ? normalizeCategoryCode(criteria.getCategoryCode().trim()) : null;
        boolean hasSizeFilter = criteria.getMaxWidthMm() != null || criteria.getMinWidthMm() != null;

        // 2. 向量通道（P2）：不可用 → 纯结构化（P1 行为）
        List<String> vectorRanked = properties.isVectorRecallEnabled()
            ? vectorChannelRankedIds(criteria, categoryCode, topN) : List.of();
        if (vectorRanked.isEmpty()) {
            return structuredSearch(criteria, styleCode, materialCode, colorCode, categoryCode,
                hasSizeFilter, topN, false);
        }
        return fusedSearch(criteria, styleCode, materialCode, colorCode, categoryCode,
            hasSizeFilter, topN, vectorRanked);
    }

    /**
     * 纯结构化检索（P1 行为）：可见性收口 + SQL 条件下推 → 尺寸硬过滤 → topN 截断。
     */
    private ProductSearchResult structuredSearch(ProductSearchCriteria criteria,
                                                 String styleCode, String materialCode, String colorCode,
                                                 String categoryCode, boolean hasSizeFilter, int topN,
                                                 boolean vectorChannelUsed) {
        QueryWrapper<RspuMaster> wrapper = new QueryWrapper<>();
        visibilityPolicy.applyScope(wrapper);
        applyConditions(wrapper, criteria, styleCode, materialCode, colorCode, categoryCode);

        List<RspuMaster> rspus;
        int totalMatched;
        if (hasSizeFilter) {
            // 尺寸硬过滤：SQL 粗筛（有未删除变体的产品）→ 内存按变体尺寸精筛
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
        List<ProductSearchItem> items = assembleItems(rspus, describeMatchedConditions(criteria));
        applyStructuredRankScores(items);

        ProductSearchResult result = new ProductSearchResult();
        result.setItems(items);
        result.setTotalMatched(totalMatched);
        result.setVectorChannelUsed(vectorChannelUsed);
        return result;
    }

    /**
     * 结构化 + 向量双通道 RRF 融合检索。
     *
     * <p>结构化通道按 P1 口径取候选窗（topN × vectorCandidateMultiplier），向量通道
     * 按相似度排名；RRF 融合后并集重查（可见性 + 预算硬过滤）→ 尺寸硬过滤
     * → 融合分降序截 topN。totalMatched 为融合 + 硬过滤后的候选总数（口径与 P1 不同，
     * 随向量通道启用而变化）。</p>
     */
    private ProductSearchResult fusedSearch(ProductSearchCriteria criteria,
                                            String styleCode, String materialCode, String colorCode,
                                            String categoryCode, boolean hasSizeFilter, int topN,
                                            List<String> vectorRanked) {
        // 1. 结构化通道候选窗（rank = 结果位置）
        QueryWrapper<RspuMaster> wrapper = new QueryWrapper<>();
        visibilityPolicy.applyScope(wrapper);
        applyConditions(wrapper, criteria, styleCode, materialCode, colorCode, categoryCode);
        List<RspuMaster> structuredHits;
        if (hasSizeFilter) {
            wrapper.exists("SELECT 1 FROM rspu_variant v WHERE v.rspu_id = rspu_master.rspu_id"
                + " AND v.deleted_at IS NULL");
            wrapper.orderByDesc("created_at").last("LIMIT " + SIZE_FILTER_PREFETCH_LIMIT);
            structuredHits = filterByWidthMm(rspuMapper.selectList(wrapper), criteria);
        } else {
            int window = topN * Math.max(properties.getVectorCandidateMultiplier(), 1);
            wrapper.orderByDesc("created_at").last("LIMIT " + window);
            structuredHits = rspuMapper.selectList(wrapper);
        }
        List<String> structuredRanked = structuredHits.stream().map(RspuMaster::getRspuId).toList();

        // 2. RRF 融合
        LinkedHashMap<String, Double> fused = RrfRanker.fuse(
            List.of(structuredRanked, vectorRanked), properties.getRrfK());

        // 3. 并集重查：可见性收口 + 预算硬过滤（结构化通道 SQL 已下推，此处对向量命中补齐）
        QueryWrapper<RspuMaster> union = new QueryWrapper<>();
        visibilityPolicy.applyScope(union);
        union.in("rspu_id", fused.keySet());
        if (criteria.getBudgetMax() != null) {
            union.le("retail_price", criteria.getBudgetMax());
        }
        List<RspuMaster> unionHits = rspuMapper.selectList(union);
        if (hasSizeFilter) {
            unionHits = filterByWidthMm(unionHits, criteria);
        }
        Map<String, RspuMaster> byId = unionHits.stream()
            .collect(Collectors.toMap(RspuMaster::getRspuId, r -> r, (a, b) -> a));
        List<RspuMaster> ordered = fused.keySet().stream()
            .map(byId::get).filter(r -> r != null).toList();

        int totalMatched = ordered.size();
        List<RspuMaster> page = ordered.stream().limit(topN).toList();
        List<ProductSearchItem> items = assembleItems(page, describeMatchedConditions(criteria));
        for (ProductSearchItem item : items) {
            Double score = fused.get(item.getRspuId());
            if (score != null) {
                item.setRankScore(BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP));
            }
        }

        ProductSearchResult result = new ProductSearchResult();
        result.setItems(items);
        result.setTotalMatched(totalMatched);
        result.setVectorChannelUsed(true);
        return result;
    }

    /**
     * 向量通道：检索文本 → embedText → pgvector 相似检索 → 按 rspu 聚合取最佳相似度
     * → 可见性过滤 → 相似度降序的 rspuId 排名列表。
     *
     * <p>降级策略（任一不满足即返回空列表，整体退化为纯结构化检索）：
     * 检索文本为空；embedText 异常或返回空向量；向量库异常或无命中。</p>
     */
    private List<String> vectorChannelRankedIds(ProductSearchCriteria criteria, String categoryCode, int topN) {
        String queryText = buildVectorQueryText(criteria);
        if (!StringUtils.hasText(queryText)) {
            return List.of();
        }
        try {
            float[] queryVector = embeddingService.embedText(queryText);
            if (queryVector == null || queryVector.length == 0) {
                return List.of();
            }
            int limit = topN * Math.max(properties.getVectorCandidateMultiplier(), 1);
            List<VectorHit> hits = productVectorStore.search(queryVector, limit, categoryCode, true);
            if (hits == null || hits.isEmpty()) {
                return List.of();
            }
            // 按 rspuId 聚合取最佳（距离最小），分数换算 clamp(1 - distance / 2)，与 RetrievalService 同口径
            Map<String, Double> bestScoreByRspu = new LinkedHashMap<>();
            for (VectorHit hit : hits) {
                if (hit == null || hit.rspuId() == null) {
                    continue;
                }
                double score = Math.max(0.0, Math.min(1.0, 1.0 - hit.distance() / 2.0));
                bestScoreByRspu.merge(hit.rspuId(), score, Math::max);
            }
            if (bestScoreByRspu.isEmpty()) {
                return List.of();
            }
            // 可见性收口（active + 非平台员工强制已确认），与结构化通道同口径
            QueryWrapper<RspuMaster> visible = new QueryWrapper<>();
            visibilityPolicy.applyScope(visible);
            visible.in("rspu_id", bestScoreByRspu.keySet());
            Set<String> visibleIds = rspuMapper.selectList(visible).stream()
                .map(RspuMaster::getRspuId).collect(Collectors.toSet());
            return bestScoreByRspu.entrySet().stream()
                .filter(e -> visibleIds.contains(e.getKey()))
                .sorted((e1, e2) -> {
                    int cmp = Double.compare(e2.getValue(), e1.getValue());
                    return cmp != 0 ? cmp : e1.getKey().compareTo(e2.getKey());
                })
                .map(Map.Entry::getKey)
                .toList();
        } catch (Exception e) {
            log.warn("向量召回通道失败，降级为纯结构化检索，queryText={}", queryText, e);
            return List.of();
        }
    }

    /** 拼接向量通道查询文本（品类词/风格/材质/颜色/关键词；全空返回空串跳过该通道）。 */
    private String buildVectorQueryText(ProductSearchCriteria criteria) {
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(criteria.getCategoryCode())) {
            parts.add(criteria.getCategoryCode().trim());
        }
        if (StringUtils.hasText(criteria.getStyle())) {
            parts.add(criteria.getStyle().trim());
        }
        if (StringUtils.hasText(criteria.getMaterial())) {
            parts.add(criteria.getMaterial().trim());
        }
        if (StringUtils.hasText(criteria.getColor())) {
            parts.add(criteria.getColor().trim());
        }
        if (StringUtils.hasText(criteria.getKeyword())) {
            parts.add(criteria.getKeyword().trim());
        }
        return String.join(" ", parts);
    }

    /** SQL 条件下推（结构化通道与融合通道共用）。 */
    private void applyConditions(QueryWrapper<RspuMaster> wrapper, ProductSearchCriteria criteria,
                                 String styleCode, String materialCode, String colorCode, String categoryCode) {
        if (StringUtils.hasText(categoryCode)) {
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
    }

    /** 纯结构化检索的 rank_score：单通道 RRF 分（1/(k+rank)，与融合分同口径可比）。 */
    private void applyStructuredRankScores(List<ProductSearchItem> items) {
        int rank = 0;
        for (ProductSearchItem item : items) {
            rank++;
            item.setRankScore(BigDecimal.valueOf(1.0 / (properties.getRrfK() + rank))
                .setScale(4, RoundingMode.HALF_UP));
        }
    }

    /**
     * 按 ID 批量取产品卡片项（过可见性过滤 + 主图/尺寸装配）。
     *
     * <p>供 Skill 读工具把 rspu_relation 置顶关系产品装配成可直接进推荐卡片的结构；
     * 不可见/不存在的产品静默剔除（关系数据稀疏，不应阻断推荐）。</p>
     *
     * @param rspuIds 产品 ID 集合
     * @return 卡片项列表（顺序与入参一致，仅保留可见产品）
     */
    public List<ProductSearchItem> findItemsByIds(java.util.Collection<String> rspuIds) {
        if (rspuIds == null || rspuIds.isEmpty()) {
            return List.of();
        }
        List<String> ids = rspuIds.stream().filter(StringUtils::hasText).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        QueryWrapper<RspuMaster> wrapper = new QueryWrapper<>();
        visibilityPolicy.applyScope(wrapper);
        wrapper.in("rspu_id", ids);
        List<RspuMaster> rspus = rspuMapper.selectList(wrapper);
        Map<String, RspuMaster> byId = rspus.stream()
            .collect(Collectors.toMap(RspuMaster::getRspuId, r -> r, (a, b) -> a));
        List<RspuMaster> ordered = ids.stream().map(byId::get).filter(r -> r != null).toList();
        return assembleItems(ordered, List.of());
    }

    /** 装配：代表规格 sizeText、主图、命中条件。 */
    private List<ProductSearchItem> assembleItems(List<RspuMaster> rspus, List<String> matchedConditions) {
        if (rspus == null || rspus.isEmpty()) {
            return List.of();
        }
        List<String> rspuIds = rspus.stream().map(RspuMaster::getRspuId).toList();
        Map<String, String> sizeTextMap = representativeSizeTexts(rspuIds);
        Map<String, String> primaryImageMap = batchPrimaryImageUrls(rspuIds);

        return rspus.stream().map(rspu -> {
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
        if (rspuIds == null || rspuIds.isEmpty()) {
            return result;
        }
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
        if (rspuIds == null || rspuIds.isEmpty()) {
            return Map.of();
        }
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
