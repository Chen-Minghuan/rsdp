package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.AuditLog;
import com.rsdp.entity.RspuMaster;
import com.rsdp.mapper.AuditLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link AuditLogService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogMapper auditLogMapper;

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        // 构造函数注入真实 ObjectMapper，确保快照脱敏/序列化走真实逻辑
        auditLogService = new AuditLogService(auditLogMapper, new ObjectMapper());
    }

    @Test
    void logCreate_shouldInsertAuditLog() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");
        rspu.setReviewStatus("待复核");

        auditLogService.logCreate("rspu_master", "RSPU-TEST01", rspu, "admin");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper, times(1)).insert(captor.capture());

        AuditLog log = captor.getValue();
        assertThat(log.getTableName()).isEqualTo("rspu_master");
        assertThat(log.getRecordId()).isEqualTo("RSPU-TEST01");
        assertThat(log.getAction()).isEqualTo("CREATE");
        assertThat(log.getOperator()).isEqualTo("admin");
        assertThat(log.getOldValue()).isNull();
        assertThat(log.getNewValue().toString()).contains("RSPU-TEST01");
    }

    @Test
    void logReview_shouldInsertAuditLogWithOldAndNewValue() {
        RspuMaster oldRspu = new RspuMaster();
        oldRspu.setRspuId("RSPU-TEST01");
        oldRspu.setReviewStatus("待复核");

        RspuMaster newRspu = new RspuMaster();
        newRspu.setRspuId("RSPU-TEST01");
        newRspu.setReviewStatus("已确认");

        auditLogService.logReview("rspu_master", "RSPU-TEST01", oldRspu, newRspu, "admin");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper, times(1)).insert(captor.capture());

        AuditLog log = captor.getValue();
        assertThat(log.getAction()).isEqualTo("REVIEW");
        assertThat(log.getOldValue().toString()).contains("待复核");
        assertThat(log.getNewValue().toString()).contains("已确认");
    }

    @Test
    void logDelete_shouldInsertAuditLogWithOldValueOnly() {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-TEST01");

        auditLogService.logDelete("rspu_master", "RSPU-TEST01", rspu, "admin");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper, times(1)).insert(captor.capture());

        AuditLog log = captor.getValue();
        assertThat(log.getAction()).isEqualTo("DELETE");
        assertThat(log.getOldValue().toString()).contains("RSPU-TEST01");
        assertThat(log.getNewValue()).isNull();
    }
}
