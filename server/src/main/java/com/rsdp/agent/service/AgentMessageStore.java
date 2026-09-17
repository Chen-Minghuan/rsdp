package com.rsdp.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.agent.entity.AgentMessage;
import com.rsdp.agent.mapper.AgentMessageMapper;
import com.rsdp.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * agent_message 持久化助手：会话内序号分配与各类消息落库。
 *
 * <p>sequence_no 取会话内 max+1；同一会话同一时刻至多一个 running run
 * （active_run_id 并发认领保证），因此分配无并发竞争。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentMessageStore {

    private final AgentMessageMapper messageMapper;

    /** 持久化用户消息（client_message_id 幂等由调用方保证/依赖唯一索引）。 */
    public AgentMessage persistUserMessage(String sessionId, String clientMessageId, String content) {
        AgentMessage message = new AgentMessage();
        message.setMessageId(IdGenerator.generate("MSG"));
        message.setSessionId(sessionId);
        message.setClientMessageId(clientMessageId);
        message.setRole("user");
        message.setMessageType("text");
        message.setContent(content);
        message.setSequenceNo(nextSequence(sessionId));
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
        return message;
    }

    /**
     * 持久化 assistant 消息。
     *
     * @param messageType text/cards/requirement/notice
     * @param metadata    附加数据 JSON（可空，须为合法 JSON 文本）
     */
    public AgentMessage persistAssistantMessage(String sessionId, String runId, String messageType,
                                                String content, String metadata) {
        AgentMessage message = new AgentMessage();
        message.setMessageId(IdGenerator.generate("MSG"));
        message.setSessionId(sessionId);
        message.setRunId(runId);
        message.setRole("assistant");
        message.setMessageType(messageType);
        message.setContent(content);
        message.setMetadata(metadata);
        message.setSequenceNo(nextSequence(sessionId));
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
        return message;
    }

    /** 按客户端幂等 ID 查找用户消息。 */
    public AgentMessage findByClientMessageId(String sessionId, String clientMessageId) {
        return messageMapper.selectOne(new QueryWrapper<AgentMessage>()
            .eq("session_id", sessionId)
            .eq("client_message_id", clientMessageId)
            .last("LIMIT 1"));
    }

    /** 找某条消息之后的首条 assistant 文本类消息（text/notice，幂等重放用）。 */
    public AgentMessage findNextAssistantMessage(String sessionId, Long afterSequenceNo) {
        return messageMapper.selectOne(new QueryWrapper<AgentMessage>()
            .eq("session_id", sessionId)
            .eq("role", "assistant")
            .in("message_type", "text", "notice")
            .gt("sequence_no", afterSequenceNo)
            .orderByAsc("sequence_no")
            .last("LIMIT 1"));
    }

    /** 会话全部消息（序号升序）。 */
    public List<AgentMessage> listBySession(String sessionId) {
        return messageMapper.selectList(new QueryWrapper<AgentMessage>()
            .eq("session_id", sessionId)
            .orderByAsc("sequence_no"));
    }

    /** 会话内下一个消息序号。 */
    public long nextSequence(String sessionId) {
        AgentMessage latest = messageMapper.selectOne(new QueryWrapper<AgentMessage>()
            .eq("session_id", sessionId)
            .orderByDesc("sequence_no")
            .last("LIMIT 1"));
        return latest == null || latest.getSequenceNo() == null ? 1L : latest.getSequenceNo() + 1L;
    }
}
