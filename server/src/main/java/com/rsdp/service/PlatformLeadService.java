package com.rsdp.service;

import com.rsdp.dto.request.LeadCreateRequest;
import com.rsdp.dto.response.LeadCreateResponse;
import com.rsdp.entity.PlatformLead;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.PlatformLeadMapper;
import com.rsdp.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 官网留资线索服务：用户端公开提交入口（免登录）。
 */
@Service
@RequiredArgsConstructor
public class PlatformLeadService {

    /** 合法来源取值。 */
    private static final Set<String> VALID_SOURCES = Set.of(
        PlatformLead.SOURCE_AI_MATCH,
        PlatformLead.SOURCE_SITE_FORM,
        PlatformLead.SOURCE_DESIGN_BOOKING);

    /** 公开提交无登录态，审计操作人固定为 anonymous。 */
    private static final String OPERATOR_ANONYMOUS = "anonymous";

    private final PlatformLeadMapper platformLeadMapper;
    private final AuditLogService auditLogService;

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
}
