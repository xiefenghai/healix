package com.healix.web.b.agent;

import com.healix.agent.gateway.AgentChatCommand;
import com.healix.agent.gateway.AgentResponse;
import com.healix.agent.gateway.StaffAgentGateway;
import com.healix.agent.gateway.StaffAgentGateway.CapabilityView;
import com.healix.agent.stream.AgentStreamEvent;
import com.healix.common.result.ApiResult;
import com.healix.security.JwtAuthenticationFilter;
import com.healix.security.SecurityUtils;
import com.healix.web.support.AgentSseSupport;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Validated
@RestController
@RequestMapping("/api/b/v1/agent")
@RequiredArgsConstructor
public class BAgentController {

    private static final long SSE_TIMEOUT_MS = 120_000L;

    private final StaffAgentGateway staffAgentGateway;

    @GetMapping("/capabilities")
    public ApiResult<List<CapabilityView>> capabilities() {
        return ApiResult.ok(staffAgentGateway.capabilities());
    }

    @PostMapping("/chat")
    public ApiResult<AgentResponse> chat(@RequestBody @Validated ChatRequest request) {
        return ApiResult.ok(staffAgentGateway.chat(toCommand(request)));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestBody @Validated ChatRequest request, HttpServletResponse response) {
        AgentSseSupport.prepareSseResponse(response);
        SseEmitter emitter = AgentSseSupport.createEmitter(SSE_TIMEOUT_MS);
        AgentSseSupport.openStream(emitter, response);
        AgentChatCommand cmd = toCommand(request);
        CompletableFuture.runAsync(JwtAuthenticationFilter.wrapAsync(() -> {
            try {
                staffAgentGateway.streamChat(cmd, AgentSseSupport.sseSink(emitter, response));
                emitter.complete();
            } catch (Exception ex) {
                try {
                    emitter.send(SseEmitter.event()
                            .data(AgentStreamEvent.error(
                                            ex.getMessage() == null ? "stream failed" : ex.getMessage())
                                    .toJsonLine()));
                } catch (Exception ignored) {
                    // ignore
                }
                emitter.completeWithError(ex);
            }
        }));
        return emitter;
    }

    private static AgentChatCommand toCommand(ChatRequest request) {
        return new AgentChatCommand(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                SecurityUtils.requireStaffId(),
                request.peopleId(),
                request.sessionId(),
                request.message(),
                request.capabilityHint(),
                null,
                null);
    }

    public record ChatRequest(
            @NotBlank String peopleId,
            String sessionId,
            @NotBlank String message,
            String capabilityHint) {}
}
