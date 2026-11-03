package com.healix.web.publicapi;

import com.healix.common.result.ApiResult;
import com.healix.core.intake.service.IntakeAccessLinkService;
import com.healix.security.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 公开：首诊 Magic Link 预览与兑换（无需登录）。 */
@Validated
@RestController
@RequestMapping("/api/public/v1/intake-access")
@RequiredArgsConstructor
public class PublicIntakeAccessController {

    private final IntakeAccessLinkService intakeAccessLinkService;
    private final JwtTokenProvider jwtTokenProvider;

    @Value("${healix.security.intake-link-jwt-seconds:7200}")
    private long intakeLinkJwtSeconds;

    @GetMapping("/{token}")
    public ApiResult<PreviewView> preview(@PathVariable String token) {
        IntakeAccessLinkService.PreviewResult p = intakeAccessLinkService.preview(token);
        return ApiResult.ok(new PreviewView(
                p.usable(),
                p.status(),
                p.expireAt(),
                p.orgName(),
                p.patientLabel(),
                p.needMobileLast4()));
    }

    @PostMapping("/{token}/redeem")
    public ApiResult<RedeemView> redeem(
            @PathVariable String token,
            @RequestBody(required = false) RedeemRequest body,
            HttpServletRequest request) {
        String mobileLast4 = body != null ? body.mobileLast4() : null;
        IntakeAccessLinkService.RedeemContext ctx =
                intakeAccessLinkService.redeem(token, mobileLast4, clientIp(request));
        String accessToken = jwtTokenProvider.createIntakeLinkToken(
                ctx.linkId(), ctx.peopleId(), ctx.tenantId(), ctx.orgId(), intakeLinkJwtSeconds);
        return ApiResult.ok(new RedeemView(accessToken, ctx.depthDefault(), intakeLinkJwtSeconds));
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }

    public record PreviewView(
            boolean usable,
            String status,
            LocalDateTime expireAt,
            String orgName,
            String patientLabel,
            boolean needMobileLast4) {}

    public record RedeemRequest(String mobileLast4) {}

    public record RedeemView(String accessToken, String depthDefault, long expiresInSeconds) {}
}
