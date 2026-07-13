package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.FactoryLeadTimeRuleRequest;
import com.rsdp.dto.response.FactoryLeadTimeRuleResponse;
import com.rsdp.service.FactoryLeadTimeRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工厂交期规则接口。
 */
@RestController
@RequestMapping("/api/v1/factories/{factoryCode}/lead-time-rules")
@RequiredArgsConstructor
public class FactoryLeadTimeRuleController {

    private final FactoryLeadTimeRuleService ruleService;

    /**
     * 查询某工厂的所有交期规则。
     */
    @GetMapping
    public Result<List<FactoryLeadTimeRuleResponse>> listByFactory(@PathVariable String factoryCode) {
        return Result.ok(ruleService.listByFactory(factoryCode));
    }

    /**
     * 创建/更新交期规则。
     */
    @PostMapping
    public Result<Long> saveRule(@PathVariable String factoryCode,
                                  @Valid @RequestBody FactoryLeadTimeRuleRequest request) {
        request.setFactoryCode(factoryCode);
        return Result.ok(ruleService.saveRule(request));
    }

    /**
     * 删除交期规则。
     */
    @DeleteMapping("/{ruleId}")
    public Result<Void> deleteRule(@PathVariable String factoryCode, @PathVariable Long ruleId) {
        ruleService.deleteRule(ruleId);
        return Result.ok();
    }

    /**
     * 动态计算交期。
     */
    @GetMapping("/calculate")
    public Result<Integer> calculate(@PathVariable String factoryCode,
                                      @RequestParam(required = false) String categoryCode,
                                      @RequestParam(required = false) String materialGradeCode,
                                      @RequestParam(required = false, defaultValue = "standard") String processType,
                                      @RequestParam(required = false, defaultValue = "1") Integer quantity) {
        return Result.ok(ruleService.calculateLeadTime(factoryCode, categoryCode, materialGradeCode, processType, quantity));
    }
}
