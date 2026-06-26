package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiSchemeRecommendation;
import com.rsdp.dto.request.RoomSchemeRequest;
import com.rsdp.dto.response.SchemeItemResponse;
import com.rsdp.dto.response.RoomSchemeResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
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

    private List<RspuMaster> fetchCandidates(String stylePreference) {
        List<RspuMaster> all = rspuMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RspuMaster>()
                .eq("status", "active")
                .isNull("deleted_at")
                .orderByDesc("created_at")
        );

        String styleName = getDictName("style", stylePreference);
        return all.stream()
            .filter(r -> styleName == null || styleName.isBlank()
                || styleName.equals(r.getPositioningLabel()))
            .limit(MAX_CANDIDATES)
            .collect(Collectors.toList());
    }

    private String buildPrompt(String roomTypeName, BigDecimal budgetLimit, String styleName, List<RspuMaster> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("请为以下空间生成家具搭配方案。\n");
        sb.append("空间类型：").append(roomTypeName).append("\n");
        sb.append("预算上限：").append(budgetLimit).append(" 元\n");
        if (styleName != null && !styleName.isBlank()) {
            sb.append("风格偏好：").append(styleName).append("\n");
        }
        sb.append("\n候选产品（请从中挑选）：\n");

        for (RspuMaster rspu : candidates) {
            BigDecimal minPrice = findMinPrice(rspu.getRspuId());
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

    private BigDecimal findMinPrice(String rspuId) {
        List<RskuSupply> rskus = rskuSupplyMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RskuSupply>()
                .eq("rspu_id", rspuId)
                .isNull("deleted_at")
        );
        return rskus.stream()
            .map(RskuSupply::getFactoryPrice)
            .filter(Objects::nonNull)
            .min(Comparator.naturalOrder())
            .orElse(null);
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

        List<SchemeItemResponse> items = new ArrayList<>();
        BigDecimal totalPrice = BigDecimal.ZERO;

        for (String rspuId : selectedIds) {
            RskuSupply cheapest = findCheapestRsku(rspuId);
            if (cheapest == null) continue;

            RspuMaster rspu = candidates.stream()
                .filter(r -> r.getRspuId().equals(rspuId))
                .findFirst()
                .orElse(null);
            if (rspu == null) continue;

            FactoryMaster factory = factoryMasterMapper.selectById(cheapest.getFactoryCode());

            SchemeItemResponse item = new SchemeItemResponse();
            item.setRspuId(rspuId);
            item.setRspuName(rspu.getPositioningLabel());
            item.setPrimaryImageUrl(findPrimaryImageUrl(rspuId));
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

    private RskuSupply findCheapestRsku(String rspuId) {
        List<RskuSupply> rskus = rskuSupplyMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RskuSupply>()
                .eq("rspu_id", rspuId)
                .isNull("deleted_at")
        );
        return rskus.stream()
            .filter(r -> r.getFactoryPrice() != null)
            .min(Comparator.comparing(RskuSupply::getFactoryPrice))
            .orElse(null);
    }

    private String findPrimaryImageUrl(String rspuId) {
        List<ImageAssets> images = imageAssetsMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ImageAssets>()
                .eq("rspu_id", rspuId)
                .eq("is_primary", true)
                .last("LIMIT 1")
        );
        if (images == null || images.isEmpty()) {
            return null;
        }
        return "/api/v1/images/" + images.get(0).getImageId();
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
