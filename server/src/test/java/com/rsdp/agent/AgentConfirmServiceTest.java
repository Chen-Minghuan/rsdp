package com.rsdp.agent;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.agent.dto.ConfirmItemRequest;
import com.rsdp.agent.dto.ConfirmedItemResponse;
import com.rsdp.agent.entity.AgentConfirmedItem;
import com.rsdp.agent.entity.AgentRecommendBatch;
import com.rsdp.agent.entity.AgentRecommendItem;
import com.rsdp.agent.entity.AgentSession;
import com.rsdp.agent.mapper.AgentConfirmedItemMapper;
import com.rsdp.agent.mapper.AgentRecommendBatchMapper;
import com.rsdp.agent.mapper.AgentRecommendItemMapper;
import com.rsdp.agent.service.AgentConfirmService;
import com.rsdp.agent.service.AgentSessionService;
import com.rsdp.exception.BusinessException;
import com.rsdp.security.SecurityOperatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentConfirmService} 单元测试（确认落库 + 幂等键守卫）。
 */
@ExtendWith(MockitoExtension.class)
class AgentConfirmServiceTest {

    @Mock
    private AgentSessionService sessionService;

    @Mock
    private AgentConfirmedItemMapper confirmedItemMapper;

    @Mock
    private AgentRecommendItemMapper recommendItemMapper;

    @Mock
    private AgentRecommendBatchMapper recommendBatchMapper;

    @InjectMocks
    private AgentConfirmService confirmService;

    private AgentSession activeSession(String sessionId) {
        AgentSession session = new AgentSession();
        session.setSessionId(sessionId);
        session.setStatus("active");
        return session;
    }

    private ConfirmItemRequest request() {
        ConfirmItemRequest request = new ConfirmItemRequest();
        request.setRecommendItemId("RI-1");
        request.setQuantity(2);
        request.setIdempotencyKey("idem-123");
        return request;
    }

    private AgentRecommendItem recommendItem() {
        AgentRecommendItem item = new AgentRecommendItem();
        item.setItemId("RI-1");
        item.setBatchId("RB-1");
        item.setRspuId("RSPU-9");
        item.setSnapshot("{\"productName\":\"云朵沙发\"}");
        return item;
    }

    private AgentRecommendBatch batchOf(String sessionId) {
        AgentRecommendBatch batch = new AgentRecommendBatch();
        batch.setBatchId("RB-1");
        batch.setSessionId(sessionId);
        return batch;
    }

    private ConfirmedItemResponse echoResponse(AgentConfirmedItem item) {
        ConfirmedItemResponse response = new ConfirmedItemResponse();
        response.setItemId(item.getItemId());
        response.setRspuId(item.getRspuId());
        response.setQuantity(item.getQuantity());
        response.setStatus(item.getStatus());
        return response;
    }

    @Test
    void confirmShouldPersistWithRspuIdFromRecommendItem() {
        when(sessionService.requireAccessibleSession("SES-1")).thenReturn(activeSession("SES-1"));
        when(confirmedItemMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(recommendItemMapper.selectById("RI-1")).thenReturn(recommendItem());
        when(recommendBatchMapper.selectById("RB-1")).thenReturn(batchOf("SES-1"));
        when(sessionService.toConfirmedResponse(any(AgentConfirmedItem.class)))
            .thenAnswer(inv -> echoResponse(inv.getArgument(0)));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            ConfirmedItemResponse response = confirmService.confirm("SES-1", request());

            ArgumentCaptor<AgentConfirmedItem> captor = ArgumentCaptor.forClass(AgentConfirmedItem.class);
            verify(confirmedItemMapper).insert(captor.capture());
            AgentConfirmedItem saved = captor.getValue();
            assertThat(saved.getItemId()).startsWith("CFI-");
            assertThat(saved.getSessionId()).isEqualTo("SES-1");
            // rspuId 与 spec 快照均取自 recommend_item，不信任客户端传值
            assertThat(saved.getRspuId()).isEqualTo("RSPU-9");
            assertThat(saved.getSpec()).isEqualTo("{\"productName\":\"云朵沙发\"}");
            assertThat(saved.getRecommendItemId()).isEqualTo("RI-1");
            assertThat(saved.getQuantity()).isEqualTo(2);
            assertThat(saved.getStatus()).isEqualTo("confirmed");
            assertThat(saved.getIdempotencyKey()).isEqualTo("idem-123");
            assertThat(saved.getConfirmedBy()).isEqualTo("user-1");
            assertThat(response.getRspuId()).isEqualTo("RSPU-9");
        }
    }

