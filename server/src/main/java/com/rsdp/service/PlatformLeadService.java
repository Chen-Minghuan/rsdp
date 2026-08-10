package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.common.PageResult;
import com.rsdp.dto.request.LeadCreateRequest;
import com.rsdp.dto.response.LeadAssigneeResponse;
import com.rsdp.dto.response.LeadCreateResponse;
import com.rsdp.dto.response.LeadListItemResponse;
import com.rsdp.dto.response.LeadSourceStatsResponse;
import com.rsdp.entity.PlatformLead;
import com.rsdp.entity.SysUser;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.PlatformLeadMapper;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 官网留资线索服务：用户端公开提交入口（免登录）+ 管理端分配/跟进/状态流转。
 */
@Service
@RequiredArgsConstructor
public class PlatformLeadService {

    /** 合法来源取值。 */
    private static final Set<String> VALID_SOURCES = Set.of(
        PlatformLead.SOURCE_AI_MATCH,
        PlatformLead.SOURCE_SITE_FORM,
        PlatformLead.SOURCE_DESIGN_BOOKING);

    /** 状态流转顺序（仅允许向前）：pending → contacted → done。 */
    private static final Map<String, Integer> STATUS_ORDER = Map.of(
        PlatformLead.STATUS_PENDING, 0,
        PlatformLead.STATUS_CONTACTED, 1,
        PlatformLead.STATUS_DONE, 2);

    /** 公开提交无登录态，审计操作人固定为 anonymous。 */
    private static final String OPERATOR_ANONYMOUS = "anonymous";

    /** 管理端列表单页上限。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final PlatformLeadMapper platformLeadMapper;
    private final SysUserMapper sysUserMapper;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /**
     * 创建留资线索（初始状态 pending）。
     *
     * @param request 留资请求
     * @return 线索 ID 与初始状态
     */
    @Transactional
    public LeadCreateResponse createLead(LeadCreateRequest request) {
        if (!VALID_SOURCES.contains(request.getSource())) {
            throw new BusinessException("非法的留资来源: " + request.getSource());
        }
        PlatformLead lead = new PlatformLead();
        lead.setLeadId(IdGenerator.generate("LEAD"));
        lead.setName(request.getName().trim());
        lead.setPhone(request.getPhone().trim());
        lead.setSource(request.getSource());
        lead.setIntent(request.getIntent());
        lead.setBudget(request.getBudget());
        lead.setStatus(PlatformLead.STATUS_PENDING);
        lead.setCreatedAt(LocalDateTime.now());
        lead.setUpdatedAt(LocalDateTime.now());
        platformLeadMapper.insert(lead);
        auditLogService.logCreate("platform_lead", lead.getLeadId(), lead, OPERATOR_ANONYMOUS);

        LeadCreateResponse response = new LeadCreateResponse();
        response.setLeadId(lead.getLeadId());
        response.setStatus(lead.getStatus());
        return response;
    }

