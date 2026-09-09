package com.healix.web.c;

import com.healix.common.result.ApiResult;
import com.healix.core.notify.dto.NotifyMessageViewDto;
import com.healix.core.notify.service.NotifyFacade;
import com.healix.security.SecurityUtils;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** C 端消息中心。 */
@Validated
@RestController
@RequestMapping("/api/c/v1/notifications")
@RequiredArgsConstructor
public class CNotificationController {

    private final NotifyFacade notifyFacade;

    @GetMapping
    public ApiResult<List<NotifyMessageViewDto>> list(
            @RequestParam(required = false) String peopleId,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return ApiResult.ok(notifyFacade.listForAccount(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireContext().getAccountId(),
                peopleId,
                unreadOnly,
                limit,
                offset));
    }

    @GetMapping("/unread-count")
    public ApiResult<Map<String, Long>> unreadCount() {
        long count = notifyFacade.unreadCount(
                SecurityUtils.requireTenantId(), SecurityUtils.requireContext().getAccountId());
        return ApiResult.ok(Map.of("count", count));
    }

    @PostMapping("/{id}/read")
    public ApiResult<NotifyMessageViewDto> read(@PathVariable String id) {
        return ApiResult.ok(notifyFacade.markRead(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireContext().getAccountId(),
                id));
    }

    @PostMapping("/read-all")
    public ApiResult<Map<String, Integer>> readAll() {
        int n = notifyFacade.markAllRead(
                SecurityUtils.requireTenantId(), SecurityUtils.requireContext().getAccountId());
        return ApiResult.ok(Map.of("updated", n));
    }
}
