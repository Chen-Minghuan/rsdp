package com.rsdp.service;

import com.rsdp.dto.request.LeadCreateRequest;
import com.rsdp.dto.response.LeadCreateResponse;
import com.rsdp.entity.PlatformLead;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.PlatformLeadMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link PlatformLeadService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class PlatformLeadServiceTest {

    @Mock
    private PlatformLeadMapper platformLeadMapper;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private PlatformLeadService platformLeadService;

    private LeadCreateRequest validRequest() {
        LeadCreateRequest request = new LeadCreateRequest();
        request.setName("王女士");
        request.setPhone("13800006621");
        request.setSource("site_form");
        request.setIntent("客厅整配");
        request.setBudget("2万");
        return request;
    }

    @Test
    void createLead_shouldAssignIdAndPendingStatus() {
        LeadCreateResponse response = platformLeadService.createLead(validRequest());

        ArgumentCaptor<PlatformLead> captor = ArgumentCaptor.forClass(PlatformLead.class);
        verify(platformLeadMapper).insert(captor.capture());
        PlatformLead saved = captor.getValue();
        assertThat(saved.getLeadId()).startsWith("LEAD-");
        assertThat(saved.getStatus()).isEqualTo("pending");
        assertThat(saved.getName()).isEqualTo("王女士");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(response.getLeadId()).isEqualTo(saved.getLeadId());
        verify(auditLogService).logCreate(eq("platform_lead"), eq(saved.getLeadId()), any(), eq("anonymous"));
    }

    @Test
    void createLead_allValidSources_shouldPass() {
        for (String source : new String[] {"ai_match", "site_form", "design_booking"}) {
            LeadCreateRequest request = validRequest();
            request.setSource(source);
            LeadCreateResponse response = platformLeadService.createLead(request);
            assertThat(response.getStatus()).isEqualTo("pending");
        }
    }

    @Test
    void createLead_invalidSource_shouldThrow() {
        LeadCreateRequest request = validRequest();
        request.setSource("hack");

        assertThatThrownBy(() -> platformLeadService.createLead(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("非法的留资来源");
        verify(platformLeadMapper, never()).insert(any(PlatformLead.class));
        verify(auditLogService, never()).logCreate(anyString(), anyString(), any(), anyString());
    }
}
