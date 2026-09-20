package com.healix.web.b.agent;

import com.healix.agent.gateway.AgentAction;
import com.healix.agent.gateway.AgentChatCommand;
import com.healix.agent.gateway.AgentResponse;
import com.healix.agent.gateway.StaffAgentGateway;
import com.healix.agent.gateway.StaffAgentGateway.CapabilityView;
import com.healix.agent.memory.AgentConversationService;
import com.healix.agent.memory.AgentConversationService.SessionBundle;
import com.healix.agent.memory.AgentConversationService.SessionSummary;
import com.healix.agent.memory.AgentConversationService.VisibleMessage;
import com.healix.agent.stream.AgentStreamEvent;
import com.healix.common.result.ApiResult;
import com.healix.security.JwtAuthenticationFilter;
import com.healix.security.SecurityUtils;
import com.healix.web.support.AgentSseSupport;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * B 端员工智能体 HTTP：能力列表 + 同步/SSE 对话 + 可见会话读写。
 */
@Validated
@RestController
@RequestMapping("/api/b/v1/agent")
@RequiredArgsConstructor
public class BAgentController {

    private static final long SSE_TIMEOUT_MS = 120_000L;

    private final StaffAgentGateway staffAgentGateway;
    private final AgentConversationService conversationService;

    @GetMapping("/capabilities")
    public ApiResult<List<CapabilityView>> capabilities() {
        return ApiResult.ok(staffAgentGateway.capabilities());
    }

    /** 历史会话列表（机构级或指定患者）。 */
    @GetMapping("/sessions")
    public ApiResult<List<SessionSummaryView>> listSessions(
            @RequestParam(required = false) String peopleId,
            @RequestParam(defaultValue = "30") int limit) {
        List<SessionSummary> rows = conversationService.listSessions(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                SecurityUtils.requireStaffId(),
                peopleId,
                limit);
        List<SessionSummaryView> out = new ArrayList<>();
        for (SessionSummary s : rows) {
            out.add(new SessionSummaryView(
                    s.sessionId(),
                    s.title(),
                    s.peopleId(),
                    s.status(),
                    s.preview(),
                    s.messageCount(),
                    s.gmtCreated(),
                    s.gmtModified()));
        }
        return ApiResult.ok(out);
    }

    /** 打开指定会话（含已归档）。 */
    @GetMapping("/sessions/{sessionId}")
    public ApiResult<SessionView> getSession(@PathVariable String sessionId) {
        SessionBundle bundle =
                conversationService.getOwnedSession(sessionId, SecurityUtils.requireStaffId());
        return ApiResult.ok(toView(bundle));
    }

    /** 新建会话：归档当前 ACTIVE，返回空会话。 */
    @PostMapping("/sessions/new")
    public ApiResult<SessionView> newSession(@RequestBody(required = false) NewSessionRequest req) {
        String peopleId = req == null ? null : req.peopleId();
        SessionBundle bundle = conversationService.startNew(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                SecurityUtils.requireStaffId(),
                peopleId);
        return ApiResult.ok(toView(bundle));
    }

    /** 继续历史会话：设为 ACTIVE。 */
    @PostMapping("/sessions/{sessionId}/resume")
    public ApiResult<SessionView> resumeSession(@PathVariable String sessionId) {
        SessionBundle bundle = conversationService.resume(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                SecurityUtils.requireStaffId(),
                sessionId);
        return ApiResult.ok(toView(bundle));
    }

    /** 加载或创建当前员工的机构/患者可见会话（刷新可续聊）。 */
    @GetMapping("/sessions/current")
    public ApiResult<SessionView> currentSession(@RequestParam(required = false) String peopleId) {
        SessionBundle bundle = conversationService.loadOrCreate(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                SecurityUtils.requireStaffId(),
                peopleId,
                null);
        return ApiResult.ok(toView(bundle));
    }

    /** 写入系统消息（开场简报 / 动作回执）。 */
    @PostMapping("/sessions/current/messages")
    public ApiResult<SessionView> appendMessage(@RequestBody @Validated AppendMessageRequest req) {
        String sessionId = conversationService.ensureSession(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                SecurityUtils.requireStaffId(),
                req.peopleId(),
                req.sessionId());
        List<AgentAction> actions = toActions(req.actions());
        if ("receipt".equalsIgnoreCase(req.kind())) {
            conversationService.appendReceipt(sessionId, req.content());
        } else {
            conversationService.appendSystem(sessionId, req.content(), actions);
        }
        SessionBundle bundle = conversationService.loadOrCreate(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                SecurityUtils.requireStaffId(),
                req.peopleId(),
                sessionId);
        return ApiResult.ok(toView(bundle));
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
                request.imageBase64(),
                request.imageMimeType());
    }

    private static SessionView toView(SessionBundle bundle) {
        List<MessageView> messages = new ArrayList<>();
        for (VisibleMessage m : bundle.messages()) {
            messages.add(new MessageView(
                    m.id(),
                    m.role(),
                    m.content(),
                    m.actions() == null ? List.of() : m.actions(),
                    m.createdAt()));
        }
        return new SessionView(bundle.sessionId(), bundle.status(), messages);
    }

    private static List<AgentAction> toActions(List<ActionBody> bodies) {
        if (bodies == null || bodies.isEmpty()) {
            return List.of();
        }
        List<AgentAction> out = new ArrayList<>();
        for (ActionBody b : bodies) {
            if (b == null || !StringUtils.hasText(b.type()) || !StringUtils.hasText(b.label())) {
                continue;
            }
            out.add(new AgentAction(b.type(), b.label(), b.path(), b.peopleId(), b.payload()));
        }
        return out;
    }

    public record ChatRequest(
            String peopleId,
            String sessionId,
            @NotBlank String message,
            String capabilityHint,
            String imageBase64,
            String imageMimeType) {}

    public record AppendMessageRequest(
            String peopleId,
            String sessionId,
            @NotBlank String content,
            /** briefing | receipt */
            String kind,
            List<ActionBody> actions) {}

    public record NewSessionRequest(String peopleId) {}

    public record ActionBody(
            String type, String label, String path, String peopleId, Map<String, Object> payload) {}

    public record SessionView(String sessionId, String status, List<MessageView> messages) {}

    public record MessageView(
            String id, String role, String content, List<AgentAction> actions, String createdAt) {}

    public record SessionSummaryView(
            String sessionId,
            String title,
            String peopleId,
            String status,
            String preview,
            int messageCount,
            String gmtCreated,
            String gmtModified) {}
}
