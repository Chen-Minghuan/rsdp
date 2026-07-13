package com.rsdp.service;

import com.rsdp.dto.request.FactoryLeadTimeRuleRequest;
import com.rsdp.dto.response.FactoryLeadTimeRuleResponse;
import com.rsdp.entity.FactoryLeadTimeRule;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.FactoryLeadTimeRuleMapper;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.security.SecurityOperatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FactoryLeadTimeRuleService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class FactoryLeadTimeRuleServiceTest {

    @Mock
    private FactoryLeadTimeRuleMapper ruleMapper;

    @Mock
    private FactoryMasterMapper factoryMasterMapper;

    @Mock
    private DictService dictService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private FactoryLeadTimeRuleService ruleService;

    @Test
    void saveRule_shouldCreateWhenFactoryExists() {
        FactoryLeadTimeRuleRequest request = new FactoryLeadTimeRuleRequest();
        request.setFactoryCode("F001");
        request.setCategoryCode("FS");
        request.setMaterialGradeCode("FABRIC_A");
        request.setProcessType("standard");
        request.setBaseDays(30);
        request.setBatchSizeThreshold(50);
        request.setBatchExtraDays(7);
        request.setPriority(10);

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        when(factoryMasterMapper.selectById("F001")).thenReturn(factory);
        when(ruleMapper.insert(any(FactoryLeadTimeRule.class))).thenAnswer(inv -> {
            FactoryLeadTimeRule rule = inv.getArgument(0);
            rule.setRuleId(1L);
            return 1;
        });

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.currentUsername()).thenReturn("测试用户");

            Long id = ruleService.saveRule(request);

            assertThat(id).isEqualTo(1L);
        }

        ArgumentCaptor<FactoryLeadTimeRule> captor = ArgumentCaptor.forClass(FactoryLeadTimeRule.class);
        verify(ruleMapper, times(1)).insert(captor.capture());
        FactoryLeadTimeRule saved = captor.getValue();
        assertThat(saved.getFactoryCode()).isEqualTo("F001");
        assertThat(saved.getCategoryCode()).isEqualTo("FS");
        assertThat(saved.getBaseDays()).isEqualTo(30);
        assertThat(saved.getPriority()).isEqualTo(10);
    }

    @Test
    void saveRule_shouldThrowWhenBaseDaysInvalid() {
        FactoryLeadTimeRuleRequest request = new FactoryLeadTimeRuleRequest();
        request.setFactoryCode("F001");
        request.setBaseDays(0);

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        when(factoryMasterMapper.selectById("F001")).thenReturn(factory);

        assertThatThrownBy(() -> ruleService.saveRule(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("基础交期天数必须大于 0");
    }

    @Test
    void saveRule_shouldThrowWhenFactoryNotExists() {
        FactoryLeadTimeRuleRequest request = new FactoryLeadTimeRuleRequest();
        request.setFactoryCode("F999");
        request.setBaseDays(30);

        when(factoryMasterMapper.selectById("F999")).thenReturn(null);

        assertThatThrownBy(() -> ruleService.saveRule(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("工厂不存在");
    }

    @Test
    void calculateLeadTime_shouldReturnExactMatch() {
        FactoryLeadTimeRule rule = createRule("FS", "FABRIC_A", "standard", 25, null, null, 10);

        when(ruleMapper.selectActiveRulesByFactory("F001")).thenReturn(List.of(rule));

        Integer days = ruleService.calculateLeadTime("F001", "FS", "FABRIC_A", "standard", 1);

        assertThat(days).isEqualTo(25);
    }

    @Test
    void calculateLeadTime_shouldFallbackWithoutMaterial() {
        FactoryLeadTimeRule exactRule = createRule("FS", "FABRIC_A", "standard", 25, null, null, 20);
        FactoryLeadTimeRule materialWildcard = createRule("FS", null, "standard", 30, null, null, 10);

        when(ruleMapper.selectActiveRulesByFactory("F001"))
            .thenReturn(List.of(exactRule, materialWildcard));

        Integer days = ruleService.calculateLeadTime("F001", "FS", "FABRIC_SS", "standard", 1);

        assertThat(days).isEqualTo(30);
    }

    @Test
    void calculateLeadTime_shouldFallbackToFactoryDefault() {
        FactoryLeadTimeRule categoryRule = createRule("FS", "FABRIC_A", "standard", 25, null, null, 10);
        FactoryLeadTimeRule defaultRule = createRule(null, null, "standard", 45, null, null, 100);

        when(ruleMapper.selectActiveRulesByFactory("F001"))
            .thenReturn(List.of(categoryRule, defaultRule));

        Integer days = ruleService.calculateLeadTime("F001", "DT", "LEATHER_A", "custom", 1);

        assertThat(days).isEqualTo(45);
    }

    @Test
    void calculateLeadTime_shouldAddBatchExtraDays() {
        FactoryLeadTimeRule rule = createRule("FS", "FABRIC_A", "standard", 25, 50, 7, 10);

        when(ruleMapper.selectActiveRulesByFactory("F001")).thenReturn(List.of(rule));

        Integer days = ruleService.calculateLeadTime("F001", "FS", "FABRIC_A", "standard", 60);

        assertThat(days).isEqualTo(32);
    }

    @Test
    void calculateLeadTime_shouldReturnNullWhenNoRules() {
        when(ruleMapper.selectActiveRulesByFactory("F001")).thenReturn(List.of());

        Integer days = ruleService.calculateLeadTime("F001", "FS", "FABRIC_A", "standard", 1);

        assertThat(days).isNull();
    }

    @Test
    void listByFactory_shouldReturnActiveRules() {
        FactoryLeadTimeRule rule = createRule("FS", "FABRIC_A", "standard", 25, null, null, 10);
        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("测试工厂");

        when(ruleMapper.selectActiveRulesByFactory("F001")).thenReturn(List.of(rule));
        when(factoryMasterMapper.selectBatchIds(any())).thenReturn(List.of(factory));

        List<FactoryLeadTimeRuleResponse> result = ruleService.listByFactory("F001");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFactoryName()).isEqualTo("测试工厂");
        assertThat(result.get(0).getBaseDays()).isEqualTo(25);
    }

    @Test
    void deleteRule_shouldDeleteAndLog() {
        FactoryLeadTimeRule rule = createRule("FS", "FABRIC_A", "standard", 25, null, null, 10);
        rule.setRuleId(1L);

        when(ruleMapper.selectById(1L)).thenReturn(rule);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUsername()).thenReturn("测试用户");

            ruleService.deleteRule(1L);
        }

        verify(ruleMapper, times(1)).deleteById(1L);
        verify(auditLogService, times(1)).logDelete(eq("factory_lead_time_rule"), eq("1"), eq(rule), any());
    }

    @Test
    void deleteRule_shouldThrowWhenNotFound() {
        when(ruleMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> ruleService.deleteRule(999L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    private FactoryLeadTimeRule createRule(String categoryCode, String materialGradeCode, String processType,
                                           int baseDays, Integer threshold, Integer extraDays, int priority) {
        FactoryLeadTimeRule rule = new FactoryLeadTimeRule();
        rule.setFactoryCode("F001");
        rule.setCategoryCode(categoryCode);
        rule.setMaterialGradeCode(materialGradeCode);
        rule.setProcessType(processType);
        rule.setBaseDays(baseDays);
        rule.setBatchSizeThreshold(threshold);
        rule.setBatchExtraDays(extraDays);
        rule.setPriority(priority);
        rule.setStatus("active");
        return rule;
    }
}
