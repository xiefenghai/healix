package com.healix.web.b.followup;

import com.healix.common.result.ApiResult;
import com.healix.common.result.PageResult;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.followup.dto.FollowupListItemDto;
import com.healix.core.followup.dto.FollowupRecordViewDto;
import com.healix.core.followup.service.FollowupService;
import com.healix.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

@Validated
@RestController
@RequestMapping("/api/b/v1")
@RequiredArgsConstructor
public class BFollowupController {

    private final FollowupService followupService;
    private final ArchiveAccessService archiveAccessService;

    @GetMapping("/followups")
    public ApiResult<PageResult<FollowupListItemDto>> page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String recordType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.ok(followupService.page(
                SecurityUtils.requireCurrentOrgId(), status, recordType, keyword, page, size));
    }

    @GetMapping("/followups/{id}")
    public ApiResult<FollowupRecordViewDto> detail(@PathVariable String id) {
        return ApiResult.ok(followupService.get(SecurityUtils.requireCurrentOrgId(), id));
    }

    @PostMapping("/followups")
    public ApiResult<FollowupRecordViewDto> create(@RequestBody @Validated CreateRequest req) {
        boolean completeNow = Boolean.TRUE.equals(req.completeNow());
        return ApiResult.ok(followupService.createPeriodic(
                SecurityUtils.requireCurrentOrgId(),
                req.peopleId(),
                req.followupType(),
                req.plannedAt(),
                req.createTask(),
                completeNow,
                req.content(),
                SecurityUtils.requireContext().getAccountId()));
    }

    @PostMapping("/followups/{id}/complete")
    public ApiResult<FollowupRecordViewDto> complete(
            @PathVariable String id, @RequestBody Map<String, Object> content) {
        return ApiResult.ok(followupService.complete(
                SecurityUtils.requireCurrentOrgId(),
                id,
                content,
                SecurityUtils.requireContext().getAccountId()));
    }

    /** 保存未完成随访草稿（不办结、不关任务） */
    @PostMapping("/followups/{id}/draft")
    public ApiResult<FollowupRecordViewDto> saveDraft(
            @PathVariable String id, @RequestBody Map<String, Object> content) {
        return ApiResult.ok(followupService.saveDraft(
                SecurityUtils.requireCurrentOrgId(),
                id,
                content,
                SecurityUtils.requireContext().getAccountId()));
    }

    @PostMapping("/followups/{id}/cancel")
    public ApiResult<FollowupRecordViewDto> cancel(
            @PathVariable String id, @RequestBody @Validated ReasonRequest req) {
        return ApiResult.ok(followupService.cancel(
                SecurityUtils.requireCurrentOrgId(),
                id,
                req.reason(),
                SecurityUtils.requireContext().getAccountId()));
    }

    @GetMapping("/patients/{peopleId}/followups")
    public ApiResult<List<FollowupListItemDto>> patientFollowups(
            @PathVariable String peopleId,
            @RequestParam(required = false) String recordType,
            @RequestParam(defaultValue = "50") int limit) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return ApiResult.ok(followupService.listByPeople(orgId, peopleId, recordType, limit));
    }

    public record CreateRequest(
            @NotBlank String peopleId,
            String followupType,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime plannedAt,
            Boolean createTask,
            Boolean completeNow,
            Map<String, Object> content) {}

    public record ReasonRequest(@NotBlank String reason) {}
}
