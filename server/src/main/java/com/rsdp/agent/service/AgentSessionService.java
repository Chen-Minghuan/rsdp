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
import com.rsdp.agent.domain.FloorPlanContext;
import com.rsdp.agent.domain.FloorPlanRoomFact;
import com.rsdp.agent.dto.AgentRoomResponse;
import com.rsdp.agent.patch.RequirementConstraints;
import com.rsdp.agent.patch.RequirementScope;
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
    private final FloorPlanContextService floorPlanContextService;
    private final ObjectMapper objectMapper;

    /**
     * 创建会话（created_by = 当前用户）。
     *
     * @param customerName 代录客户名（可空）
     * @return 新会话
     */
    public AgentSessionResponse create(String customerName) {
        return create(customerName, null);
    }

    /**
     * 创建会话（created_by = 当前用户），可绑定已确认户型（W2）。
     *
     * @param customerName       代录客户名（可空）
     * @param floorPlanAnalysisId 已确认户型分析 ID（可空=纯口头描述会话）；
     *                            非空时校验存在 + confirmed + 归属本人（平台员工不受限），
     *                            并默认把首个房间设为当前房间
     * @return 新会话
     */
    public AgentSessionResponse create(String customerName, String floorPlanAnalysisId) {
        String userId = currentUserIdRequired();
        AgentSession session = new AgentSession();
        session.setSessionId(IdGenerator.generate("SES"));
        session.setCreatedBy(userId);
        session.setCustomerName(StringUtils.hasText(customerName) ? customerName.trim() : null);
        session.setStatus("active");
        session.setCurrentVersionNo(0);
        if (StringUtils.hasText(floorPlanAnalysisId)) {
            // floor_plan_analysis.created_by 存的是 username（FloorPlanService 用 currentUsername 落库），
            // 归属校验必须传 username，不能用 userId，否则设计师绑自己的户型也会 403
            floorPlanContextService.requireBindableAnalysis(
                floorPlanAnalysisId.trim(), SecurityOperatorContext.currentUsername(),
                SecurityOperatorContext.isPlatformStaff());
            session.setFloorPlanAnalysisId(floorPlanAnalysisId.trim());
            List<FloorPlanRoomFact> rooms = floorPlanContextService.listRooms(floorPlanAnalysisId.trim());
            if (!rooms.isEmpty()) {
                session.setActiveRoomId(rooms.get(0).roomId());
            }
        }
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(session);
        return toResponse(session);
    }

    /**
     * 切换当前选品房间（W2）：房间必须属于会话绑定的户型。
     *
     * @param sessionId 会话 ID
     * @param roomId    目标房间 ID
     * @return 更新后的会话
     */
    public AgentSessionResponse activateRoom(String sessionId, String roomId) {
        AgentSession session = requireAccessibleSession(sessionId);
        if (!"active".equals(session.getStatus())) {
            throw new BusinessException(400, "会话已关闭");
        }
        if (!StringUtils.hasText(session.getFloorPlanAnalysisId())) {
            throw new BusinessException(400, "会话未绑定户型");
        }
        FloorPlanContext context = floorPlanContextService.loadContext(
            session.getFloorPlanAnalysisId(), null);
        if (context == null || context.roomById(roomId) == null) {
            throw new BusinessException(400, "房间不属于当前户型: " + roomId);
        }
        session.setActiveRoomId(roomId);
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
        return toResponse(session);
    }

    /**
     * 会话绑定户型的房间列表（W2；无户型/户型未确认时返回空列表）。
     *
     * @param session 会话实体
     * @return 房间响应列表（active 标记当前房间）
     */
    public List<AgentRoomResponse> listRooms(AgentSession session) {
        FloorPlanContext context = floorPlanContextService.loadContext(
            session.getFloorPlanAnalysisId(), session.getActiveRoomId());
        if (context == null) {
            return List.of();
        }
        return context.rooms().stream().map(room -> {
            AgentRoomResponse response = new AgentRoomResponse();
            response.setRoomId(room.roomId());
            response.setLabel(room.label());
            response.setRoomType(room.roomType());
            response.setDisplayName(room.displayName());
            response.setWidthMm(room.widthMm());
            response.setDepthMm(room.depthMm());
            response.setAreaM2(room.areaM2());
            response.setActive(room.roomId().equals(session.getActiveRoomId()));
            return response;
        }).toList();
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

        FloorPlanContext floorPlanContext = floorPlanContextService.loadContext(
            session.getFloorPlanAnalysisId(), session.getActiveRoomId());
        detail.setRequirement(effectiveProfile(sessionId,
            floorPlanContext != null ? floorPlanContext.activeRoom() : null));
        detail.setConfirmedItems(listConfirmedItems(sessionId));
        detail.setRooms(listRooms(session));
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
     * 删除会话（软删除：deleted_at 置位，列表/详情不可见；子表数据留痕可溯）。
     * 存在 running run（active_run_id 非空）时返回 409。
     *
     * @param sessionId 会话 ID
     */
    public void delete(String sessionId) {
        AgentSession session = requireAccessibleSession(sessionId);
        if (StringUtils.hasText(session.getActiveRunId())) {
            throw new BusinessException(409, "SESSION_BUSY");
        }
        sessionMapper.deleteById(sessionId);
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

    /** 全局作用域最新版本（room_id IS NULL，无则 null）。 */
    public AgentRequirementVersion latestGlobalVersion(String sessionId) {
        return requirementVersionMapper.selectOne(new QueryWrapper<AgentRequirementVersion>()
            .eq("session_id", sessionId)
            .isNull("room_id")
            .orderByDesc("version_no")
            .last("LIMIT 1"));
    }

    /** 指定房间作用域最新版本（无则 null）。 */
    public AgentRequirementVersion latestRoomVersion(String sessionId, String roomId) {
        return requirementVersionMapper.selectOne(new QueryWrapper<AgentRequirementVersion>()
            .eq("session_id", sessionId)
            .eq("room_id", roomId)
            .orderByDesc("version_no")
            .last("LIMIT 1"));
    }

    /**
     * 有效需求约束：全局为底 + 当前房间字段覆盖（W2 户型会话；无户型=全局快照，历史行为）。
     *
     * @param sessionId    会话 ID
     * @param activeRoomId 当前房间 ID（可空=仅全局）
     * @return 有效约束（不为 null）
     */
    public RequirementConstraints effectiveConstraints(String sessionId, String activeRoomId) {
        AgentRequirementVersion global = latestGlobalVersion(sessionId);
        RequirementConstraints globalConstraints = global != null
            ? RequirementConstraints.fromJson(global.getConstraints()) : null;
        if (!StringUtils.hasText(activeRoomId)) {
            return globalConstraints != null ? globalConstraints : new RequirementConstraints();
        }
        AgentRequirementVersion room = latestRoomVersion(sessionId, activeRoomId);
        RequirementConstraints roomConstraints = room != null
            ? RequirementConstraints.fromJson(room.getConstraints()) : null;
        return RequirementScope.mergeEffective(globalConstraints, roomConstraints);
    }

    /**
     * 有效需求档案（含当前房间标识），versionNo 取两个作用域最新版本的较大值。
     *
     * @param sessionId    会话 ID
     * @param activeRoom   当前房间事实（可空）
     * @return 有效档案；无任何版本时返回 null
     */
    public RequirementProfileResponse effectiveProfile(String sessionId, FloorPlanRoomFact activeRoom) {
        AgentRequirementVersion global = latestGlobalVersion(sessionId);
        AgentRequirementVersion room = activeRoom != null ? latestRoomVersion(sessionId, activeRoom.roomId()) : null;
        if (global == null && room == null) {
            return null;
        }
        int versionNo = Math.max(
            global != null && global.getVersionNo() != null ? global.getVersionNo() : 0,
            room != null && room.getVersionNo() != null ? room.getVersionNo() : 0);
        RequirementConstraints effective = RequirementScope.mergeEffective(
            global != null ? RequirementConstraints.fromJson(global.getConstraints()) : null,
            room != null ? RequirementConstraints.fromJson(room.getConstraints()) : null);
        String source = room != null && room.getVersionNo() != null
            && (global == null || global.getVersionNo() == null || room.getVersionNo() >= global.getVersionNo())
            ? room.getSource() : global != null ? global.getSource() : null;
        RequirementProfileResponse profile = new RequirementProfileResponse(versionNo, effective, source);
        if (activeRoom != null) {
            profile.setRoomId(activeRoom.roomId());
            profile.setRoomName(activeRoom.displayName());
        }
        return profile;
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
        response.setRoomId(item.getRoomId());
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
        response.setFloorPlanAnalysisId(session.getFloorPlanAnalysisId());
        response.setActiveRoomId(session.getActiveRoomId());
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
