package com.rsdp.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.rsdp.agent.graph.AgentStateKeys;
import com.rsdp.agent.graph.nodes.AbstractAgentNode;
import com.rsdp.agent.service.AgentEventBus;
import com.rsdp.agent.service.AgentRunContext;
import com.rsdp.agent.service.AgentRunRecorder;
import com.rsdp.security.SecurityOperatorContext;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentNodeSecurityContextTest {

    @Test
    void graphNodeUsesRequestIdentityAndRestoresWorkerContext() throws Exception {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext workerContext = SecurityContextHolder.createEmptyContext();
        SecurityContextHolder.setContext(workerContext);
        AgentRunContext run = new AgentRunContext("SES-1", "RUN-1", "admin-id", "找座椅");
        run.setAuthentication(new TestingAuthenticationToken("admin", null, "ROLE_ADMIN"));
        AgentRunContext.register(run);
        try {
            OverAllState state = mock(OverAllState.class);
            when(state.value(AgentStateKeys.STATE_RUN_ID, "")).thenReturn("RUN-1");
            AbstractAgentNode node = new AbstractAgentNode(mock(AgentEventBus.class), mock(AgentRunRecorder.class)) {
                @Override
                protected String nodeName() {
                    return AgentStateKeys.NODE_PRODUCT_SEARCH;
                }

                @Override
                protected Map<String, Object> doApply(OverAllState state, AgentRunContext ctx) {
                    return Map.of("platformStaff", SecurityOperatorContext.isPlatformStaff());
                }
            };

            assertThat(node.apply(state)).containsEntry("platformStaff", true);
            assertThat(SecurityContextHolder.getContext()).isSameAs(workerContext);
            assertThat(SecurityOperatorContext.isPlatformStaff()).isFalse();
        } finally {
            AgentRunContext.remove("RUN-1");
            SecurityContextHolder.setContext(previous);
        }
    }
}
