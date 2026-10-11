package com.healix.web.b.report;

import com.healix.agent.report.ReportSummaryGenerator;
import com.healix.agent.report.ReportSummaryGenerator.ReportSummaryDraft;
import com.healix.agent.support.AiUsageGuard;
import com.healix.common.result.ApiResult;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.govern.enums.FeatureFlagKeyEnum;
import com.healix.core.govern.enums.QuotaKeyEnum;
import com.healix.core.report.catalog.HealthReportGeneratedBy;
import com.healix.core.report.catalog.HealthReportPeriodType;
import com.healix.core.report.dto.HealthReportListItemDto;
import com.healix.core.report.dto.HealthReportViewDto;
import com.healix.core.report.service.HealthReportService;
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

@Validated
@RestController
@RequestMapping("/api/b/v1")
@RequiredArgsConstructor
public class BHealthReportController {

    private final HealthReportService healthReportService;
    private final ArchiveAccessService archiveAccessService;
    private final ReportSummaryGenerator reportSummaryGenerator;
    private final AiUsageGuard aiUsageGuard;

    @GetMapping("/patients/{peopleId}/health-reports")
    public ApiResult<List<HealthReportListItemDto>> listByPeople(
            @PathVariable String peopleId, @RequestParam(defaultValue = "50") int limit) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return ApiResult.ok(healthReportService.listByPeople(orgId, peopleId, limit));
    }

    @GetMapping("/health-reports/{id}")
    public ApiResult<HealthReportViewDto> detail(@PathVariable String id) {
        return ApiResult.ok(healthReportService.get(SecurityUtils.requireCurrentOrgId(), id));
    }

    @PostMapping("/patients/{peopleId}/health-reports/generate")
    public ApiResult<HealthReportViewDto> generate(
            @PathVariable String peopleId, @RequestBody @Validated GenerateRequest req) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        String periodType =
                req.periodType() == null || req.periodType().isBlank()
                        ? HealthReportPeriodType.WEEK.name()
                        : req.periodType();
        return ApiResult.ok(healthReportService.generate(
                tenantId,
                orgId,
                peopleId,
                periodType,
                req.periodStart(),
                HealthReportGeneratedBy.MANUAL.name(),
                SecurityUtils.requireStaffId(),
                false));
    }

    @PostMapping("/health-reports/{id}/refresh")
    public ApiResult<HealthReportViewDto> refresh(@PathVariable String id) {
        return ApiResult.ok(healthReportService.refresh(SecurityUtils.requireCurrentOrgId(), id));
    }

    /**
     * AI 点评草稿：不落库，健管师确认/修改后再调 publish。
     * LLM 未启用或失败时返回模板草稿（fromLlm=false，note 说明原因）。
     */
    @PostMapping("/health-reports/{id}/ai-summary")
    public ApiResult<AiSummaryResponse> aiSummary(@PathVariable String id) {
        aiUsageGuard.check(
                SecurityUtils.requireTenantId(),
                FeatureFlagKeyEnum.AI_REPORT_SUMMARY,
                QuotaKeyEnum.AI_CALL_MONTHLY);
        HealthReportViewDto report = healthReportService.get(SecurityUtils.requireCurrentOrgId(), id);
        ReportSummaryDraft draft = reportSummaryGenerator.generate(report.getContent(), report.getPeriodType());
        return ApiResult.ok(new AiSummaryResponse(
                draft.staffComment(),
                draft.nextFocus(),
                draft.quarterAdvice(),
                draft.fromLlm(),
                draft.note()));
    }

    @PostMapping("/health-reports/{id}/publish")
    public ApiResult<HealthReportViewDto> publish(
            @PathVariable String id, @RequestBody(required = false) PublishRequest req) {
        PublishRequest body = req == null ? new PublishRequest(null, null, null) : req;
        return ApiResult.ok(healthReportService.publish(
                SecurityUtils.requireCurrentOrgId(),
                id,
                SecurityUtils.requireStaffId(),
                body.staffComment(),
                body.nextFocus(),
                body.quarterAdvice()));
    }

    @PostMapping("/health-reports/{id}/skip")
    public ApiResult<HealthReportViewDto> skip(
            @PathVariable String id, @RequestBody(required = false) SkipRequest req) {
        SkipRequest body = req == null ? new SkipRequest(null) : req;
        return ApiResult.ok(healthReportService.skip(
                SecurityUtils.requireCurrentOrgId(),
                id,
                SecurityUtils.requireStaffId(),
                body.reason()));
    }

    @PostMapping("/health-reports/{id}/void")
    public ApiResult<Void> voidReport(@PathVariable String id) {
        healthReportService.voidReport(
                SecurityUtils.requireCurrentOrgId(),
                id,
                SecurityUtils.requireStaffId(),
                HealthReportService.isTenantAdminContext());
        return ApiResult.ok();
    }

    public record GenerateRequest(
            String periodType, @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart) {}

    public record PublishRequest(String staffComment, String nextFocus, String quarterAdvice) {}

    public record AiSummaryResponse(
            String staffComment, String nextFocus, String quarterAdvice, boolean fromLlm, String note) {}

    public record SkipRequest(String reason) {}
}
