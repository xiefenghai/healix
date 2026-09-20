package com.healix.web.b.carechat;

import com.healix.common.result.ApiResult;
import com.healix.core.carechat.dto.CareChatInboxDto;
import com.healix.core.carechat.dto.CareChatMessageDto;
import com.healix.core.carechat.dto.CareChatSendRequest;
import com.healix.core.carechat.dto.CareChatSessionDto;
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

/** B 端：患者沟通（CareChat）。 */
@Validated
@RestController
@RequestMapping("/api/b/v1")
@RequiredArgsConstructor
public class BCareChatController {

    private final CareChatFacade careChatFacade;
    private final CareChatSseHub careChatSseHub;

    @GetMapping("/patients/{peopleId}/care-chat")
    public ApiResult<CareChatSessionDto> open(@PathVariable String peopleId) {
        return ApiResult.ok(careChatFacade.openForStaff(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), peopleId));
    }

    @GetMapping("/patients/{peopleId}/care-chat/messages")
    public ApiResult<List<CareChatMessageDto>> messages(
            @PathVariable String peopleId,
            @RequestParam(required = false) String before,
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "30") int limit) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        if (after != null && !after.isBlank()) {
            return ApiResult.ok(careChatFacade.listAfterForStaff(tenantId, orgId, peopleId, after, limit));
        }
        return ApiResult.ok(careChatFacade.listHistoryForStaff(tenantId, orgId, peopleId, before, limit));
    }

    @PostMapping("/patients/{peopleId}/care-chat/messages")
    public ApiResult<CareChatMessageDto> send(
            @PathVariable String peopleId, @Valid @RequestBody CareChatSendRequest body) {
        return ApiResult.ok(careChatFacade.sendFromStaff(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                SecurityUtils.requireContext().getAccountId(),
                body.getContent(),
                body.getClientMsgId()));
    }

    @PostMapping("/patients/{peopleId}/care-chat/read")
    public ApiResult<Map<String, Boolean>> read(@PathVariable String peopleId) {
        careChatFacade.markStaffRead(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), peopleId);
        return ApiResult.ok(Map.of("ok", true));
    }

    @GetMapping("/care-chat/unread-count")
    public ApiResult<Map<String, Long>> unreadCount() {
        long count = careChatFacade.staffUnreadTotal(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId());
        return ApiResult.ok(Map.of("count", count));
    }

    @GetMapping("/care-chat/inbox")
    public ApiResult<CareChatInboxDto> inbox() {
        return ApiResult.ok(careChatFacade.inboxForStaff(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId()));
    }

    /** 机构沟通 SSE：未读 / 新消息推送（B 侧栏与沟通页订阅）。 */
    @GetMapping(value = "/care-chat/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(HttpServletResponse response) {
        AgentSseSupport.prepareSseResponse(response);
        SseEmitter emitter = careChatSseHub.subscribeOrg(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId());
        AgentSseSupport.openStream(emitter, response);
        return emitter;
    }
}
