package com.healix.web.c.carechat;

import com.healix.common.result.ApiResult;
import com.healix.core.carechat.dto.CareChatMessageDto;
import com.healix.core.carechat.dto.CareChatSendRequest;
import com.healix.core.carechat.dto.CareChatSessionDto;
import com.healix.core.carechat.dto.CareChatThreadDto;
import com.healix.core.carechat.service.CareChatFacade;
import com.healix.security.SecurityUtils;
import com.healix.web.carechat.CareChatSseHub;
import com.healix.web.support.AgentSseSupport;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** C 端：健康沟通（CareChat）。 */
@Validated
@RestController
@RequestMapping("/api/c/v1/care-chat")
@RequiredArgsConstructor
public class CCareChatController {

    private final CareChatFacade careChatFacade;
    private final CareChatSseHub careChatSseHub;

    @GetMapping("/threads")
    public ApiResult<List<CareChatThreadDto>> threads() {
        return ApiResult.ok(careChatFacade.listThreadsForAccount(
                SecurityUtils.requireTenantId(), SecurityUtils.requireContext().getAccountId()));
    }

    /**
     * 联系健管师团队：按当前就诊人解析机构并获取/创建沟通会话。
     *
     * @param orgId 可选，多机构时指定目标机构
     */
    @PostMapping("/contact")
    public ApiResult<CareChatSessionDto> contact(
            @RequestParam(required = false) String orgId) {
        return ApiResult.ok(careChatFacade.contactCareTeam(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireContext().getAccountId(),
                SecurityUtils.requirePatientId(),
                orgId));
    }

    @GetMapping("/unread-count")
    public ApiResult<Map<String, Integer>> unreadCount() {
        int count = careChatFacade.patientUnreadTotal(
                SecurityUtils.requireTenantId(), SecurityUtils.requireContext().getAccountId());
        return ApiResult.ok(Map.of("count", count));
    }

    @GetMapping("/threads/{threadId}")
    public ApiResult<CareChatSessionDto> open(@PathVariable String threadId) {
        return ApiResult.ok(careChatFacade.openForPatient(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireContext().getAccountId(),
                threadId));
    }

    @GetMapping("/threads/{threadId}/messages")
    public ApiResult<List<CareChatMessageDto>> messages(
            @PathVariable String threadId,
            @RequestParam(required = false) String before,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "30") int limit) {
        String tenantId = SecurityUtils.requireTenantId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        if (after != null && !after.isBlank()) {
            return ApiResult.ok(careChatFacade.listAfterForPatient(tenantId, accountId, threadId, after, limit));
        }
        return ApiResult.ok(careChatFacade.listHistoryForPatient(tenantId, accountId, threadId, before, limit));
    }

    @PostMapping("/threads/{threadId}/messages")
    public ApiResult<CareChatMessageDto> send(
            @PathVariable String threadId, @Valid @RequestBody CareChatSendRequest body) {
        return ApiResult.ok(careChatFacade.sendFromPatient(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireContext().getAccountId(),
                threadId,
                body.getContent(),
                body.getClientMsgId()));
    }

    @PostMapping("/threads/{threadId}/read")
    public ApiResult<Map<String, Boolean>> read(@PathVariable String threadId) {
        careChatFacade.markPatientRead(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireContext().getAccountId(),
                threadId);
        return ApiResult.ok(Map.of("ok", true));
    }

    /** 患者沟通 SSE：健管师新消息 / 未读变更。 */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(HttpServletResponse response) {
        AgentSseSupport.prepareSseResponse(response);
        SseEmitter emitter = careChatSseHub.subscribeAccount(
                SecurityUtils.requireTenantId(), SecurityUtils.requireContext().getAccountId());
        AgentSseSupport.openStream(emitter, response);
        return emitter;
    }
}
