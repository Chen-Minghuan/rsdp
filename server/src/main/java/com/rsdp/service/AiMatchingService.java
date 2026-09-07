package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiSchemeRecommendation;
import com.rsdp.dto.request.AnchorMatchingRequest;
import com.rsdp.dto.request.RoomSchemeRequest;
import com.rsdp.dto.response.AnchorMatchingResponse;
import com.rsdp.dto.response.SchemeItemResponse;
import com.rsdp.dto.response.RoomSchemeResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.security.datascope.DataScopeHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 搭配方案服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiMatchingService {

    private final RspuMapper rspuMapper;
    private final RskuSupplyMapper rskuSupplyMapper;
    private final FactoryMasterMapper factoryMasterMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final DictService dictService;
    private final VisionService visionService;
    private final ObjectMapper objectMapper;
    private final DataScopeHelper dataScopeHelper;
    private final PricingService pricingService;

    private static final int MAX_CANDIDATES = 30;

    private static final String SYSTEM_PROMPT = """
        你是家具搭配专家。请根据用户给出的空间类型、预算和风格偏好，从候选产品列表中挑选一组产品，组成一个协调的空间方案。
        只输出 JSON，不要任何其他文字说明。
        输出格式：
        {
          "rspuIds": ["RSPU-001", "RSPU-002"],
          "reasoning": "推荐理由，简洁说明为什么这些产品搭配在一起"
        }
        必须保证推荐方案的总价不超过预算上限。
        如果候选产品不足或没有合适组合，rspuIds 可为空数组，reasoning 说明原因。
        """;

    private static final String ANCHOR_SYSTEM_PROMPT = """
        你是家具搭配专家。用户已经选定了一款产品（锚点产品），希望你在目标品类中推荐 1~3 个最搭配的产品。
        只输出 JSON，不要任何其他文字说明。
        输出格式：
        {
          "rspuIds": ["RSPU-001"],
          "reasoning": "推荐理由，简洁说明为什么这些产品与锚点产品搭配"
        }
        请从风格、颜色、材质、使用场景等维度判断搭配协调性。
        如果候选产品不足或没有合适搭配，rspuIds 可为空数组，reasoning 说明原因。
        """;

    /**
     * 根据空间类型和预算生成 AI 搭配方案。
     *
     * @param request 请求
     * @return 搭配方案
     */
    public RoomSchemeResponse generateRoomScheme(RoomSchemeRequest request) {
        String roomTypeName = getDictName("room_type", request.getRoomType());
        List<RspuMaster> candidates = fetchCandidates(request.getStylePreference());

        String prompt = buildPrompt(
            roomTypeName,
            request.getBudgetLimit(),
            getDictName("style", request.getStylePreference()),
            candidates
        );

        String aiJson = visionService.chatText(SYSTEM_PROMPT, prompt);
        AiSchemeRecommendation recommendation = parseRecommendation(aiJson);

        return buildResponse(
            request.getRoomType(),
            request.getBudgetLimit(),
            recommendation,
            candidates
        );
    }

    /**
     * 生成 AI 搭配方案（候选由调用方经尺寸硬规则预筛，户型图链路 v3.0 §5.3）。
     *
     * <p>与 {@link #generateRoomScheme(RoomSchemeRequest)} 的差异：</p>
     * <ul>
     *   <li>候选列表由调用方提供（已过 RoomDimensionRules R1~R5），不再自行取数；</li>
     *   <li>request 携带 widthMm/depthMm 时，prompt 注入空间尺寸上下文，
     *       告知 LLM 候选均已通过尺寸校验、只需判断搭配协调性；</li>
     *   <li>LLM 返回空（或解析失败）时按规则兜底：候选已按风格匹配分 + 创建时间排序，
     *       每品类取最前者组合（SF×1 / TB×1 / FC×1 / FS×2），reasoning 注明规则推荐，
     *       保证永远有结果。</li>
     * </ul>
     *
     * @param request    请求（含可选空间尺寸）
     * @param candidates 尺寸合规的候选产品（按风格分降序 + 创建时间降序）
     * @return 搭配方案
     */
    public RoomSchemeResponse generateRoomScheme(RoomSchemeRequest request, List<RspuMaster> candidates) {
        String roomTypeName = getDictName("room_type", request.getRoomType());

        String prompt = buildPrompt(
            roomTypeName,
            request.getRoomType(),
            request.getBudgetLimit(),
            getDictName("style", request.getStylePreference()),
            candidates,
            request.getWidthMm(),
            request.getDepthMm()
        );

        String aiJson = visionService.chatText(SYSTEM_PROMPT, prompt);
        AiSchemeRecommendation recommendation = parseRecommendation(aiJson);
        if (recommendation.getRspuIds() == null || recommendation.getRspuIds().isEmpty()) {
            recommendation = ruleFallback(request.getRoomType(), candidates);
        }

        return buildResponse(
            request.getRoomType(),
            request.getBudgetLimit(),
            recommendation,
            candidates
        );
    }

    /**
     * 规则兜底方案（多空间链路 v3.0 §8 P2：LLM 调用抛异常时由编排层逐空间降级调用）。
     *
     * <p>不调用 LLM，直接按空间模板从候选中取每品类最前者组合（候选已由调用方
     * 按风格匹配分 + 创建时间排序），语义同 {@link #generateRoomScheme(RoomSchemeRequest, List)}
     * 内部的空返回兜底。</p>
     *
     * @param request    请求（含可选空间尺寸）
     * @param candidates 尺寸合规的候选产品（已排序）
     * @return 规则兜底搭配方案
     */
    public RoomSchemeResponse ruleFallbackScheme(RoomSchemeRequest request, List<RspuMaster> candidates) {
        return buildResponse(
            request.getRoomType(),
            request.getBudgetLimit(),
            ruleFallback(request.getRoomType(), candidates),
            candidates
        );
    }

    /**
     * 以某个产品为锚点，推荐目标品类下的搭配产品。
     *
     * @param request 请求
     * @return 推荐结果
     */
    public AnchorMatchingResponse recommendByAnchor(AnchorMatchingRequest request) {
        RspuMaster anchor = rspuMapper.selectById(request.getExistingRspuId());
        if (anchor == null) {
            throw new ResourceNotFoundException("产品不存在: " + request.getExistingRspuId());
        }

        List<RspuMaster> candidates = fetchCandidatesByCategory(
            request.getTargetCategoryCode(),
            request.getExistingRspuId()
        );

        if (candidates.isEmpty()) {
            AnchorMatchingResponse empty = new AnchorMatchingResponse();
            empty.setExistingRspuId(request.getExistingRspuId());
            empty.setTargetCategoryCode(request.getTargetCategoryCode());
            empty.setReasoning("目标品类下暂无可用产品");
            empty.setItems(List.of());
            return empty;
        }

        String prompt = buildAnchorPrompt(anchor, candidates);
        String aiJson = visionService.chatText(ANCHOR_SYSTEM_PROMPT, prompt);
        AiSchemeRecommendation recommendation = parseRecommendation(aiJson);

        return buildAnchorResponse(
            request.getExistingRspuId(),
            request.getTargetCategoryCode(),
            recommendation,
            candidates
        );
    }

    private List<RspuMaster> fetchCandidates(String stylePreference) {
        // 风格条件下推为 EXISTS 子查询，避免先全量加载 rspu_style 再 IN 过滤的两步查询
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RspuMaster> wrapper =
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RspuMaster>()
                .eq("status", "active")
                .orderByDesc("created_at")
                .last("LIMIT " + MAX_CANDIDATES);
        if (stylePreference != null && !stylePreference.isBlank()) {
            wrapper.exists(
                "SELECT 1 FROM rspu_style s WHERE s.rspu_id = rspu_master.rspu_id AND s.style_code = {0}",
                stylePreference
            );
        }
        return rspuMapper.selectList(wrapper);
    }

    private List<RspuMaster> fetchCandidatesByCategory(String categoryCode, String excludeRspuId) {
        return rspuMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RspuMaster>()
                .eq("status", "active")
                .eq("category_code", categoryCode)
                .ne("rspu_id", excludeRspuId)
                .orderByDesc("created_at")
                .last("LIMIT " + MAX_CANDIDATES)
        );
    }

    private String buildPrompt(String roomTypeName, BigDecimal budgetLimit, String styleName, List<RspuMaster> candidates) {
        return buildPrompt(roomTypeName, budgetLimit, styleName, candidates, null, null);
    }

    /**
     * 组装搭配终审 prompt；widthMm/depthMm 均非空时注入空间尺寸上下文（v3.0 §5.3），
     * 并在候选行附品类码，便于 LLM 按「1 沙发 + 0~1 茶几 + 0~1 电视柜 + 0~2 休闲椅」选品。
     */
    private String buildPrompt(String roomTypeName, BigDecimal budgetLimit, String styleName,
                               List<RspuMaster> candidates, Integer widthMm, Integer depthMm) {
        return buildPrompt(roomTypeName, null, budgetLimit, styleName, candidates, widthMm, depthMm);
    }

    /**
     * 组装搭配终审 prompt；widthMm/depthMm 均非空时注入空间尺寸上下文（v3.0 §5.3），
     * 并在候选行附品类码；选品组合提示按 roomType 空间模板给出（P2 多空间：
     * 客厅「1 沙发 + 0~1 茶几 + 0~1 电视柜 + 0~2 休闲椅」、餐厅「1 餐桌 + 0~4 餐椅」、
     * 卧室「1 床 + 0~2 柜类」）。
     */
    private String buildPrompt(String roomTypeName, String roomType, BigDecimal budgetLimit,
                               String styleName, List<RspuMaster> candidates,
                               Integer widthMm, Integer depthMm) {
        Map<String, BigDecimal> minSalePriceMap = batchMinSalePrices(candidates);

        boolean withDimension = widthMm != null && depthMm != null;
        StringBuilder sb = new StringBuilder();
        sb.append("请为以下空间生成家具搭配方案。\n");
        sb.append("空间类型：").append(roomTypeName).append("\n");
        if (withDimension) {
            BigDecimal areaM2 = BigDecimal.valueOf((long) widthMm * depthMm)
                .divide(BigDecimal.valueOf(1_000_000L), 1, java.math.RoundingMode.HALF_UP);
            sb.append("空间尺寸：").append(widthMm).append("mm × ").append(depthMm)
                .append("mm（约 ").append(areaM2).append("㎡）\n");
            sb.append("请从候选产品列表中挑选一套").append(compositionHint(roomType)).append("。\n");
            sb.append("候选产品均已通过尺寸校验，你只需判断风格、颜色、材质的搭配协调性。\n");
        }
        // 预算口径：客户应付的销售价总额（批次 2，出厂价不再进 prompt）
        sb.append("预算上限：").append(budgetLimit)
            .append(" 元（指客户应付的销售价总额，请确保所选产品参考售价之和不超过预算）\n");
        if (styleName != null && !styleName.isBlank()) {
            sb.append("风格偏好：").append(styleName).append("\n");
        }
        sb.append("\n候选产品（请从中挑选）：\n");

        for (RspuMaster rspu : candidates) {
            BigDecimal salePrice = minSalePriceMap.get(rspu.getRspuId());
            sb.append("- ").append(rspu.getRspuId());
            if (withDimension) {
                sb.append(" | 品类：").append(rspu.getCategoryCode());
            }
            sb.append(" | 风格：").append(rspu.getPositioningLabel())
                .append(" | 主色：").append(rspu.getColorPrimaryName())
                .append(" | 材质：").append(rspu.getMaterialTags())
                .append(" | 适用场景：").append(rspu.getSceneTags());
            // 无售价候选标注「价格待定」，不阻止入选（硬性拦截在报价单/订单环节）
            if (salePrice != null) {
                sb.append(" | 参考售价：").append(salePrice).append(" 元\n");
            } else {
                sb.append(" | 参考售价：价格待定\n");
            }
        }

        sb.append("\n请输出 JSON 格式的推荐结果。");
        return sb.toString();
    }

    /**
     * 规则兜底（v3.0 §5.3 降级链）：LLM 返回空时，按空间品类模板从候选中取每品类最前者
     * （候选已由调用方按风格匹配分 + 创建时间排序），保证永远有结果。
     *
     * @param roomType   空间类型（决定品类模板 maxPerCategory，P2 多空间）
     * @param candidates 尺寸合规候选（已排序）
     * @return 兜底推荐结果，reasoning 注明规则推荐
     */
    private AiSchemeRecommendation ruleFallback(String roomType, List<RspuMaster> candidates) {
        Map<String, Integer> maxPerCategory = maxPerCategoryFor(roomType);
        Map<String, Integer> picked = new java.util.HashMap<>();
        List<String> selectedIds = new ArrayList<>();
        for (RspuMaster candidate : candidates) {
            String category = candidate.getCategoryCode();
            Integer max = maxPerCategory.get(category);
            if (max == null) {
                continue;
            }
            int count = picked.getOrDefault(category, 0);
            if (count >= max) {
                continue;
            }
            picked.put(category, count + 1);
            selectedIds.add(candidate.getRspuId());
        }

        AiSchemeRecommendation fallback = new AiSchemeRecommendation();
        fallback.setRspuIds(selectedIds);
        fallback.setReasoning(selectedIds.isEmpty()
            ? "规则推荐：当前没有满足尺寸规则的候选产品，无法生成方案"
            : "规则推荐：AI 未给出有效结果，已按尺寸合规与风格匹配分自动组合");
        return fallback;
    }

    /**
     * 空间模板选品组合提示（prompt 文案，P2 多空间）；默认客厅文案与历史版本完全一致。
     *
     * @param roomType 空间类型（LIVING/LIVING_ROOM/DINING_ROOM/BEDROOM 等）
     * @return 组合提示文案
     */
    private static String compositionHint(String roomType) {
        return switch (roomType == null ? "" : roomType.trim().toUpperCase()) {
            case "DINING", "DINING_ROOM" -> "餐厅搭配：1 款餐桌 + 0~4 款餐椅";
            case "BEDROOM" -> "卧室搭配：1 款床 + 0~2 款柜类";
            default -> "客厅搭配：1 款沙发 + 0~1 款茶几 + 0~1 款电视柜 + 0~2 款休闲椅";
        };
    }

    /**
     * 空间模板每品类最大选品数（规则兜底用，P2 多空间；与
     * {@code RoomDimensionRules} 各空间模板 maxPerCategory 保持一致）。
     *
     * @param roomType 空间类型
     * @return 品类码 → 最大选品数
     */
    private static Map<String, Integer> maxPerCategoryFor(String roomType) {
        return switch (roomType == null ? "" : roomType.trim().toUpperCase()) {
            case "DINING", "DINING_ROOM" -> Map.of("DT", 1, "FS", 4);
            case "BEDROOM" -> Map.of("BD", 1, "FC", 2);
            default -> Map.of("SF", 1, "TB", 1, "FC", 1, "FS", 2);
        };
    }

    /**
     * 批量解析候选 RSPU 的最低参考售价（销售价口径，批次 2）。
     *
     * <p>口径：复用 {@code selectCapableByRspuIds} + {@code canAccessFactory} 数据范围过滤
     * 拿到候选 RSKU，每个 RSPU 取其所有候选 RSKU 经
     * {@link PricingService#resolveSalePrice(RspuMaster, RskuSupply)} 解析出的最低标准售价
     * （即各候选 RSKU 售价中的最小值，而非最低成本者的售价）；三级解析链
     * （建议销售价 → 倍率计价 → 全局倍率兜底）都解析不出（null）时该 RSPU 不进 Map，
     * prompt 侧标注「价格待定」不阻止入选。出厂价仅作为倍率计价的内部输入，
     * 不作为数值透出给 prompt/响应。</p>
     *
     * @param candidates 候选产品（resolveSalePrice 需要 RSPU 的建议销售价与品类）
     * @return RSPU ID → 最低参考售价
     */
    private Map<String, BigDecimal> batchMinSalePrices(List<RspuMaster> candidates) {
        if (candidates.isEmpty()) {
            return Map.of();
        }
        Map<String, RspuMaster> rspuMap = candidates.stream()
            .collect(Collectors.toMap(RspuMaster::getRspuId, r -> r, (a, b) -> a));
        List<RskuSupply> capableRskus = rskuSupplyMapper.selectCapableByRspuIds(
            candidates.stream().map(RspuMaster::getRspuId).toList());
        Map<String, BigDecimal> result = new java.util.HashMap<>();
        for (RskuSupply rsku : capableRskus) {
            if (!dataScopeHelper.canAccessFactory(rsku.getFactoryCode())) {
                continue;
            }
            BigDecimal salePrice = pricingService.resolveSalePrice(rspuMap.get(rsku.getRspuId()), rsku);
            if (salePrice == null) {
                continue;
            }
            result.merge(rsku.getRspuId(), salePrice, (a, b) -> a.compareTo(b) <= 0 ? a : b);
        }
        return result;
    }

    private AiSchemeRecommendation parseRecommendation(String aiJson) {
        try {
            return objectMapper.readValue(aiJson, AiSchemeRecommendation.class);
        } catch (Exception e) {
            log.error("解析 AI 搭配推荐失败: {}", aiJson, e);
            AiSchemeRecommendation fallback = new AiSchemeRecommendation();
            fallback.setRspuIds(List.of());
            fallback.setReasoning("AI 返回格式异常，无法生成方案");
            return fallback;
        }
    }

    private RoomSchemeResponse buildResponse(String roomType, BigDecimal budgetLimit,
                                             AiSchemeRecommendation recommendation,
                                             List<RspuMaster> candidates) {
        Set<String> candidateIds = candidates.stream().map(RspuMaster::getRspuId).collect(Collectors.toSet());
        List<String> selectedIds = recommendation.getRspuIds().stream()
            .filter(candidateIds::contains)
            .distinct()
            .collect(Collectors.toList());

        Map<String, RspuMaster> rspuMap = candidates.stream()
            .collect(Collectors.toMap(RspuMaster::getRspuId, r -> r));
        Map<String, RskuSupply> cheapestRskuMap = batchCheapestRskus(selectedIds);
        Map<String, String> imageUrlMap = batchPrimaryImageUrls(selectedIds);
        Map<String, FactoryMaster> factoryMap = batchFactoryMap(cheapestRskuMap.values().stream()
            .map(RskuSupply::getFactoryCode).distinct().toList());
        // 销售价口径（批次 2）：与 prompt 同一个 batchMinSalePrices，全角色可见
        Map<String, BigDecimal> minSalePriceMap = batchMinSalePrices(
            selectedIds.stream().map(rspuMap::get).filter(java.util.Objects::nonNull).toList());

        List<SchemeItemResponse> items = new ArrayList<>();
        BigDecimal totalPrice = BigDecimal.ZERO;
        BigDecimal totalSalePrice = BigDecimal.ZERO;
        boolean hasUnpricedItems = false;
        // 出厂价按角色掩码：仅平台运营人员与本厂管理员可见；任一厂不可见则总价同步隐藏
        boolean canViewAllPrices = true;

        for (String rspuId : selectedIds) {
            RskuSupply cheapest = cheapestRskuMap.get(rspuId);
            if (cheapest == null) continue;

            RspuMaster rspu = rspuMap.get(rspuId);
            if (rspu == null) continue;

            FactoryMaster factory = factoryMap.get(cheapest.getFactoryCode());

            boolean canViewPrice = dataScopeHelper.canViewFactoryPrice(cheapest.getFactoryCode());
            canViewAllPrices = canViewAllPrices && canViewPrice;

            SchemeItemResponse item = new SchemeItemResponse();
            item.setRspuId(rspuId);
            item.setRspuName(rspu.getPositioningLabel());
            item.setPrimaryImageUrl(imageUrlMap.get(rspuId));
            item.setRskuId(cheapest.getRskuId());
            item.setFactoryCode(cheapest.getFactoryCode());
            item.setFactoryName(factory != null ? factory.getFactoryName() : null);
            item.setFactorySku(cheapest.getFactorySku());
            item.setFactoryPrice(canViewPrice ? cheapest.getFactoryPrice() : null);
            item.setQuantity(1);
            item.setSubtotal(canViewPrice ? cheapest.getFactoryPrice() : null);
            // 参考售价（销售价口径，全角色可见；未定价为 null 且不计入 totalSalePrice）
            BigDecimal salePrice = minSalePriceMap.get(rspuId);
            item.setSalePrice(salePrice);
            item.setLeadTimeDays(cheapest.getLeadTimeDays());
            item.setMoq(cheapest.getMoq());
            items.add(item);

            totalPrice = totalPrice.add(cheapest.getFactoryPrice());
            if (salePrice != null) {
                totalSalePrice = totalSalePrice.add(salePrice);
            } else {
                hasUnpricedItems = true;
            }
        }

        RoomSchemeResponse response = new RoomSchemeResponse();
        response.setRoomType(roomType);
        response.setBudgetLimit(budgetLimit);
        response.setTotalPrice(canViewAllPrices ? totalPrice : null);
        response.setTotalSalePrice(totalSalePrice);
        response.setHasUnpricedItems(hasUnpricedItems);
        response.setItemCount(items.size());
        response.setReasoning(recommendation.getReasoning());
        response.setItems(items);
        return response;
    }

    private String buildAnchorPrompt(RspuMaster anchor, List<RspuMaster> candidates) {
        List<RspuMaster> all = new ArrayList<>(candidates.size() + 1);
        all.add(anchor);
        all.addAll(candidates);
        Map<String, BigDecimal> minSalePriceMap = batchMinSalePrices(all);

        StringBuilder sb = new StringBuilder();
        sb.append("锚点产品信息：\n");
        sb.append("- ").append(anchor.getRspuId())
            .append(" | 风格：").append(anchor.getPositioningLabel())
            .append(" | 主色：").append(anchor.getColorPrimaryName())
            .append(" | 材质：").append(anchor.getMaterialTags())
            .append(" | 场景：").append(anchor.getSceneTags());
        appendSalePrice(sb, minSalePriceMap.get(anchor.getRspuId()));
        sb.append("\n");

        sb.append("目标品类候选产品（请从中挑选 1~3 个最搭配的）：\n");
        for (RspuMaster rspu : candidates) {
            sb.append("- ").append(rspu.getRspuId())
                .append(" | 风格：").append(rspu.getPositioningLabel())
                .append(" | 主色：").append(rspu.getColorPrimaryName())
                .append(" | 材质：").append(rspu.getMaterialTags())
                .append(" | 场景：").append(rspu.getSceneTags());
            appendSalePrice(sb, minSalePriceMap.get(rspu.getRspuId()));
        }

        sb.append("\n请输出 JSON 格式的推荐结果。");
        return sb.toString();
    }

    /** 候选行追加参考售价（销售价口径）；无售价标注「价格待定」，不阻止入选。 */
    private static void appendSalePrice(StringBuilder sb, BigDecimal salePrice) {
        if (salePrice != null) {
            sb.append(" | 参考售价：").append(salePrice).append(" 元\n");
        } else {
            sb.append(" | 参考售价：价格待定\n");
        }
    }

    private AnchorMatchingResponse buildAnchorResponse(String existingRspuId, String targetCategoryCode,
                                                       AiSchemeRecommendation recommendation,
                                                       List<RspuMaster> candidates) {
        Set<String> candidateIds = candidates.stream().map(RspuMaster::getRspuId).collect(Collectors.toSet());
        List<String> selectedIds = recommendation.getRspuIds().stream()
            .filter(candidateIds::contains)
            .distinct()
            .limit(3)
            .collect(Collectors.toList());

        Map<String, RspuMaster> rspuMap = candidates.stream()
            .collect(Collectors.toMap(RspuMaster::getRspuId, r -> r));
        Map<String, RskuSupply> cheapestRskuMap = batchCheapestRskus(selectedIds);
        Map<String, String> imageUrlMap = batchPrimaryImageUrls(selectedIds);
        Map<String, FactoryMaster> factoryMap = batchFactoryMap(cheapestRskuMap.values().stream()
            .map(RskuSupply::getFactoryCode).distinct().toList());
        // 销售价口径（批次 2）：与锚点 prompt 同一个 batchMinSalePrices，全角色可见
        Map<String, BigDecimal> minSalePriceMap = batchMinSalePrices(
            selectedIds.stream().map(rspuMap::get).filter(java.util.Objects::nonNull).toList());

        List<SchemeItemResponse> items = new ArrayList<>();
        for (String rspuId : selectedIds) {
            RskuSupply cheapest = cheapestRskuMap.get(rspuId);
            if (cheapest == null) continue;

            RspuMaster rspu = rspuMap.get(rspuId);
            if (rspu == null) continue;

            FactoryMaster factory = factoryMap.get(cheapest.getFactoryCode());

            SchemeItemResponse item = new SchemeItemResponse();
            item.setRspuId(rspuId);
            item.setRspuName(rspu.getPositioningLabel());
            item.setPrimaryImageUrl(imageUrlMap.get(rspuId));
            item.setRskuId(cheapest.getRskuId());
            item.setFactoryCode(cheapest.getFactoryCode());
            item.setFactoryName(factory != null ? factory.getFactoryName() : null);
            item.setFactorySku(cheapest.getFactorySku());
            // 出厂价按角色掩码：仅平台运营人员与本厂管理员可见
            boolean canViewPrice = dataScopeHelper.canViewFactoryPrice(cheapest.getFactoryCode());
            item.setFactoryPrice(canViewPrice ? cheapest.getFactoryPrice() : null);
            item.setQuantity(1);
            item.setSubtotal(canViewPrice ? cheapest.getFactoryPrice() : null);
            // 参考售价（销售价口径，全角色可见；未定价为 null）
            item.setSalePrice(minSalePriceMap.get(rspuId));
            item.setLeadTimeDays(cheapest.getLeadTimeDays());
            item.setMoq(cheapest.getMoq());
            items.add(item);
        }

        AnchorMatchingResponse response = new AnchorMatchingResponse();
        response.setExistingRspuId(existingRspuId);
        response.setTargetCategoryCode(targetCategoryCode);
        response.setReasoning(recommendation.getReasoning());
        response.setItems(items);
        return response;
    }

    private Map<String, RskuSupply> batchCheapestRskus(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        List<RskuSupply> capableRskus = rskuSupplyMapper.selectCapableByRspuIds(rspuIds);
        return capableRskus.stream()
            .filter(r -> r.getFactoryPrice() != null)
            .filter(r -> dataScopeHelper.canAccessFactory(r.getFactoryCode()))
            .collect(Collectors.groupingBy(
                RskuSupply::getRspuId,
                Collectors.minBy(Comparator.comparing(RskuSupply::getFactoryPrice))
            ))
            .entrySet().stream()
            .filter(e -> e.getValue().isPresent())
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }

    private Map<String, String> batchPrimaryImageUrls(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        List<ImageAssets> images = imageAssetsMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ImageAssets>()
                .in("rspu_id", rspuIds)
                .eq("is_primary", true)
        );
        return images.stream()
            .collect(Collectors.toMap(
                ImageAssets::getRspuId,
                img -> "/api/v1/images/" + img.getImageId(),
                (a, b) -> a
            ));
    }

    private Map<String, FactoryMaster> batchFactoryMap(List<String> factoryCodes) {
        if (factoryCodes.isEmpty()) {
            return Map.of();
        }
        return factoryMasterMapper.selectBatchIds(factoryCodes).stream()
            .collect(Collectors.toMap(FactoryMaster::getFactoryCode, f -> f));
    }

    private String getDictName(String dictType, String dictCode) {
        if (dictCode == null || dictCode.isBlank()) {
            return null;
        }
        return dictService.listByType(dictType).stream()
            .filter(d -> dictCode.equals(d.getDictCode()))
            .findFirst()
            .map(CategoryDict::getDictName)
            .orElse(dictCode);
    }
}
