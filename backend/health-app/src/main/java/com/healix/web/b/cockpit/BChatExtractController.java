package com.healix.web.b.cockpit;

import com.healix.agent.extract.ChatExtractService;
import com.healix.common.result.ApiResult;
import com.healix.security.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 聊天文字整理的确认写入。抽取仍走现有患者会话。 */
@Validated
@RestController
@RequestMapping("/api/b/v1/cockpit/patients/{peopleId}/chat-extract")
@RequiredArgsConstructor
public class BChatExtractController {

    private final ChatExtractService chatExtractService;

    @PostMapping("/confirm")
    public ApiResult<ConfirmResult> confirm(@PathVariable String peopleId, @RequestBody @Valid ConfirmRequest request) {
        String status = chatExtractService.confirmSaved(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                SecurityUtils.requireStaffId(),
                peopleId,
                request.messageId(),
                request.rows(),
                request.discardIds());
        return ApiResult.ok(new ConfirmResult(status));
    }

    @PostMapping("/reopen")
    public ApiResult<ConfirmResult> reopen(@PathVariable String peopleId, @RequestBody @Valid ReopenRequest request) {
        String status = chatExtractService.reopenDiscarded(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                SecurityUtils.requireStaffId(),
                peopleId,
                request.messageId());
        return ApiResult.ok(new ConfirmResult(status));
    }

    public record ReopenRequest(@NotBlank String messageId) {}

    public record ConfirmRequest(
            @NotBlank String messageId, List<Map<String, Object>> rows, List<String> discardIds) {}

    public record ConfirmResult(String status) {}
}
