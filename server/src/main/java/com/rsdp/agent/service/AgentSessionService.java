package com.rsdp.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.agent.dto.AgentMessageResponse;
import com.rsdp.agent.dto.AgentSessionDetailResponse;
import com.rsdp.agent.dto.AgentSessionResponse;
import com.rsdp.agent.dto.ConfirmedItemResponse;
import com.rsdp.agent.dto.RequirementProfileResponse;
import com.rsdp.agent.entity.AgentConfirmedItem;
import com.rsdp.agent.entity.AgentMessage;
import com.rsdp.agent.entity.AgentRequirementVersion;
import com.rsdp.agent.entity.AgentSession;
import com.rsdp.agent.mapper.AgentConfirmedItemMapper;
import com.rsdp.agent.mapper.AgentRequirementVersionMapper;
import com.rsdp.agent.mapper.AgentSessionMapper;
import com.rsdp.agent.patch.RequirementConstraints;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 营销 Agent 会话业务服务：会话 CRUD 与详情装配。
 *
 * <p>数据隔离：非平台员工仅可见 created_by = 自己 或 customer_user_id = 自己 的会话；
 * 平台员工（ADMIN/EDITOR）可见全部。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSessionService {

    private final AgentSessionMapper sessionMapper;
    private final AgentRequirementVersionMapper requirementVersionMapper;
    private final AgentConfirmedItemMapper confirmedItemMapper;
    private final AgentMessageStore messageStore;
    private final ObjectMapper objectMapper;

    /**
     * 创建会话（created_by = 当前用户）。
     *
     * @param customerName 代录客户名（可空）
     * @return 新会话
     */
    public AgentSessionResponse create(String customerName) {
        String userId = currentUserIdRequired();
        AgentSession session = new AgentSession();
        session.setSessionId(IdGenerator.generate("SES"));
        session.setCreatedBy(userId);
        session.setCustomerName(StringUtils.hasText(customerName) ? customerName.trim() : null);
        session.setStatus("active");
        session.setCurrentVersionNo(0);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(session);
        return toResponse(session);
    }

    /**
     * 查询当前用户可见的会话列表（updated_at 倒序；平台员工可见全部）。
     *
     * @return 会话列表
     */
    public List<AgentSessionResponse> listMine() {
        QueryWrapper<AgentSession> wrapper = new QueryWrapper<>();
        if (!SecurityOperatorContext.isPlatformStaff()) {
            // 平台员工可见全部，无需解析 userId；其余角色按 actor/subject 过滤
            String userId = currentUserIdRequired();
            wrapper.and(w -> w.eq("created_by", userId).or().eq("customer_user_id", userId));
        }
        wrapper.orderByDesc("updated_at");
        return sessionMapper.selectList(wrapper).stream().map(this::toResponse).toList();
    }

    /**
     * 会话详情：会话 + 消息 + 当前需求档案 + 已确认清单。
     *
     * @param sessionId 会话 ID
     * @return 详情
     */
    public AgentSessionDetailResponse getDetail(String sessionId) {
        AgentSession session = requireAccessibleSession(sessionId);

        AgentSessionDetailResponse detail = new AgentSessionDetailResponse();
        detail.setSession(toResponse(session));
        detail.setMessages(messageStore.listBySession(sessionId).stream()
            .map(this::toMessageResponse)
            .toList());

        AgentRequirementVersion latest = latestRequirementVersion(sessionId);
        detail.setRequirement(latest == null ? null : toProfile(latest));
        detail.setConfirmedItems(listConfirmedItems(sessionId));
        return detail;
    }

    /**
     * 结束会话。存在 running run（active_run_id 非空）时返回 409。
     *
     * @param sessionId 会话 ID
     * @return 更新后的会话
     */
    public AgentSessionResponse close(String sessionId) {
        AgentSession session = requireAccessibleSession(sessionId);
        if (StringUtils.hasText(session.getActiveRunId())) {
            throw new BusinessException(409, "SESSION_BUSY");
        }
        session.setStatus("closed");
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
        return toResponse(session);
    }

    /**
     * 加载会话并校验当前用户访问权限。
     *
     * @param sessionId 会话 ID
     * @return 会话实体
     * @throws ResourceNotFoundException 会话不存在
     * @throws BusinessException         无权访问（403）
     */
    public AgentSession requireAccessibleSession(String sessionId) {
        AgentSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new ResourceNotFoundException("会话不存在: " + sessionId);
        }
        if (!SecurityOperatorContext.isPlatformStaff()) {
            String userId = currentUserIdRequired();
            boolean accessible = userId.equals(session.getCreatedBy())
                || userId.equals(session.getCustomerUserId());
            if (!accessible) {
                throw new BusinessException(403, "无权访问该会话");
            }
        }
        return session;
    }

    /** 最新需求版本（无则 null）。 */
    public AgentRequirementVersion latestRequirementVersion(String sessionId) {
        return requirementVersionMapper.selectOne(new QueryWrapper<AgentRequirementVersion>()
            .eq("session_id", sessionId)
            .orderByDesc("version_no")
            .last("LIMIT 1"));
    }

    /** 版本实体 → 需求档案契约结构。 */
    public RequirementProfileResponse toProfile(AgentRequirementVersion version) {
        return new RequirementProfileResponse(
            version.getVersionNo(),
            RequirementConstraints.fromJson(version.getConstraints()),
            version.getSource());
    }

    /** 会话确认清单（created_at 升序）。 */
    public List<ConfirmedItemResponse> listConfirmedItems(String sessionId) {
        return confirmedItemMapper.selectList(new QueryWrapper<AgentConfirmedItem>()
                .eq("session_id", sessionId)
                .orderByAsc("created_at"))
            .stream().map(this::toConfirmedResponse).toList();
    }

    /** 确认条目实体 → 契约结构（spec 解析为对象，productName 取自 spec）。 */
    @SuppressWarnings("unchecked")
    public ConfirmedItemResponse toConfirmedResponse(AgentConfirmedItem item) {
        ConfirmedItemResponse response = new ConfirmedItemResponse();
        response.setItemId(item.getItemId());
        response.setRspuId(item.getRspuId());
        response.setQuantity(item.getQuantity());
        response.setStatus(item.getStatus());
        response.setCreatedAt(item.getCreatedAt());
        Map<String, Object> spec = parseMap(item.getSpec());
        response.setSpec(spec);
        Object productName = spec != null ? spec.get("productName") : null;
        response.setProductName(productName != null ? String.valueOf(productName) : null);
        return response;
    }

    private AgentSessionResponse toResponse(AgentSession session) {
        AgentSessionResponse response = new AgentSessionResponse();
        response.setSessionId(session.getSessionId());
        // 客户视角同样返回 customerName（契约内字段，无内部敏感信息）
        response.setCustomerName(session.getCustomerName());
        response.setStatus(session.getStatus());
        response.setCurrentVersionNo(session.getCurrentVersionNo());
        response.setSummary(session.getSummary());
        response.setCreatedAt(session.getCreatedAt());
        response.setUpdatedAt(session.getUpdatedAt());
        return response;
    }

    private AgentMessageResponse toMessageResponse(AgentMessage message) {
        AgentMessageResponse response = new AgentMessageResponse();
        response.setMessageId(message.getMessageId());
        response.setRole(message.getRole());
        response.setMessageType(message.getMessageType());
        response.setContent(message.getContent());
        response.setMetadata(parseJson(message.getMetadata()));
        response.setSequenceNo(message.getSequenceNo());
        response.setCreatedAt(message.getCreatedAt());
        return response;
    }

    private Object parseJson(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("消息 metadata JSON 解析失败，按 null 返回");
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(String json) {
        Object parsed = parseJson(json);
        return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private String currentUserIdRequired() {
        String userId = SecurityOperatorContext.currentUserId();
        if (!StringUtils.hasText(userId)) {
            throw new BusinessException("无法获取当前用户 ID");
        }
        return userId;
    }
}
