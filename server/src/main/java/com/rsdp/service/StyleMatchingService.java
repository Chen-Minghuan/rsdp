package com.rsdp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiLabels;
import com.rsdp.dto.ElementMatchDetail;
import com.rsdp.dto.FormulaScore;
import com.rsdp.dto.StyleMatchResult;
import com.rsdp.entity.ProductStyleMatch;
import com.rsdp.entity.StyleMatchingFormula;
import com.rsdp.mapper.ProductStyleMatchMapper;
import com.rsdp.mapper.StyleMatchingFormulaMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 风格匹配服务：基于 style_matching_formula 对 AI 识别结果做校验评分。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StyleMatchingService {

    private final StyleMatchingFormulaMapper formulaMapper;
    private final ProductStyleMatchMapper matchMapper;
    private final ObjectMapper objectMapper;

    private static final BigDecimal SCORE_HIGH = new BigDecimal("0.75");
    private static final BigDecimal SCORE_MID = new BigDecimal("0.50");
    private static final int SCORE_SCALE = 4;

    /**
     * 对 AI 识别结果进行风格匹配评分，并将结果写入 product_style_match。
     *
     * @param labels  AI 识别标签
     * @param rspuId  产品 RSPU ID
     * @return 匹配结果；若无法匹配则返回 null
     */
    @Transactional(rollbackFor = Exception.class)
    public StyleMatchResult match(AiLabels labels, String rspuId) {
        if (labels == null || !StringUtils.hasText(labels.getStyle())) {
            log.warn("风格匹配入参为空，跳过评分，rspuId={}", rspuId);
            return null;
        }

        String styleCode = resolveStyleCode(labels.getStyle());
        if (!StringUtils.hasText(styleCode)) {
            log.warn("无法解析风格编码，styleName={}，rspuId={}", labels.getStyle(), rspuId);
            return null;
        }

        StyleMatchingFormula formula = findActiveFormula(styleCode);
        if (formula == null) {
            log.warn("未找到风格搭配公式，styleCode={}，rspuId={}", styleCode, rspuId);
            return null;
        }

        FormulaDto formulaDto = parseFormula(formula.getFormulaJson());
        MatchContext ctx = new MatchContext(labels);

        List<ElementMatchDetail> elementMatches = new ArrayList<>();
        List<FormulaScore> formulaScores = new ArrayList<>();

        evaluateMustHave(formulaDto.mustHave, ctx, elementMatches, formulaScores);
        evaluateCompatible(formulaDto.compatible, ctx, elementMatches, formulaScores);
        boolean hasAvoidHit = evaluateAvoid(formulaDto.avoid, ctx, elementMatches, formulaScores);

        BigDecimal overallScore = computeOverallScore(formulaScores);
        String confidence = mapConfidence(overallScore);

        StyleMatchResult result = StyleMatchResult.builder()
            .styleCode(styleCode)
            .overallScore(overallScore)
            .confidence(confidence)
            .elementMatches(elementMatches)
            .formulaScores(formulaScores)
            .hasAvoidHit(hasAvoidHit)
            .reason(buildReason(elementMatches, overallScore, confidence))
            .build();

        saveMatch(rspuId, styleCode, result);
        return result;
    }

    /**
     * 根据风格名称解析风格编码。优先尝试直接匹配 code，再尝试首字母大写形式。
     */
    private String resolveStyleCode(String styleName) {
        if (!StringUtils.hasText(styleName)) {
            return null;
        }
        String trimmed = styleName.trim();
        if (trimmed.length() <= 4 && trimmed.matches("[A-Za-z0-9]+")) {
            return trimmed.toUpperCase();
        }
        // 简单兜底：尝试从名称中提取双字母或中文风格简称
        // 实际项目中可扩展为完整的别名映射
        return null;
    }

    @Cacheable(value = "styleFormula", key = "#styleCode")
    public StyleMatchingFormula findActiveFormula(String styleCode) {
        return formulaMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StyleMatchingFormula>()
                .eq(StyleMatchingFormula::getStyleCode, styleCode)
                .eq(StyleMatchingFormula::getStatus, "active")
                .orderByDesc(StyleMatchingFormula::getPriority)
        ).stream().findFirst().orElse(null);
    }

    @SneakyThrows
    private FormulaDto parseFormula(String formulaJson) {
        if (!StringUtils.hasText(formulaJson)) {
            return new FormulaDto();
        }
        return objectMapper.readValue(formulaJson, FormulaDto.class);
    }

    private void evaluateMustHave(List<Rule> rules, MatchContext ctx,
                                  List<ElementMatchDetail> elementMatches,
                                  List<FormulaScore> formulaScores) {
        if (CollectionUtils.isEmpty(rules)) {
            return;
        }

        int hitCount = 0;
        List<String> matchedValues = new ArrayList<>();
        for (Rule rule : rules) {
            List<String> intersection = ctx.intersect(rule.getType(), rule.getValues());
            boolean matched = !intersection.isEmpty();
            if (matched) {
                hitCount++;
                matchedValues.addAll(intersection);
            }
            elementMatches.add(ElementMatchDetail.builder()
                .type(rule.getType())
                .value(String.join(",", rule.getValues()))
                .role("must_have")
                .matched(matched)
                .reason(matched ? "命中核心元素" : "缺失核心元素")
                .build());
        }

        BigDecimal score = rules.isEmpty()
            ? BigDecimal.ONE
            : BigDecimal.valueOf(hitCount).divide(BigDecimal.valueOf(rules.size()), SCORE_SCALE, RoundingMode.HALF_UP);
        formulaScores.add(FormulaScore.builder()
            .dimension("must_have")
            .score(score)
            .weight(new BigDecimal("0.35"))
            .matchedValues(String.join(",", matchedValues))
            .reason("核心元素命中情况")
            .build());
    }

    private void evaluateCompatible(List<Rule> rules, MatchContext ctx,
                                    List<ElementMatchDetail> elementMatches,
                                    List<FormulaScore> formulaScores) {
        if (CollectionUtils.isEmpty(rules)) {
            return;
        }

        for (Rule rule : rules) {
            List<String> intersection = ctx.intersect(rule.getType(), rule.getValues());
            boolean matched = !intersection.isEmpty();
            BigDecimal weight = rule.getWeight() != null ? rule.getWeight() : BigDecimal.ZERO;
            BigDecimal score = matched ? BigDecimal.ONE : BigDecimal.ZERO;

            elementMatches.add(ElementMatchDetail.builder()
                .type(rule.getType())
                .value(String.join(",", rule.getValues()))
                .role("compatible")
                .matched(matched)
                .reason(matched ? "命中兼容元素（weight=" + weight + "）" : "未命中兼容元素")
                .build());

            formulaScores.add(FormulaScore.builder()
                .dimension("compatible-" + rule.getType())
                .score(score)
                .weight(weight)
                .matchedValues(String.join(",", intersection))
                .reason(rule.getReason())
                .build());
        }
    }

    private boolean evaluateAvoid(List<Rule> rules, MatchContext ctx,
                                  List<ElementMatchDetail> elementMatches,
                                  List<FormulaScore> formulaScores) {
        if (CollectionUtils.isEmpty(rules)) {
            return false;
        }

        int hitCount = 0;
        List<String> hitValues = new ArrayList<>();
        for (Rule rule : rules) {
            List<String> intersection = ctx.intersect(rule.getType(), rule.getValues());
            boolean matched = !intersection.isEmpty();
            if (matched) {
                hitCount++;
                hitValues.addAll(intersection);
            }
            elementMatches.add(ElementMatchDetail.builder()
                .type(rule.getType())
                .value(String.join(",", rule.getValues()))
                .role("avoid")
                .matched(matched)
                .reason(matched ? "命中风格禁忌" : "未触发禁忌")
                .build());
        }

        BigDecimal score = rules.isEmpty()
            ? BigDecimal.ONE
            : BigDecimal.ONE.subtract(
                BigDecimal.valueOf(hitCount)
                    .divide(BigDecimal.valueOf(rules.size()), SCORE_SCALE, RoundingMode.HALF_UP));
        formulaScores.add(FormulaScore.builder()
            .dimension("avoid")
            .score(score)
            .weight(new BigDecimal("0.30"))
            .matchedValues(String.join(",", hitValues))
            .reason("风格禁忌规避情况")
            .build());

        return hitCount > 0;
    }

    private BigDecimal computeOverallScore(List<FormulaScore> formulaScores) {
        if (CollectionUtils.isEmpty(formulaScores)) {
            return BigDecimal.ZERO;
        }

        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal weightSum = BigDecimal.ZERO;
        for (FormulaScore fs : formulaScores) {
            BigDecimal weight = fs.getWeight() != null ? fs.getWeight() : BigDecimal.ZERO;
            weightedSum = weightedSum.add(fs.getScore().multiply(weight));
            weightSum = weightSum.add(weight);
        }

        if (weightSum.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return weightedSum.divide(weightSum, SCORE_SCALE, RoundingMode.HALF_UP);
    }

    private String mapConfidence(BigDecimal score) {
        if (score.compareTo(SCORE_HIGH) >= 0) {
            return "high";
        }
        if (score.compareTo(SCORE_MID) >= 0) {
            return "mid";
        }
        return "low";
    }

    private String buildReason(List<ElementMatchDetail> elementMatches, BigDecimal score, String confidence) {
        long missingMustHave = elementMatches.stream()
            .filter(e -> "must_have".equals(e.getRole()) && !e.isMatched())
            .count();
        long avoidHit = elementMatches.stream()
            .filter(e -> "avoid".equals(e.getRole()) && e.isMatched())
            .count();

        StringBuilder sb = new StringBuilder();
        sb.append("整体得分 ").append(score.setScale(2, RoundingMode.HALF_UP))
          .append("，置信度 ").append(confidence).append("。");
        if (missingMustHave > 0) {
            sb.append("缺失 ").append(missingMustHave).append(" 项核心元素。");
        }
        if (avoidHit > 0) {
            sb.append("命中 ").append(avoidHit).append(" 项风格禁忌。");
        }
        if (missingMustHave == 0 && avoidHit == 0) {
            sb.append("核心元素完整且无禁忌冲突。");
        }
        return sb.toString();
    }

    private void saveMatch(String rspuId, String styleCode, StyleMatchResult result) {
        try {
            ProductStyleMatch existing = matchMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProductStyleMatch>()
                    .eq(ProductStyleMatch::getRspuId, rspuId)
                    .eq(ProductStyleMatch::getStyleCode, styleCode)
            ).stream().findFirst().orElse(null);

            ProductStyleMatch entity = new ProductStyleMatch();
            entity.setRspuId(rspuId);
            entity.setDictType("style");
            entity.setStyleCode(styleCode);
            entity.setOverallScore(result.getOverallScore());
            entity.setConfidence(result.getConfidence());
            entity.setElementMatch(objectMapper.writeValueAsString(result.getElementMatches()));
            entity.setFormulaScores(objectMapper.writeValueAsString(result.getFormulaScores()));
            entity.setUpdatedAt(LocalDateTime.now());

            if (existing == null) {
                entity.setCreatedAt(LocalDateTime.now());
                matchMapper.insert(entity);
            } else {
                entity.setMatchId(existing.getMatchId());
                matchMapper.updateById(entity);
            }
        } catch (Exception e) {
            log.error("保存 product_style_match 失败，rspuId={}，styleCode={}", rspuId, styleCode, e);
        }
    }

    /**
     * 匹配上下文，统一封装 AI 识别出的各类元素，便于与公式规则比对。
     */
    private static class MatchContext {

        private final List<String> materials = new ArrayList<>();
        private final List<String> scenes = new ArrayList<>();
        private final List<String> colors = new ArrayList<>();
        private final List<String> categories = new ArrayList<>();
        private final List<String> moods = new ArrayList<>();
        private final List<String> sixDimValues = new ArrayList<>();

        MatchContext(AiLabels labels) {
            Optional.ofNullable(labels.getMaterialTags()).ifPresent(materials::addAll);
            Optional.ofNullable(labels.getSceneTags()).ifPresent(scenes::addAll);
            if (StringUtils.hasText(labels.getColorPrimaryName())) {
                colors.add(labels.getColorPrimaryName());
            }
            // category 暂无法从 AiLabels 直接获取，由调用方补充；OCR 材质描述也作为 material 补充
            if (labels.getOcr() != null && StringUtils.hasText(labels.getOcr().getMaterialDescription())) {
                materials.add(labels.getOcr().getMaterialDescription());
            }
            if (labels.getSixDimTags() != null) {
                labels.getSixDimTags().values().stream()
                    .filter(StringUtils::hasText)
                    .forEach(sixDimValues::add);
            }
        }

        List<String> intersect(String type, List<String> ruleValues) {
            Collection<String> source = switch (type == null ? "" : type) {
                case "material" -> materials;
                case "scene" -> scenes;
                case "color" -> colors;
                case "category" -> categories;
                case "mood" -> moods;
                default -> new ArrayList<>();
            };

            if (CollectionUtils.isEmpty(ruleValues)) {
                return List.of();
            }

            return ruleValues.stream()
                .filter(ruleValue -> source.stream().anyMatch(src -> containsIgnoreCase(src, ruleValue)))
                .collect(Collectors.toList());
        }

        private static boolean containsIgnoreCase(String source, String target) {
            if (!StringUtils.hasText(source) || !StringUtils.hasText(target)) {
                return false;
            }
            String s = source.toLowerCase();
            String t = target.toLowerCase();
            return s.contains(t) || t.contains(s);
        }
    }

    /**
     * 搭配公式 JSON 内部表示。
     */
    private static class FormulaDto {
        public List<Rule> mustHave = new ArrayList<>();
        public List<Rule> compatible = new ArrayList<>();
        public List<Rule> avoid = new ArrayList<>();
        public Map<String, Object> spatialHint = Map.of();

        // Jackson 反序列化用
        public void setMust_have(List<Rule> mustHave) { this.mustHave = mustHave; }
        public void setMustHave(List<Rule> mustHave) { this.mustHave = mustHave; }
        public void setCompatible(List<Rule> compatible) { this.compatible = compatible; }
        public void setAvoid(List<Rule> avoid) { this.avoid = avoid; }
        public void setSpatial_hint(Map<String, Object> spatialHint) { this.spatialHint = spatialHint; }
        public void setSpatialHint(Map<String, Object> spatialHint) { this.spatialHint = spatialHint; }
    }

    /**
     * 单条规则。
     */
    private static class Rule {
        private String type;
        private List<String> values;
        private String role;
        private BigDecimal weight;
        private String relation;
        private String reason;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public List<String> getValues() { return values == null ? List.of() : values; }
        public void setValues(List<String> values) { this.values = values; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public BigDecimal getWeight() { return weight; }
        public void setWeight(BigDecimal weight) { this.weight = weight; }
        public String getRelation() { return relation; }
        public void setRelation(String relation) { this.relation = relation; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