    /**
     * 管理端线索分页列表（手机号脱敏）。
     *
     * @param status 状态筛选（可选）
     * @param source 来源筛选（可选）
     * @param page   页码（从 1 开始）
     * @param size   每页条数（上限 100）
     * @return 分页线索列表
     */
    public PageResult<LeadListItemResponse> listLeads(String status, String source, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        QueryWrapper<PlatformLead> wrapper = new QueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq("status", status.trim());
        }
        if (StringUtils.hasText(source)) {
            wrapper.eq("source", source.trim());
        }
        wrapper.orderByDesc("created_at");
        Page<PlatformLead> pageResult = platformLeadMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        List<LeadListItemResponse> rows = pageResult.getRecords().stream()
            .map(this::toListItem)
            .toList();
        return PageResult.of(pageResult.getTotal(), safePage, safeSize, rows);
    }

    /**
     * 来源分布统计（三来源各自总数 + 待跟进数）。
     *
     * @return 来源分布
     */
    public LeadSourceStatsResponse sourceStats() {
        LeadSourceStatsResponse stats = new LeadSourceStatsResponse();
        stats.setAiMatch(countBySource(PlatformLead.SOURCE_AI_MATCH));
        stats.setSiteForm(countBySource(PlatformLead.SOURCE_SITE_FORM));
        stats.setDesignBooking(countBySource(PlatformLead.SOURCE_DESIGN_BOOKING));
        stats.setPending(platformLeadMapper.selectCount(
            new QueryWrapper<PlatformLead>().eq("status", PlatformLead.STATUS_PENDING)));
        return stats;
    }

    /**
     * 跟进人候选：平台运营角色（ADMIN/EDITOR）的启用用户。
     *
     * @return 候选人列表
     */
    public List<LeadAssigneeResponse> listAssignees() {
        return sysUserMapper.selectPlatformOperators().stream()
            .map(user -> {
                LeadAssigneeResponse item = new LeadAssigneeResponse();
                item.setUsername(user.getUsername());
                item.setNickname(user.getNickname());
                return item;
            }).toList();
    }

    /**
     * 分配跟进人。
     *
     * @param leadId   线索 ID
     * @param assignee 跟进人用户名（须为存在的启用用户）
     * @return 更新后的列表项
     */
    @Transactional
    public LeadListItemResponse assign(String leadId, String assignee) {
        PlatformLead lead = requireLead(leadId);
        SysUser operator = sysUserMapper.selectByUsername(assignee);
        if (operator == null || !"active".equals(operator.getStatus())) {
            throw new BusinessException("跟进人不存在或已停用: " + assignee);
        }
        PlatformLead oldSnapshot = copyLead(lead);
        lead.setAssignee(assignee);
        lead.setUpdatedAt(LocalDateTime.now());
        platformLeadMapper.updateById(lead);
        auditLogService.logUpdate("platform_lead", leadId, oldSnapshot, lead, currentOperator());
        return toListItem(lead);
    }

    /**
     * 追加跟进记录（follow_log JSON 数组追加 {time, operator, content}）。
     *
     * @param leadId  线索 ID
     * @param content 跟进内容
     * @return 更新后的列表项
     */
    @Transactional
    public LeadListItemResponse appendFollowLog(String leadId, String content) {
        PlatformLead lead = requireLead(leadId);
        PlatformLead oldSnapshot = copyLead(lead);
        lead.setFollowLog(appendFollowLogJson(lead.getFollowLog(), content, currentOperator()));
        lead.setUpdatedAt(LocalDateTime.now());
        platformLeadMapper.updateById(lead);
        auditLogService.logUpdate("platform_lead", leadId, oldSnapshot, lead, currentOperator());
        return toListItem(lead);
    }

    /**
     * 状态流转（仅允许向前：pending → contacted → done）。
     *
     * @param leadId       线索 ID
     * @param targetStatus 目标状态
     * @return 更新后的列表项
     */
    @Transactional
    public LeadListItemResponse updateStatus(String leadId, String targetStatus) {
        PlatformLead lead = requireLead(leadId);
        Integer currentOrder = STATUS_ORDER.get(lead.getStatus());
        Integer targetOrder = STATUS_ORDER.get(targetStatus);
        if (targetOrder == null) {
            throw new BusinessException("非法的线索状态: " + targetStatus);
        }
        if (currentOrder == null || targetOrder <= currentOrder) {
            throw new BusinessException(
                "线索状态仅允许向前流转（pending → contacted → done），当前状态: " + lead.getStatus());
        }
        PlatformLead oldSnapshot = copyLead(lead);
        lead.setStatus(targetStatus);
        lead.setUpdatedAt(LocalDateTime.now());
        platformLeadMapper.updateById(lead);
        auditLogService.logUpdate("platform_lead", leadId, oldSnapshot, lead, currentOperator());
        return toListItem(lead);
    }

    private Long countBySource(String source) {
        return platformLeadMapper.selectCount(
            new QueryWrapper<PlatformLead>().eq("source", source));
    }

    private PlatformLead requireLead(String leadId) {
        PlatformLead lead = platformLeadMapper.selectById(leadId);
        if (lead == null) {
            throw new ResourceNotFoundException("留资线索不存在: " + leadId);
        }
        return lead;
    }

    private LeadListItemResponse toListItem(PlatformLead lead) {
        LeadListItemResponse item = new LeadListItemResponse();
        item.setLeadId(lead.getLeadId());
        item.setName(lead.getName());
        item.setPhoneMasked(maskPhone(lead.getPhone()));
        item.setSource(lead.getSource());
        item.setIntent(lead.getIntent());
        item.setBudget(lead.getBudget());
        item.setStatus(lead.getStatus());
        item.setAssignee(lead.getAssignee());
        item.setFollowLogCount(followLogCount(lead.getFollowLog()));
        item.setCreatedAt(lead.getCreatedAt());
        return item;
    }

    /**
     * 手机号脱敏：保留前 3 位与后 4 位（如 138****6621）；过短号码仅保留前 2 位。
     */
    static String maskPhone(String phone) {
        if (!StringUtils.hasText(phone)) {
            return phone;
        }
        String p = phone.trim();
        if (p.length() >= 8) {
            return p.substring(0, 3) + "****" + p.substring(p.length() - 4);
        }
        if (p.length() >= 4) {
            return p.substring(0, 2) + "****";
        }
        return "****";
    }

    private String appendFollowLogJson(String existingJson, String content, String operator) {
        ArrayNode logs;
        try {
            logs = StringUtils.hasText(existingJson)
                ? (ArrayNode) objectMapper.readTree(existingJson)
                : objectMapper.createArrayNode();
        } catch (Exception e) {
            // 历史数据异常时从空数组重新开始，不阻断跟进记录
            logs = objectMapper.createArrayNode();
        }
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("time", LocalDateTime.now().toString());
        entry.put("operator", operator);
        entry.put("content", content);
        logs.add(entry);
        return logs.toString();
    }

    private int followLogCount(String followLogJson) {
        if (!StringUtils.hasText(followLogJson)) {
            return 0;
        }
        try {
            return objectMapper.readTree(followLogJson).size();
        } catch (Exception e) {
            return 0;
        }
    }

    private PlatformLead copyLead(PlatformLead source) {
        PlatformLead copy = new PlatformLead();
        org.springframework.beans.BeanUtils.copyProperties(source, copy);
        return copy;
    }

    private String currentOperator() {
        String username = SecurityOperatorContext.currentUsername();
        return StringUtils.hasText(username) ? username : "system";
    }
}
