package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.request.FactoryLeadTimeRuleRequest;
import com.rsdp.dto.response.FactoryLeadTimeRuleResponse;
import com.rsdp.entity.FactoryLeadTimeRule;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FactoryLeadTimeRuleMapper;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.security.SecurityOperatorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工厂交期规则服务。
 */
@Service
@RequiredArgsConstructor
public class FactoryLeadTimeRuleService {

    private final FactoryLeadTimeRuleMapper ruleMapper;
    private final FactoryMasterMapper factoryMasterMapper;
    private final DictService dictService;
    private final AuditLogService auditLogService;

    /**
     * 创建或更新交期规则。
     *
     * @param request 规则请求
     * @return 规则 ID
     */
    @Transactional
    public Long saveRule(FactoryLeadTimeRuleRequest request) {
        validateRequest(request);

        FactoryLeadTimeRule rule;
        boolean isCreate = request.getRuleId() == null;
        if (isCreate) {
            rule = new FactoryLeadTimeRule();
            rule.setCreatedAt(LocalDateTime.now());
        } else {
            rule = ruleMapper.selectById(request.getRuleId());
            if (rule == null) {
                throw new ResourceNotFoundException("交期规则不存在: " + request.getRuleId());
            }
        }

        rule.setFactoryCode(request.getFactoryCode());
        rule.setCategoryCode(request.getCategoryCode());
        rule.setMaterialGradeCode(request.getMaterialGradeCode());
        rule.setProcessType(StringUtils.hasText(request.getProcessType()) ? request.getProcessType() : "standard");
        rule.setBaseDays(request.getBaseDays());
        rule.setBatchSizeThreshold(request.getBatchSizeThreshold());
        rule.setBatchExtraDays(request.getBatchExtraDays() != null ? request.getBatchExtraDays() : 0);
        rule.setMaterialSwitchExtraDays(request.getMaterialSwitchExtraDays() != null ? request.getMaterialSwitchExtraDays() : 0);
        rule.setPriority(request.getPriority() != null ? request.getPriority() : 100);
        rule.setStatus(StringUtils.hasText(request.getStatus()) ? request.getStatus() : "active");
        rule.setNotes(request.getNotes());
        rule.setCreatedBy(SecurityOperatorContext.currentUserId());
        rule.setUpdatedAt(LocalDateTime.now());

        if (isCreate) {
            ruleMapper.insert(rule);
            auditLogService.logCreate("factory_lead_time_rule", String.valueOf(rule.getRuleId()), rule,
                SecurityOperatorContext.currentUsername());
        } else {
            FactoryLeadTimeRule old = ruleMapper.selectById(rule.getRuleId());
            ruleMapper.updateById(rule);
            auditLogService.logUpdate("factory_lead_time_rule", String.valueOf(rule.getRuleId()), old, rule,
                SecurityOperatorContext.currentUsername());
        }
        return rule.getRuleId();
    }

    /**
     * 查询某工厂的所有生效规则。
     *
     * @param factoryCode 工厂代码
     * @return 规则列表
     */
    public List<FactoryLeadTimeRuleResponse> listByFactory(String factoryCode) {
        List<FactoryLeadTimeRule> rules = ruleMapper.selectActiveRulesByFactory(factoryCode);
        return enrichAndConvert(rules);
    }

