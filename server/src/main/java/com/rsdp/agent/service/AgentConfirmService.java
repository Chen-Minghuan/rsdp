package com.rsdp.agent.service;

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
import com.rsdp.exception.BusinessException;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 营销 Agent 主体产品确认服务。
 *
 * <p>幂等：idempotency_key 全库唯一，重复提交返回已存在记录（先查后插 +
 * 唯一冲突兜底重查）。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentConfirmService {

    private final AgentSessionService sessionService;
    private final AgentConfirmedItemMapper confirmedItemMapper;
    private final AgentRecommendItemMapper recommendItemMapper;
    private final AgentRecommendBatchMapper recommendBatchMapper;

    /**
     * 确认主体产品。
     *
     * @param sessionId 会话 ID
     * @param request   确认请求
     * @return 已确认项（幂等命中时返回已存在记录）
     */
    @Transactional
    public ConfirmedItemResponse confirm(String sessionId, ConfirmItemRequest request) {
        AgentSession session = sessionService.requireAccessibleSession(sessionId);
        if (!"active".equals(session.getStatus())) {
            throw new BusinessException("会话已关闭，无法确认产品");
        }

        // 幂等：已存在直接返回，但必须属于当前会话（防止把会话 A 的幂等键带到会话 B 拿到"成功"）
        AgentConfirmedItem existing = findByIdempotencyKey(request.getIdempotencyKey());
        if (existing != null) {
            if (!sessionId.equals(existing.getSessionId())) {
                throw new BusinessException(409, "幂等键已被其他会话使用");
            }
            return sessionService.toConfirmedResponse(existing);
        }

        // 校验推荐条目属于该会话的推荐批次
        AgentRecommendItem recommendItem = recommendItemMapper.selectById(request.getRecommendItemId());
        if (recommendItem == null) {
            throw new BusinessException("推荐条目不存在: " + request.getRecommendItemId());
        }
        AgentRecommendBatch batch = recommendBatchMapper.selectById(recommendItem.getBatchId());
        if (batch == null || !sessionId.equals(batch.getSessionId())) {
            throw new BusinessException("推荐条目不属于当前会话");
        }

        AgentConfirmedItem item = new AgentConfirmedItem();
        item.setItemId(IdGenerator.generate("CFI"));
        item.setSessionId(sessionId);
        item.setRecommendItemId(recommendItem.getItemId());
        item.setRspuId(recommendItem.getRspuId());
        // spec = 推荐时刻的产品快照（颜色/尺寸/价格等事实口径以快照为准）
        item.setSpec(recommendItem.getSnapshot());
        item.setQuantity(request.getQuantity());
        item.setStatus("confirmed");
        item.setIdempotencyKey(request.getIdempotencyKey());
        item.setConfirmedBy(SecurityOperatorContext.currentUserId());
        item.setCreatedAt(LocalDateTime.now());
        try {
            confirmedItemMapper.insert(item);
        } catch (DuplicateKeyException e) {
            // 并发下同幂等键插入冲突：返回已存在记录（同上会话归属校验）
            AgentConfirmedItem conflicted = findByIdempotencyKey(request.getIdempotencyKey());
            if (conflicted != null) {
                if (!sessionId.equals(conflicted.getSessionId())) {
                    throw new BusinessException(409, "幂等键已被其他会话使用");
                }
                return sessionService.toConfirmedResponse(conflicted);
            }
            throw e;
        }
        return sessionService.toConfirmedResponse(item);
    }

    private AgentConfirmedItem findByIdempotencyKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return null;
        }
        return confirmedItemMapper.selectOne(new QueryWrapper<AgentConfirmedItem>()
            .eq("idempotency_key", idempotencyKey)
            .last("LIMIT 1"));
    }
}
