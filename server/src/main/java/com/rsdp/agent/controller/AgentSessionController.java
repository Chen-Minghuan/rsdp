package com.rsdp.agent.controller;

import com.rsdp.agent.dto.AgentSessionDetailResponse;
import com.rsdp.agent.dto.AgentSessionResponse;
import com.rsdp.agent.dto.AgentQuoteResponse;
import com.rsdp.agent.dto.AgentSchemeExportResponse;
import com.rsdp.agent.dto.ConfirmItemRequest;
import com.rsdp.agent.dto.ConfirmedItemResponse;
import com.rsdp.agent.dto.CreateSessionRequest;
import com.rsdp.agent.dto.ExportSchemeRequest;
import com.rsdp.agent.dto.GenerateQuoteRequest;
import com.rsdp.agent.dto.SendMessageRequest;
import com.rsdp.agent.service.AgentConfirmService;
import com.rsdp.agent.service.AgentQuoteService;
import com.rsdp.agent.service.AgentSchemeExportService;
import com.rsdp.agent.service.AgentSessionBusyException;
import com.rsdp.agent.service.AgentSessionService;
import com.rsdp.agent.service.AgentStreamService;
import com.rsdp.common.Result;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 营销选品 Agent 接口（/api/v1/agent）。
 *
 * <p>全部接口需 agent:use 权限（SecurityConfig URL 授权）。数据隔离在 Service 层：
 * 非平台员工仅可见 created_by = 自己 或 customer_user_id = 自己 的会话，无权 403；
 * DTO 契约对平台员工与客户一致（契约内本就不含出厂价等内部字段）。</p>
 */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
@Validated
public class AgentSessionController {

    private final AgentSessionService sessionService;
    private final AgentStreamService streamService;
    private final AgentConfirmService confirmService;
    private final AgentQuoteService quoteService;
    private final AgentSchemeExportService schemeExportService;

    /**
     * 创建选品会话（设计师代录时填客户名）。
     *
     * @param request 创建请求
     * @return 新会话
     */
    @PostMapping("/sessions")
    public Result<AgentSessionResponse> createSession(@RequestBody(required = false) CreateSessionRequest request) {
        return Result.ok(sessionService.create(request != null ? request.getCustomerName() : null));
    }

    /**
     * 查询我的会话列表（按 updatedAt 倒序；平台员工可见全部）。
     *
     * @return 会话列表
     */
    @GetMapping("/sessions")
    public Result<List<AgentSessionResponse>> listSessions() {
        return Result.ok(sessionService.listMine());
    }

    /**
     * 查询会话详情（会话 + 消息 + 需求档案 + 已确认清单）。
     *
     * @param sessionId 会话 ID
     * @return 会话详情
     */
    @GetMapping("/sessions/{sessionId}")
    public Result<AgentSessionDetailResponse> getSession(@PathVariable String sessionId) {
        return Result.ok(sessionService.getDetail(sessionId));
    }

    /**
     * 发送消息（SSE 流式）。
     *
     * <p>同步校验失败时返回非 200 + {code,message}（前端 useAgentStream 按此读取）；
     * 校验通过后返回 text/event-stream，事件帧 data 均含 runId + seq。</p>
     *
     * @param sessionId 会话 ID
     * @param request   消息请求（clientMessageId 幂等）
     * @return SSE 流或错误响应
     */
    @PostMapping("/sessions/{sessionId}/messages/stream")
    public Object streamMessage(@PathVariable String sessionId, @RequestBody @Valid SendMessageRequest request) {
        try {
            return streamService.startStream(sessionId, request);
        } catch (AgentSessionBusyException e) {
            // 会话已有 running run：HTTP 409 + {code:409, message:"SESSION_BUSY"}
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.error(409, "SESSION_BUSY"));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.error(404, e.getMessage()));
        } catch (BusinessException e) {
            int status = e.getCode() >= 400 && e.getCode() < 600 ? e.getCode() : 400;
            return ResponseEntity.status(status).body(Result.error(e.getCode(), e.getMessage()));
        }
    }

    /**
     * 确认主体产品（幂等：重复提交返回已存在记录）。
     *
     * @param sessionId 会话 ID
     * @param request   确认请求
     * @return 已确认项
     */
    @PostMapping("/sessions/{sessionId}/confirm")
    public Result<ConfirmedItemResponse> confirmItem(@PathVariable String sessionId,
                                                     @RequestBody @Valid ConfirmItemRequest request) {
        return Result.ok(confirmService.confirm(sessionId, request));
    }

    /**
     * 结束会话（存在 running run 时返回 409 SESSION_BUSY）。
     *
     * @param sessionId 会话 ID
     * @return 更新后的会话
     */
    @PostMapping("/sessions/{sessionId}/close")
    public Result<AgentSessionResponse> closeSession(@PathVariable String sessionId) {
        return Result.ok(sessionService.close(sessionId));
    }

    /**
     * 删除会话（软删除：列表/详情不可见，子表数据留痕；存在 running run 时返回 409 SESSION_BUSY）。
     *
     * @param sessionId 会话 ID
     * @return 空结果
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        sessionService.delete(sessionId);
        return Result.ok();
    }

    /**
     * 生成会话报价（P2 一键报价，幂等：重复提交返回已存在报价）。
     *
     * <p>售价口径卡片（无成本字段）；需 quote:generate 权限（Service 层校验）。</p>
     *
     * @param sessionId 会话 ID
     * @param request   生成请求
     * @return 报价卡片
     */
    @PostMapping("/sessions/{sessionId}/quote")
    public Result<AgentQuoteResponse> generateQuote(@PathVariable String sessionId,
                                                    @RequestBody @Valid GenerateQuoteRequest request) {
        return Result.ok(quoteService.generate(sessionId, request));
    }

    /**
     * 导出会话确认清单为方案（P2 对话外下单出口，幂等：同键重复提交返回首个导出结果）。
     *
     * <p>需 scheme:create 权限（Service 层校验）；导出后跳方案详情页走既有报价单/下单流程。</p>
     *
     * @param sessionId 会话 ID
     * @param request   导出请求
     * @return 方案导出结果
     */
    @PostMapping("/sessions/{sessionId}/scheme")
    public Result<AgentSchemeExportResponse> exportScheme(@PathVariable String sessionId,
                                                          @RequestBody @Valid ExportSchemeRequest request) {
        return Result.ok(schemeExportService.exportScheme(sessionId, request));
    }
}
