package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.config.CacheConfig;
import com.rsdp.dto.request.PricingRuleUpsertRequest;
import com.rsdp.dto.response.PricingRuleResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.PricingRule;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.CategoryDictMapper;
import com.rsdp.mapper.PricingRuleMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 品类级加价规则服务（pricing_rule）。
 *
 * <p>售价解析链中的一级：RSPU 建议销售价 retail_price 优先；否则成本 × 品类倍率；
 * 无品类规则时由 {@link PricingService} 回退全局倍率。规则全量读走 Spring Cache，
 * 写操作（upsert/删除）整体失效并记审计日志。</p>
 */
@Service
@RequiredArgsConstructor
public class PricingRuleService {

    private final PricingRuleMapper pricingRuleMapper;
    private final CategoryDictMapper categoryDictMapper;
    private final AuditLogService auditLogService;

    /**
     * 读取全部品类规则（category_code → 规则），供售价解析热路径使用。
     *
     * <p>结果整体缓存（{@code pricingRule::all}），规则变更时全量失效。</p>
     *
     * @return 品类编码 → 规则映射
     */
    @Cacheable(cacheNames = CacheConfig.CACHE_NAME_PRICING_RULE, key = "'all'")
    public Map<String, PricingRule> listAllRules() {
        return pricingRuleMapper.selectList(null).stream()
            .collect(Collectors.toMap(PricingRule::getCategoryCode, r -> r, (a, b) -> a));
    }

    /**
     * 规则列表（关联 category_dict 返回品类名称，按品类编码排序）。
     *
     * @return 规则响应列表
     */
    public List<PricingRuleResponse> listRules() {
        List<PricingRule> rules = pricingRuleMapper.selectList(
            new QueryWrapper<PricingRule>().orderByAsc("category_code"));
        Map<String, String> categoryNames = batchCategoryNames(
            rules.stream().map(PricingRule::getCategoryCode).toList());
        return rules.stream().map(rule -> {
            PricingRuleResponse response = new PricingRuleResponse();
            response.setRuleId(rule.getRuleId());
            response.setCategoryCode(rule.getCategoryCode());
            response.setCategoryName(categoryNames.get(rule.getCategoryCode()));
            response.setMarkupMultiplier(rule.getMarkupMultiplier());
            response.setRemark(rule.getRemark());
            response.setCreatedAt(rule.getCreatedAt());
            response.setUpdatedAt(rule.getUpdatedAt());
            return response;
        }).toList();
    }

    /**
     * 按品类编码 upsert 加价规则（不存在则创建，存在则更新倍率与备注）。
     *
     * @param categoryCode 品类编码
     * @param request      请求（倍率必须 &gt; 0）
     * @return 生效后的规则
     */
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.CACHE_NAME_PRICING_RULE, allEntries = true)
    public PricingRuleResponse upsertRule(String categoryCode, PricingRuleUpsertRequest request) {
        BigDecimal multiplier = request.getMarkupMultiplier();
        if (multiplier == null || multiplier.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("品类加价倍率必须大于 0，当前值: " + multiplier);
        }

        PricingRule rule = pricingRuleMapper.selectOne(
            new QueryWrapper<PricingRule>().eq("category_code", categoryCode));
        if (rule == null) {
            rule = new PricingRule();
            rule.setRuleId(IdGenerator.generate("PRULE"));
            rule.setCategoryCode(categoryCode);
            rule.setMarkupMultiplier(multiplier);
            rule.setRemark(request.getRemark());
            rule.setCreatedAt(LocalDateTime.now());
            rule.setUpdatedAt(rule.getCreatedAt());
            pricingRuleMapper.insert(rule);
            auditLogService.logCreate("pricing_rule", rule.getRuleId(), rule, currentUsername());
        } else {
            PricingRule oldSnapshot = snapshot(rule);
            rule.setMarkupMultiplier(multiplier);
            if (request.getRemark() != null) {
                rule.setRemark(StringUtils.hasText(request.getRemark()) ? request.getRemark() : null);
            }
            rule.setUpdatedAt(LocalDateTime.now());
            pricingRuleMapper.updateById(rule);
            auditLogService.logUpdate("pricing_rule", rule.getRuleId(), oldSnapshot, rule, currentUsername());
        }

        PricingRuleResponse response = new PricingRuleResponse();
        response.setRuleId(rule.getRuleId());
        response.setCategoryCode(rule.getCategoryCode());
        response.setCategoryName(batchCategoryNames(List.of(categoryCode)).get(categoryCode));
        response.setMarkupMultiplier(rule.getMarkupMultiplier());
        response.setRemark(rule.getRemark());
        response.setCreatedAt(rule.getCreatedAt());
        response.setUpdatedAt(rule.getUpdatedAt());
        return response;
    }

    /**
     * 按品类编码删除加价规则。
     *
     * @param categoryCode 品类编码
     */
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.CACHE_NAME_PRICING_RULE, allEntries = true)
    public void deleteRule(String categoryCode) {
        PricingRule rule = pricingRuleMapper.selectOne(
            new QueryWrapper<PricingRule>().eq("category_code", categoryCode));
        if (rule == null) {
            throw new ResourceNotFoundException("定价规则不存在: " + categoryCode);
        }
        pricingRuleMapper.deleteById(rule.getRuleId());
        auditLogService.logDelete("pricing_rule", rule.getRuleId(), rule, currentUsername());
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

    private String currentUsername() {
        String username = SecurityOperatorContext.currentUsername();
        return StringUtils.hasText(username) ? username : "unknown";
    }

    private PricingRule snapshot(PricingRule source) {
        PricingRule copy = new PricingRule();
        copy.setRuleId(source.getRuleId());
        copy.setCategoryCode(source.getCategoryCode());
        copy.setMarkupMultiplier(source.getMarkupMultiplier());
        copy.setRemark(source.getRemark());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }
}
