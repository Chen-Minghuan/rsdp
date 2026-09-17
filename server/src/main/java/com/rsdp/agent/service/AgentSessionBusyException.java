package com.rsdp.agent.service;

/**
 * 会话并发冲突异常：同一会话已有 running 的 run。
 *
 * <p>Controller 捕获后返回 HTTP 409 {@code {code:409, message:"SESSION_BUSY"}}
 * （前端 useAgentStream 按非 200 响应读取该结构）。</p>
 */
public class AgentSessionBusyException extends RuntimeException {

    public AgentSessionBusyException(String message) {
        super(message);
    }
}
