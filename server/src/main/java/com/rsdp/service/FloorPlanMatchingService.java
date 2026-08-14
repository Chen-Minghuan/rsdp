package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.config.properties.FloorPlanRulesProperties;
import com.rsdp.dto.Dimensions;
import com.rsdp.dto.request.FloorPlanSchemeRequest;
import com.rsdp.dto.request.RoomSchemeRequest;
import com.rsdp.dto.request.SchemeCreateRequest;
import com.rsdp.dto.request.SchemeItemRequest;
import com.rsdp.dto.response.RoomSchemeResponse;
import com.rsdp.dto.response.SchemeItemResponse;
import com.rsdp.dto.response.SchemeResponse;
import com.rsdp.entity.FloorPlanAnalysis;
import com.rsdp.entity.FloorPlanRoom;
import com.rsdp.entity.ProductStyleMatch;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuVariant;
import com.rsdp.entity.RskuSupply;
import com.rsdp.entity.Scheme;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FloorPlanAnalysisMapper;
import com.rsdp.mapper.FloorPlanRoomMapper;
import com.rsdp.mapper.ProductStyleMatchMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuVariantMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.SchemeMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.security.datascope.DataScopeHelper;
import com.rsdp.util.RoomDimensionRules;
import com.rsdp.util.RoomDimensionRules.CandidateProduct;
import com.rsdp.util.SizeSpecParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 户型图空间搭配编排服务（户型图链路 v3.0 §4.1/§5.3，双端唯一出口）。
 *
 * <p>编排：尺寸硬规则筛选（{@link RoomDimensionRules} R1~R5）→ LLM 终审
 * （{@link AiMatchingService#generateRoomScheme(RoomSchemeRequest, List)}，prompt 注入
 * 空间尺寸上下文）→ LLM 空返回规则兜底（每品类取风格分最高者，永远有结果）。</p>
 *
 * <p>两个入口：</p>
 * <ul>
 *   <li>{@link #matchRoomScheme}：公共匹配（官网 ai-match 链路），输入尺寸/风格/预算，
 *       输出选品 + reasoning，不落库；无尺寸时退化为原 {@link AiMatchingService} 行为；</li>
 *   <li>{@link #generateSchemeForAnalysis}：管理端接口 4，基于 confirmed 分析批次的空间尺寸
 *       生成搭配并落 scheme + scheme_item，scheme.analysis_id 回填溯源。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FloorPlanMatchingService {

    /** 客厅空间类型（RoomSchemeRequest.roomType，与公开链路现有一致）。 */
    private static final String ROOM_TYPE_LIVING = "LIVING";

    /** 方案名称时间戳格式（自动命名：客厅方案-yyyyMMdd-HHmmss）。 */
    private static final DateTimeFormatter SCHEME_NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** L 型/转角类沙发关键词（R1 面积分档的小客厅排除项）。 */
    private static final List<String> L_TYPE_KEYWORDS = List.of("L型", "L 型", "L形", "转角", "贵妃");

    /** 候选预取上限（规则引擎前的宽口径取数，规则筛选后再截断到每品类 ≤6 / 总量 ≤30）。 */
    private static final int CANDIDATE_PREFETCH_LIMIT = 200;

    private final FloorPlanAnalysisMapper analysisMapper;
    private final FloorPlanRoomMapper roomMapper;
    private final RspuMapper rspuMapper;
    private final RspuVariantMapper rspuVariantMapper;
    private final RskuSupplyMapper rskuSupplyMapper;
    private final ProductStyleMatchMapper productStyleMatchMapper;
    private final SchemeMapper schemeMapper;
    private final AiMatchingService aiMatchingService;
    private final SchemeService schemeService;
    private final DataScopeHelper dataScopeHelper;
    private final FloorPlanRulesProperties rulesProperties;
    private final ObjectMapper objectMapper;

    /**
     * 公共匹配入口（官网 ai-match 链路；不落库）。
     *
     * <p>widthMm/depthMm 均提供时走规则引擎：R1~R5 硬规则筛选 → LLM 终审 → 规则兜底；
     * 任一缺失时退化为 {@link AiMatchingService#generateRoomScheme(RoomSchemeRequest)}
     * 原行为（无尺寸时行为不变，v3.0 §5.3）。</p>
     *
     * @param widthMm         空间开间（mm），可空
     * @param depthMm         空间进深（mm），可空
     * @param stylePreference 风格偏好（字典码），可空
     * @param budgetLimit     预算上限（元），可空
     * @return 搭配方案（选品 + reasoning）
     */
    public RoomSchemeResponse matchRoomScheme(Integer widthMm, Integer depthMm,
                                              String stylePreference, BigDecimal budgetLimit) {
        RoomSchemeRequest request = new RoomSchemeRequest();
        request.setRoomType(ROOM_TYPE_LIVING);
        request.setBudgetLimit(budgetLimit);
        request.setStylePreference(stylePreference);

        if (widthMm == null || depthMm == null) {
            return aiMatchingService.generateRoomScheme(request);
        }
        request.setWidthMm(widthMm);
        request.setDepthMm(depthMm);

        List<CandidateProduct> candidates = assembleCandidates(stylePreference);
        RoomDimensionRules.FilterResult filterResult =
            RoomDimensionRules.filter(widthMm, depthMm, candidates, rulesProperties);
        List<CandidateProduct> filtered = filterResult.flatCandidates();
        if (!filterResult.degradations().isEmpty()) {
            log.info("客厅尺寸规则降级，room={}x{}，degradations={}", widthMm, depthMm, filterResult.degradations());
        }

        List<String> orderedIds = filtered.stream().map(CandidateProduct::rspuId).toList();
        Map<String, RspuMaster> rspuMap = orderedIds.isEmpty()
            ? Map.of()
            : rspuMapper.selectBatchIds(orderedIds).stream()
                .collect(Collectors.toMap(RspuMaster::getRspuId, r -> r));
        List<RspuMaster> orderedCandidates = orderedIds.stream()
            .map(rspuMap::get)
            .filter(java.util.Objects::nonNull)
            .toList();

        RoomSchemeResponse response = aiMatchingService.generateRoomScheme(request, orderedCandidates);
        if (!filterResult.degradations().isEmpty() && StringUtils.hasText(response.getReasoning())) {
            response.setReasoning(response.getReasoning() + "（" + String.join("；", filterResult.degradations()) + "）");
        }
        return response;
    }

    /**
     * 管理端接口 4：基于 confirmed 分析批次的空间生成搭配方案，落 scheme + scheme_item。
     *
     * <p>校验链：analysis 存在 → 归属（平台运营或创建者本人）→ 状态 confirmed →
     * room 属于该 analysis 且有尺寸。方案名自动生成「客厅方案-yyyyMMdd-HHmmss」，
     * 价格快照语义沿用 {@link SchemeService#createScheme}（取 RSKU 当前价落 scheme_item），
     * scheme.analysis_id 回填溯源；方案项均为 LIVING 场景产品，详情页空间分区标签
     * 由 scheme 体系按 RSPU 场景自动得出。</p>
     *
     * @param analysisId 分析批次 ID
     * @param request    搭配生成请求（roomId + 可选风格/预算/项目）
     * @param operator   操作人用户名
     * @return 新建方案 ID
     */
    public String generateSchemeForAnalysis(String analysisId, FloorPlanSchemeRequest request, String operator) {
        FloorPlanAnalysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null) {
            throw new ResourceNotFoundException("户型图分析不存在: " + analysisId);
        }
        assertCanAccess(analysis, operator);
        if (!FloorPlanService.STATUS_CONFIRMED.equals(analysis.getStatus())) {
            throw new BusinessException("分析未确认，请先在管理端人工确认空间尺寸后再生成方案，当前状态: " + analysis.getStatus());
        }

        FloorPlanRoom room = roomMapper.selectById(request.getRoomId());
        if (room == null || !analysisId.equals(room.getAnalysisId())) {
            throw new BusinessException("空间不存在或不属于该分析: " + request.getRoomId());
        }
        if (room.getWidthMm() == null || room.getDepthMm() == null
            || room.getWidthMm() <= 0 || room.getDepthMm() <= 0) {
            throw new BusinessException("目标空间缺少尺寸，请先人工校正开间/进深: " + request.getRoomId());
        }

        RoomSchemeResponse matched = matchRoomScheme(room.getWidthMm(), room.getDepthMm(),
            request.getStylePreference(), request.getBudgetLimit());
        List<SchemeItemResponse> matchedItems = matched.getItems() != null
            ? matched.getItems() : List.of();
        if (matchedItems.isEmpty()) {
            throw new BusinessException("没有满足客厅尺寸规则与报价要求的产品，无法生成方案");
        }

        SchemeCreateRequest createRequest = new SchemeCreateRequest();
        createRequest.setSchemeName("客厅方案-" + SCHEME_NAME_TIME.format(LocalDateTime.now()));
        createRequest.setRoomType(ROOM_TYPE_LIVING);
        createRequest.setProjectId(request.getProjectId());
        createRequest.setBudgetLimit(request.getBudgetLimit());
        List<SchemeItemRequest> items = new ArrayList<>();
        int sortOrder = 0;
        for (SchemeItemResponse matchedItem : matchedItems) {
            SchemeItemRequest item = new SchemeItemRequest();
            item.setRspuId(matchedItem.getRspuId());
            item.setRskuId(matchedItem.getRskuId());
            item.setQuantity(1);
            item.setSortOrder(sortOrder++);
            items.add(item);
        }
        createRequest.setItems(items);

        SchemeResponse created = schemeService.createScheme(createRequest);

        // 回填方案溯源（scheme.analysis_id，V36）
        Scheme scheme = schemeMapper.selectById(created.getSchemeId());
        scheme.setAnalysisId(analysisId);
        schemeMapper.updateById(scheme);

        log.info("户型图搭配方案已落库，analysisId={}，roomId={}，schemeId={}",
            analysisId, request.getRoomId(), created.getSchemeId());
        return created.getSchemeId();
    }

    /**
     * 装配规则引擎候选：宽口径预取（active + 客厅模板品类 + rspu_scene 含 LIVING +
     * 风格偏好下推）→ 逐个补齐尺寸（dimensions / sizeText 解析）、L 型标记、
     * 有效报价标记、风格匹配分。
     */
    private List<CandidateProduct> assembleCandidates(String stylePreference) {
        QueryWrapper<RspuMaster> wrapper = new QueryWrapper<RspuMaster>()
            .eq("status", "active")
            .in("category_code", RoomDimensionRules.TEMPLATE_CATEGORIES)
            .exists("SELECT 1 FROM rspu_scene sc WHERE sc.rspu_id = rspu_master.rspu_id"
                + " AND sc.scene_code = '" + RoomDimensionRules.SCENE_LIVING + "'")
            .orderByDesc("created_at")
            .last("LIMIT " + CANDIDATE_PREFETCH_LIMIT);
        if (StringUtils.hasText(stylePreference)) {
            wrapper.exists(
                "SELECT 1 FROM rspu_style s WHERE s.rspu_id = rspu_master.rspu_id AND s.style_code = {0}",
                stylePreference
            );
        }
        List<RspuMaster> rspus = rspuMapper.selectList(wrapper);
        if (rspus.isEmpty()) {
            return List.of();
        }

        List<String> rspuIds = rspus.stream().map(RspuMaster::getRspuId).toList();
        Map<String, List<RspuVariant>> variantMap = rspuVariantMapper.selectList(
                new QueryWrapper<RspuVariant>().in("rspu_id", rspuIds))
            .stream().collect(Collectors.groupingBy(RspuVariant::getRspuId));
        Set<String> quotableRspuIds = rskuSupplyMapper.selectCapableByRspuIds(rspuIds).stream()
            .filter(r -> r.getFactoryPrice() != null)
            .filter(r -> dataScopeHelper.canAccessFactory(r.getFactoryCode()))
            .map(RskuSupply::getRspuId)
            .collect(Collectors.toSet());
        Map<String, Double> styleScoreMap = batchStyleScores(rspuIds, stylePreference);

        List<CandidateProduct> candidates = new ArrayList<>();
        for (RspuMaster rspu : rspus) {
            List<RspuVariant> variants = variantMap.getOrDefault(rspu.getRspuId(), List.of());
            int[] dims = resolveDimsMm(variants);
            candidates.add(new CandidateProduct(
                rspu.getRspuId(),
                rspu.getCategoryCode(),
                dims != null ? dims[0] : null,
                dims != null && dims[1] > 0 ? dims[1] : null,
                isLType(rspu, variants),
                true, // SQL 已下推 rspu_scene 含 LIVING，规则引擎 R4 作兜底复核
                quotableRspuIds.contains(rspu.getRspuId()),
                styleScoreMap.getOrDefault(rspu.getRspuId(), 0.0),
                rspu.getCreatedAt()
            ));
        }
        return candidates;
    }

    /**
     * 解析 RSPU 代表尺寸（mm）：取所有变体中宽度最大的一组（主规格假设）；
     * dimensions JSON 优先，缺失时 sizeText 经 {@link SizeSpecParser} 解析兜底（R5 语义）。
     *
     * @param variants RSPU 的变体列表
     * @return [widthMm, depthMm]，全部不可解析返回 null
     */
    private int[] resolveDimsMm(List<RspuVariant> variants) {
        List<int[]> parsed = new ArrayList<>();
        for (RspuVariant variant : variants) {
            int[] dims = parseDimensionsJson(variant.getDimensions());
            if (dims == null) {
                dims = parseSizeText(variant.getSizeText());
            }
            if (dims != null) {
                parsed.add(dims);
            }
        }
        return parsed.stream()
            .max(Comparator.comparingInt(d -> d[0]))
            .orElse(null);
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

    /** 从 sizeText 解析尺寸（SizeSpecParser 多规格解析，取首个带结构化尺寸的规格）。 */
    private int[] parseSizeText(String sizeText) {
        if (!StringUtils.hasText(sizeText)) {
            return null;
        }
        for (SizeSpecParser.SizeSpec spec : SizeSpecParser.parse(sizeText)) {
            Dimensions dims = spec.dimensions();
            if (dims != null && dims.getW() != null) {
                int[] mm = toMm(dims.getW(), dims.getD(), dims.getUnit());
                if (mm != null) {
                    return mm;
                }
            }
        }
        return null;
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

    /** L 型/转角/贵妃类沙发识别（R1 小客厅排除项）。 */
    private boolean isLType(RspuMaster rspu, List<RspuVariant> variants) {
        List<String> texts = new ArrayList<>();
        texts.add(rspu.getProductName());
        for (RspuVariant variant : variants) {
            texts.add(variant.getDisplayName());
            texts.add(variant.getSizeText());
        }
        return texts.stream()
            .filter(StringUtils::hasText)
            .anyMatch(text -> L_TYPE_KEYWORDS.stream().anyMatch(text::contains));
    }

    /** 批量取风格匹配分（product_style_match.overall_score）；无风格偏好返回空 Map。 */
    private Map<String, Double> batchStyleScores(List<String> rspuIds, String stylePreference) {
        if (!StringUtils.hasText(stylePreference)) {
            return Map.of();
        }
        return productStyleMatchMapper.selectList(new QueryWrapper<ProductStyleMatch>()
                .in("rspu_id", rspuIds)
                .eq("dict_type", "style")
                .eq("style_code", stylePreference))
            .stream()
            .filter(m -> m.getOverallScore() != null)
            .collect(Collectors.toMap(
                ProductStyleMatch::getRspuId,
                m -> m.getOverallScore().doubleValue(),
                (a, b) -> a));
    }

    /**
     * 归属校验（与 {@link FloorPlanService} 同口径）：平台运营人员（ADMIN/EDITOR）
     * 可访问任意分析，其他用户仅能访问自己创建的。
     */
    private void assertCanAccess(FloorPlanAnalysis analysis, String operator) {
        if (!SecurityOperatorContext.isPlatformStaff()) {
            String creator = analysis.getCreatedBy();
            if (creator == null || !creator.equals(operator)) {
                throw new ResourceNotFoundException("户型图分析不存在: " + analysis.getAnalysisId());
            }
        }
    }
}
