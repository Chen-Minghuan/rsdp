package com.rsdp.service;

import com.rsdp.dto.request.LeadCreateRequest;
import com.rsdp.dto.response.LeadCreateResponse;
import com.rsdp.dto.response.LeadListItemResponse;
import com.rsdp.dto.response.LeadSourceStatsResponse;
import com.rsdp.entity.PlatformLead;
import com.rsdp.entity.SysUser;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.PlatformLeadMapper;
import com.rsdp.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PlatformLeadService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class PlatformLeadServiceTest {

    @Mock
    private PlatformLeadMapper platformLeadMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private AuditLogService auditLogService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

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

    // ---------- 管理端：列表 / 统计 / 分配 / 跟进 / 状态流转 ----------

    private PlatformLead sampleLead() {
        PlatformLead lead = new PlatformLead();
        lead.setLeadId("LEAD-1");
        lead.setName("王女士");
        lead.setPhone("13800006621");
        lead.setSource("site_form");
        lead.setStatus(PlatformLead.STATUS_PENDING);
        return lead;
    }

    @Test
    void listLeads_shouldMaskPhone() {
        Page<PlatformLead> page = new Page<>(1, 10);
        page.setRecords(List.of(sampleLead()));
        page.setTotal(1);
        when(platformLeadMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);

        var result = platformLeadService.listLeads(null, null, 1, 10);

        assertThat(result.getTotal()).isEqualTo(1);
        LeadListItemResponse item = result.getRows().get(0);
        assertThat(item.getPhoneMasked()).isEqualTo("138****6621");
        assertThat(item.getFollowLogCount()).isEqualTo(0);
    }

    @Test
    void sourceStats_shouldAggregateBySourceAndPending() {
        when(platformLeadMapper.selectCount(any(QueryWrapper.class)))
            .thenReturn(3L)   // ai_match
            .thenReturn(5L)   // site_form
            .thenReturn(2L)   // design_booking
            .thenReturn(7L);  // pending

        LeadSourceStatsResponse stats = platformLeadService.sourceStats();

        assertThat(stats.getAiMatch()).isEqualTo(3L);
        assertThat(stats.getSiteForm()).isEqualTo(5L);
        assertThat(stats.getDesignBooking()).isEqualTo(2L);
        assertThat(stats.getPending()).isEqualTo(7L);
    }

    @Test
    void assign_shouldSetAssigneeAndAudit() {
        when(platformLeadMapper.selectById("LEAD-1")).thenReturn(sampleLead());
        SysUser operator = new SysUser();
        operator.setUsername("admin");
        operator.setStatus("active");
        when(sysUserMapper.selectByUsername("admin")).thenReturn(operator);

        LeadListItemResponse item = platformLeadService.assign("LEAD-1", "admin");

        assertThat(item.getAssignee()).isEqualTo("admin");
        verify(platformLeadMapper).updateById(any(PlatformLead.class));
        verify(auditLogService).logUpdate(eq("platform_lead"), eq("LEAD-1"), any(), any(), anyString());
    }

    @Test
    void assign_unknownAssignee_shouldThrow() {
        when(platformLeadMapper.selectById("LEAD-1")).thenReturn(sampleLead());
        when(sysUserMapper.selectByUsername("ghost")).thenReturn(null);

        assertThatThrownBy(() -> platformLeadService.assign("LEAD-1", "ghost"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("跟进人不存在");
        verify(platformLeadMapper, never()).updateById(any(PlatformLead.class));
    }

    @Test
    void appendFollowLog_shouldAppendJsonEntry() {
        PlatformLead lead = sampleLead();
        lead.setFollowLog("[{\"time\":\"2026-08-10T10:00:00\",\"operator\":\"admin\",\"content\":\"首次电话\"}]");
        when(platformLeadMapper.selectById("LEAD-1")).thenReturn(lead);

        LeadListItemResponse item = platformLeadService.appendFollowLog("LEAD-1", "二次回访");

        ArgumentCaptor<PlatformLead> captor = ArgumentCaptor.forClass(PlatformLead.class);
        verify(platformLeadMapper).updateById(captor.capture());
        String followLog = captor.getValue().getFollowLog();
        assertThat(followLog).contains("二次回访");
        assertThat(followLog).contains("首次电话");
        assertThat(item.getFollowLogCount()).isEqualTo(2);
    }

    @Test
    void appendFollowLog_emptyExisting_shouldStartNewArray() {
        when(platformLeadMapper.selectById("LEAD-1")).thenReturn(sampleLead());

        LeadListItemResponse item = platformLeadService.appendFollowLog("LEAD-1", "首次跟进");

        assertThat(item.getFollowLogCount()).isEqualTo(1);
    }

    @Test
    void updateStatus_forwardTransition_shouldPass() {
        when(platformLeadMapper.selectById("LEAD-1")).thenReturn(sampleLead());

        LeadListItemResponse item = platformLeadService.updateStatus("LEAD-1", "contacted");

        assertThat(item.getStatus()).isEqualTo("contacted");
        verify(platformLeadMapper).updateById(any(PlatformLead.class));
    }

    @Test
    void updateStatus_backwardTransition_shouldThrow() {
        PlatformLead lead = sampleLead();
        lead.setStatus(PlatformLead.STATUS_CONTACTED);
        when(platformLeadMapper.selectById("LEAD-1")).thenReturn(lead);

        assertThatThrownBy(() -> platformLeadService.updateStatus("LEAD-1", "pending"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("仅允许向前流转");
        verify(platformLeadMapper, never()).updateById(any(PlatformLead.class));
    }

    @Test
    void updateStatus_unknownLead_shouldThrowNotFound() {
        when(platformLeadMapper.selectById("LEAD-9")).thenReturn(null);

        assertThatThrownBy(() -> platformLeadService.updateStatus("LEAD-9", "contacted"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void maskPhone_shouldKeepHeadAndTail() {
        assertThat(PlatformLeadService.maskPhone("13800006621")).isEqualTo("138****6621");
        assertThat(PlatformLeadService.maskPhone("02112345")).isEqualTo("021****2345");
        assertThat(PlatformLeadService.maskPhone("12345")).isEqualTo("12****");
        assertThat(PlatformLeadService.maskPhone("12")).isEqualTo("****");
        assertThat(PlatformLeadService.maskPhone(null)).isNull();
    }
}