    /**
     * 删除交期规则。
     *
     * @param ruleId 规则 ID
     */
    @Transactional
    public void deleteRule(Long ruleId) {
        FactoryLeadTimeRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw new ResourceNotFoundException("交期规则不存在: " + ruleId);
        }
        ruleMapper.deleteById(ruleId);
        auditLogService.logDelete("factory_lead_time_rule", String.valueOf(ruleId), rule,
            SecurityOperatorContext.currentUsername());
    }

    /**
     * 动态计算交期。
     *
     * <p>匹配顺序：精确匹配 → 忽略材质等级 → 忽略工艺类型 → 仅按工厂默认。</p>
     *
     * @param factoryCode       工厂代码
     * @param categoryCode      品类代码
     * @param materialGradeCode 材质等级代码
     * @param processType       工艺类型
     * @param quantity          数量
     * @return 交期天数；无规则时返回 null
     */
    public Integer calculateLeadTime(String factoryCode, String categoryCode,
                                     String materialGradeCode, String processType, Integer quantity) {
        List<FactoryLeadTimeRule> rules = ruleMapper.selectActiveRulesByFactory(factoryCode);
        if (rules.isEmpty()) {
            return null;
        }

        FactoryLeadTimeRule rule = findBestMatch(rules, categoryCode, materialGradeCode, processType);
        if (rule == null) {
            return null;
        }

        int days = rule.getBaseDays();
        if (quantity != null && rule.getBatchSizeThreshold() != null && quantity > rule.getBatchSizeThreshold()) {
            days += rule.getBatchExtraDays() != null ? rule.getBatchExtraDays() : 0;
        }
        return days;
    }

    private FactoryLeadTimeRule findBestMatch(List<FactoryLeadTimeRule> rules, String categoryCode,
                                               String materialGradeCode, String processType) {
        String effectiveProcess = StringUtils.hasText(processType) ? processType : "standard";

        // 精确匹配
        FactoryLeadTimeRule exact = rules.stream()
            .filter(r -> matchRule(r, categoryCode, materialGradeCode, effectiveProcess))
            .min(Comparator.comparingInt(FactoryLeadTimeRule::getPriority))
            .orElse(null);
        if (exact != null) {
            return exact;
        }

        // 忽略材质等级
        FactoryLeadTimeRule withoutMaterial = rules.stream()
            .filter(r -> matchRule(r, categoryCode, null, effectiveProcess))
            .min(Comparator.comparingInt(FactoryLeadTimeRule::getPriority))
            .orElse(null);
        if (withoutMaterial != null) {
            return withoutMaterial;
        }

        // 忽略工艺类型
        FactoryLeadTimeRule withoutProcess = rules.stream()
            .filter(r -> matchRule(r, categoryCode, materialGradeCode, "standard"))
            .min(Comparator.comparingInt(FactoryLeadTimeRule::getPriority))
            .orElse(null);
        if (withoutProcess != null) {
            return withoutProcess;
        }

        // 仅按工厂默认（品类、材质、工艺全部通配）
        return rules.stream()
            .filter(r -> matchRule(r, null, null, "standard"))
            .min(Comparator.comparingInt(FactoryLeadTimeRule::getPriority))
            .orElse(null);
    }

    private boolean matchRule(FactoryLeadTimeRule rule, String categoryCode,
                              String materialGradeCode, String processType) {
        boolean categoryMatch = !StringUtils.hasText(categoryCode)
            || categoryCode.equals(rule.getCategoryCode())
            || rule.getCategoryCode() == null;
        boolean materialMatch = !StringUtils.hasText(materialGradeCode)
            || materialGradeCode.equals(rule.getMaterialGradeCode())
            || rule.getMaterialGradeCode() == null;
        boolean processMatch = processType.equals(rule.getProcessType()) || rule.getProcessType() == null;
        return categoryMatch && materialMatch && processMatch;
    }

    private void validateRequest(FactoryLeadTimeRuleRequest request) {
        FactoryMaster factory = factoryMasterMapper.selectById(request.getFactoryCode());
        if (factory == null) {
            throw new BusinessException("工厂不存在: " + request.getFactoryCode());
        }
        if (request.getBaseDays() == null || request.getBaseDays() <= 0) {
            throw new BusinessException("基础交期天数必须大于 0");
        }
    }

    private List<FactoryLeadTimeRuleResponse> enrichAndConvert(List<FactoryLeadTimeRule> rules) {
        Set<String> factoryCodes = rules.stream()
            .map(FactoryLeadTimeRule::getFactoryCode)
            .collect(Collectors.toSet());
        Map<String, FactoryMaster> factoryMap = factoryCodes.isEmpty() ? Map.of()
            : factoryMasterMapper.selectBatchIds(factoryCodes).stream()
                .collect(Collectors.toMap(FactoryMaster::getFactoryCode, f -> f));

        return rules.stream()
            .map(r -> toResponse(r, factoryMap.get(r.getFactoryCode())))
            .toList();
    }

    private FactoryLeadTimeRuleResponse toResponse(FactoryLeadTimeRule rule, FactoryMaster factory) {
        FactoryLeadTimeRuleResponse response = new FactoryLeadTimeRuleResponse();
        response.setRuleId(rule.getRuleId());
        response.setFactoryCode(rule.getFactoryCode());
        response.setFactoryName(factory != null ? factory.getFactoryName() : null);
        response.setCategoryCode(rule.getCategoryCode());
        response.setMaterialGradeCode(rule.getMaterialGradeCode());
        response.setProcessType(rule.getProcessType());
        response.setBaseDays(rule.getBaseDays());
        response.setBatchSizeThreshold(rule.getBatchSizeThreshold());
        response.setBatchExtraDays(rule.getBatchExtraDays());
        response.setMaterialSwitchExtraDays(rule.getMaterialSwitchExtraDays());
        response.setPriority(rule.getPriority());
        response.setStatus(rule.getStatus());
        response.setNotes(rule.getNotes());
        response.setCreatedAt(rule.getCreatedAt());
        response.setUpdatedAt(rule.getUpdatedAt());
        return response;
    }
}
