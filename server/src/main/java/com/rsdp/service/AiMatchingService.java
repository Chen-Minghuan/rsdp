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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final FactoryService factoryService;

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
     * 以某个产品为锚点，推荐目标品类下的搭配产品。
     *
     * @param request 请求
     * @return 推荐结果
     */
    public AnchorMatchingResponse recommendByAnchor(AnchorMatchingRequest request) {
        RspuMaster anchor = rspuMapper.selectById(request.getExistingRspuId());
        if (anchor == null || anchor.getDeletedAt() != null) {
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
        List<RspuMaster> all = rspuMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RspuMaster>()
                .eq("status", "active")
                .orderByDesc("created_at")
        );

        String styleName = getDictName("style", stylePreference);
        return all.stream()
            .filter(r -> styleName == null || styleName.isBlank()
                || styleName.equals(r.getPositioningLabel()))
            .limit(MAX_CANDIDATES)
            .collect(Collectors.toList());
    }

    private List<RspuMaster> fetchCandidatesByCategory(String categoryCode, String excludeRspuId) {
        List<RspuMaster> all = rspuMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RspuMaster>()
                .eq("status", "active")
                .eq("category_code", categoryCode)
                .orderByDesc("created_at")
        );

        return all.stream()
            .filter(r -> !r.getRspuId().equals(excludeRspuId))
            .limit(MAX_CANDIDATES)
            .collect(Collectors.toList());
    }

    private String buildPrompt(String roomTypeName, BigDecimal budgetLimit, String styleName, List<RspuMaster> candidates) {
        Map<String, BigDecimal> minPriceMap = batchMinPrices(
            candidates.stream().map(RspuMaster::getRspuId).toList()
        );

        StringBuilder sb = new StringBuilder();
        sb.append("请为以下空间生成家具搭配方案。\n");
        sb.append("空间类型：").append(roomTypeName).append("\n");
        sb.append("预算上限：").append(budgetLimit).append(" 元\n");
        if (styleName != null && !styleName.isBlank()) {
            sb.append("风格偏好：").append(styleName).append("\n");
        }
        sb.append("\n候选产品（请从中挑选）：\n");

        for (RspuMaster rspu : candidates) {
            BigDecimal minPrice = minPriceMap.get(rspu.getRspuId());
            sb.append("- ").append(rspu.getRspuId())
                .append(" | 风格：").append(rspu.getPositioningLabel())
                .append(" | 主色：").append(rspu.getColorPrimaryName())
                .append(" | 材质：").append(rspu.getMaterialTags())
                .append(" | 适用场景：").append(rspu.getSceneTags())
                .append(" | 最低报价：").append(minPrice != null ? minPrice : "无")
                .append(" 元\n");
        }

        sb.append("\n请输出 JSON 格式的推荐结果。");
        return sb.toString();
    }

    private Map<String, BigDecimal> batchMinPrices(List<String> rspuIds) {
        if (rspuIds.isEmpty()) {
            return Map.of();
        }
        List<RskuSupply> rskus = rskuSupplyMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RskuSupply>()
                .in("rspu_id", rspuIds)
        );
        List<RskuSupply> capableRskus = filterCapableRskus(rskus);
        return capableRskus.stream()
            .filter(r -> r.getFactoryPrice() != null)
            .collect(Collectors.groupingBy(
                RskuSupply::getRspuId,
                Collectors.mapping(
                    RskuSupply::getFactoryPrice,
                    Collectors.minBy(Comparator.naturalOrder())
                )
            ))
            .entrySet().stream()
            .filter(e -> e.getValue().isPresent())
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
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

        List<SchemeItemResponse> items = new ArrayList<>();
        BigDecimal totalPrice = BigDecimal.ZERO;

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
            item.setFactoryPrice(cheapest.getFactoryPrice());
            item.setLeadTimeDays(cheapest.getLeadTimeDays());
            item.setMoq(cheapest.getMoq());
            items.add(item);

            totalPrice = totalPrice.add(cheapest.getFactoryPrice());
        }

        RoomSchemeResponse response = new RoomSchemeResponse();
        response.setRoomType(roomType);
        response.setBudgetLimit(budgetLimit);
        response.setTotalPrice(totalPrice);
        response.setItemCount(items.size());
        response.setReasoning(recommendation.getReasoning());
        response.setItems(items);
        return response;
    }

    private String buildAnchorPrompt(RspuMaster anchor, List<RspuMaster> candidates) {
        List<String> allRspuIds = new ArrayList<>(candidates.size() + 1);
        allRspuIds.add(anchor.getRspuId());
        allRspuIds.addAll(candidates.stream().map(RspuMaster::getRspuId).toList());
        Map<String, BigDecimal> minPriceMap = batchMinPrices(allRspuIds);

        StringBuilder sb = new StringBuilder();
        sb.append("锚点产品信息：\n");
        sb.append("- ").append(anchor.getRspuId())
            .append(" | 风格：").append(anchor.getPositioningLabel())
            .append(" | 主色：").append(anchor.getColorPrimaryName())
            .append(" | 材质：").append(anchor.getMaterialTags())
            .append(" | 场景：").append(anchor.getSceneTags())
            .append(" | 最低报价：").append(minPriceMap.get(anchor.getRspuId()) != null ? minPriceMap.get(anchor.getRspuId()) : "无")
            .append(" 元\n\n");

        sb.append("目标品类候选产品（请从中挑选 1~3 个最搭配的）：\n");
        for (RspuMaster rspu : candidates) {
            BigDecimal minPrice = minPriceMap.get(rspu.getRspuId());
            sb.append("- ").append(rspu.getRspuId())
                .append(" | 风格：").append(rspu.getPositioningLabel())
                .append(" | 主色：").append(rspu.getColorPrimaryName())
                .append(" | 材质：").append(rspu.getMaterialTags())
                .append(" | 场景：").append(rspu.getSceneTags())
                .append(" | 最低报价：").append(minPrice != null ? minPrice : "无")
                .append(" 元\n");
        }

        sb.append("\n请输出 JSON 格式的推荐结果。");
        return sb.toString();
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
            item.setFactoryPrice(cheapest.getFactoryPrice());
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
        List<RskuSupply> rskus = rskuSupplyMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RskuSupply>()
                .in("rspu_id", rspuIds)
        );
        List<RskuSupply> capableRskus = filterCapableRskus(rskus);
        return capableRskus.stream()
            .filter(r -> r.getFactoryPrice() != null)
            .collect(Collectors.groupingBy(
                RskuSupply::getRspuId,
                Collectors.minBy(Comparator.comparing(RskuSupply::getFactoryPrice))
            ))
            .entrySet().stream()
            .filter(e -> e.getValue().isPresent())
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }

    /**
     * 过滤出工厂具备对应产品等级能力的 RSKU。
     */
    private List<RskuSupply> filterCapableRskus(List<RskuSupply> rskus) {
        if (rskus.isEmpty()) {
            return List.of();
        }
        Set<String> factoryCodes = rskus.stream()
            .map(RskuSupply::getFactoryCode)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<String, List<String>> capabilityMap = factoryCodes.stream()
            .collect(Collectors.toMap(
                code -> code,
                code -> factoryService.getFactoryCapableLevels(code)
            ));

        return rskus.stream()
            .filter(r -> {
                String productLevel = r.getProductLevel();
                if (productLevel == null || productLevel.isBlank()) {
                    return true;
                }
                List<String> capableLevels = capabilityMap.get(r.getFactoryCode());
                return capableLevels != null && capableLevels.contains(productLevel);
            })
            .collect(Collectors.toList());
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
