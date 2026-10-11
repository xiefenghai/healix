package com.healix.web.c;

import com.healix.common.exception.BusinessException;
import com.healix.common.result.ApiResult;
import com.healix.core.notify.webpush.WebPushService;
import com.healix.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端浏览器推送订阅。 */
@Validated
@RestController
@RequestMapping("/api/c/v1/push")
@RequiredArgsConstructor
public class CPushController {

    private final WebPushService webPushService;

    @GetMapping("/config")
    public ApiResult<Map<String, Object>> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", webPushService.isEnabled());
        if (webPushService.isEnabled()) {
            out.put("publicKey", webPushService.publicKey());
            out.put("subscriptionCount", webPushService.countSubscriptions(accountId()));
        }
        return ApiResult.ok(out);
    }

    @PostMapping("/subscriptions")
    public ApiResult<Void> subscribe(
            @Validated @RequestBody SubscribeRequest req,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        webPushService.subscribe(accountId(), req.getEndpoint(), req.getP256dh(), req.getAuth(), userAgent);
        return ApiResult.ok(null);
    }

    @DeleteMapping("/subscriptions")
    public ApiResult<Void> unsubscribe(@RequestBody EndpointRequest req) {
        webPushService.unsubscribe(accountId(), req.getEndpoint());
        return ApiResult.ok(null);
    }

    @PostMapping("/test")
    public ApiResult<WebPushService.SendSummary> test() {
        WebPushService.SendSummary s = webPushService.sendToAccount(
                accountId(),
                new WebPushService.Payload("推送已开启", "之后报告、计划和每日待办会在这里提醒你", "/notifications", "TEST"));
        if (s.total() == 0) {
            throw new BusinessException(400, "当前账号还没有开启推送的设备");
        }
        return ApiResult.ok(s);
    }

    private static String accountId() {
        SecurityUtils.requireTenantId();
        return SecurityUtils.requireContext().getAccountId();
    }

    @Data
    public static class SubscribeRequest {
        @NotBlank
        private String endpoint;

        @NotBlank
        private String p256dh;

        @NotBlank
        private String auth;
    }

    @Data
    public static class EndpointRequest {
        private String endpoint;
    }
}