    @Test
    void duplicateIdempotencyKeyShouldReturnExistingWithoutInsert() {
        when(sessionService.requireAccessibleSession("SES-1")).thenReturn(activeSession("SES-1"));
        AgentConfirmedItem existing = new AgentConfirmedItem();
        existing.setItemId("CFI-old");
        existing.setSessionId("SES-1");
        existing.setRspuId("RSPU-9");
        existing.setIdempotencyKey("idem-123");
        when(confirmedItemMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);
        ConfirmedItemResponse existingResponse = echoResponse(existing);
        when(sessionService.toConfirmedResponse(existing)).thenReturn(existingResponse);

        ConfirmedItemResponse response = confirmService.confirm("SES-1", request());

        assertThat(response.getItemId()).isEqualTo("CFI-old");
        // 幂等命中：不重复插入，也不再校验推荐条目
        verify(confirmedItemMapper, never()).insert(any(AgentConfirmedItem.class));
        verify(recommendItemMapper, never()).selectById(any());
    }

    @Test
    void insertConflictOnSameKeyShouldFallbackToExistingRecord() {
        when(sessionService.requireAccessibleSession("SES-1")).thenReturn(activeSession("SES-1"));
        AgentConfirmedItem conflicted = new AgentConfirmedItem();
        conflicted.setItemId("CFI-race");
        conflicted.setSessionId("SES-1");
        conflicted.setRspuId("RSPU-9");
        // 先查没有，插入撞唯一键，重查命中（并发兜底）
        when(confirmedItemMapper.selectOne(any(QueryWrapper.class))).thenReturn(null, conflicted);
        when(recommendItemMapper.selectById("RI-1")).thenReturn(recommendItem());
        when(recommendBatchMapper.selectById("RB-1")).thenReturn(batchOf("SES-1"));
        when(confirmedItemMapper.insert(any(AgentConfirmedItem.class)))
            .thenThrow(new DuplicateKeyException("duplicate idempotency_key"));
        when(sessionService.toConfirmedResponse(conflicted)).thenReturn(echoResponse(conflicted));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            ConfirmedItemResponse response = confirmService.confirm("SES-1", request());

            assertThat(response.getItemId()).isEqualTo("CFI-race");
        }
    }

    @Test
    void idempotencyKeyUsedByOtherSessionShouldBeRejected() {
        // 会话归属校验：会话 A 的幂等键带到会话 B 不得返回"成功"
        when(sessionService.requireAccessibleSession("SES-2")).thenReturn(activeSession("SES-2"));
        AgentConfirmedItem existing = new AgentConfirmedItem();
        existing.setItemId("CFI-old");
        existing.setSessionId("SES-1");
        existing.setIdempotencyKey("idem-123");
        when(confirmedItemMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        assertThatThrownBy(() -> confirmService.confirm("SES-2", request()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("幂等键已被其他会话使用");
        verify(confirmedItemMapper, never()).insert(any(AgentConfirmedItem.class));
    }

    @Test
    void recommendItemOfOtherSessionShouldBeRejected() {
        when(sessionService.requireAccessibleSession("SES-1")).thenReturn(activeSession("SES-1"));
        when(confirmedItemMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(recommendItemMapper.selectById("RI-1")).thenReturn(recommendItem());
        when(recommendBatchMapper.selectById("RB-1")).thenReturn(batchOf("SES-OTHER"));

        assertThatThrownBy(() -> confirmService.confirm("SES-1", request()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不属于当前会话");
        verify(confirmedItemMapper, never()).insert(any(AgentConfirmedItem.class));
    }

    @Test
    void missingRecommendItemShouldBeRejected() {
        when(sessionService.requireAccessibleSession("SES-1")).thenReturn(activeSession("SES-1"));
        when(confirmedItemMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(recommendItemMapper.selectById("RI-1")).thenReturn(null);

        assertThatThrownBy(() -> confirmService.confirm("SES-1", request()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("推荐条目不存在");
        verify(confirmedItemMapper, never()).insert(any(AgentConfirmedItem.class));
    }

    @Test
    void closedSessionShouldBeRejected() {
        AgentSession closed = activeSession("SES-1");
        closed.setStatus("closed");
        when(sessionService.requireAccessibleSession("SES-1")).thenReturn(closed);

        assertThatThrownBy(() -> confirmService.confirm("SES-1", request()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("会话已关闭");
        verify(confirmedItemMapper, never()).insert(any(AgentConfirmedItem.class));
    }
}
