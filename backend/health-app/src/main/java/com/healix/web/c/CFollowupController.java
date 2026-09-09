package com.healix.web.c;

import com.healix.common.result.ApiResult;
import com.healix.core.followup.dto.FollowupListItemDto;
import com.healix.core.followup.dto.FollowupRecordViewDto;
import com.healix.core.followup.service.FollowupScheduleService;
import com.healix.core.followup.service.FollowupScheduleService.RequestResult;
import com.healix.core.followup.service.FollowupService;
import com.healix.security.SecurityUtils;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** C 端随访记录：只读，OPEN/DONE。 */
@Validated
@RestController
@RequestMapping("/api/c/v1/followups")
@RequiredArgsConstructor
public class CFollowupController {

    private final FollowupService followupService;
    private final FollowupScheduleService followupScheduleService;

    @GetMapping
    public ApiResult<List<FollowupListItemDto>> list(@RequestParam(defaultValue = "50") int limit) {
        return ApiResult.ok(followupService.listForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), limit));
    }

    @GetMapping("/{id}")
    public ApiResult<FollowupRecordViewDto> detail(@PathVariable String id) {
        return ApiResult.ok(followupService.getForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), id));
    }

    /** 主动申请回访：开待办随访 + 工作台任务；已有待处理随访则复用。 */
    @PostMapping("/requests")
    public ApiResult<RequestResult> request(@RequestBody(required = false) FollowupRequestBody body) {
        FollowupRequestBody req = body == null ? new FollowupRequestBody(null, null) : body;
        return ApiResult.ok(followupScheduleService.requestByPatient(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requirePatientId(),
                req.reason(),
                req.preferredDay()));
    }

    public record FollowupRequestBody(
            String reason, @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate preferredDay) {}
}
