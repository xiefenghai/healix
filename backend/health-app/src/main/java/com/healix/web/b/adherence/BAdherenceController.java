package com.healix.web.b.adherence;

import com.healix.common.result.ApiResult;
import com.healix.common.result.PageResult;
import com.healix.core.adherence.dto.AdherenceBoardActionResultDto;
import com.healix.core.adherence.dto.AdherenceBoardEscalateRequest;
import com.healix.core.adherence.dto.AdherenceNudgeResultDto;
import com.healix.core.adherence.dto.AdherenceOverviewDto;
import com.healix.core.adherence.dto.AdherencePatientDetailDto;
import com.healix.core.adherence.dto.AdherencePatientItemDto;
import com.healix.core.adherence.dto.AdherencePatientSummaryDto;
import com.healix.core.adherence.dto.AdherenceTrendPointDto;
import com.healix.core.adherence.service.AdherenceBoardActionService;
import com.healix.core.adherence.service.AdherenceQueryService;
import com.healix.core.adherence.service.AdherenceSnapshotService;
import com.healix.core.archive.service.ArchiveAccessService;
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
public class BAdherenceController {

    private final AdherenceQueryService adherenceQueryService;
    private final AdherenceBoardActionService adherenceBoardActionService;
    private final AdherenceSnapshotService adherenceSnapshotService;
    private final ArchiveAccessService archiveAccessService;

    @GetMapping("/adherence/overview")
    public ApiResult<AdherenceOverviewDto> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String careTeamId) {
        return ApiResult.ok(adherenceQueryService.overview(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), date, careTeamId));
    }

    /** 依从性趋势：读日快照，默认到昨天为止（当天由 overview 实时给）。 */
    @GetMapping("/adherence/trend")
    public ApiResult<List<AdherenceTrendPointDto>> trend(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) String careTeamId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResult.ok(adherenceSnapshotService.trend(
                SecurityUtils.requireCurrentOrgId(), careTeamId, days, endDate));
    }

    @GetMapping("/adherence/patients")
    public ApiResult<PageResult<AdherencePatientItemDto>> patients(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String careTeamId,
            @RequestParam(required = false) String risk,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResult.ok(adherenceQueryService.pagePatients(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                date,
                careTeamId,
                risk,
                filter,
                keyword,
                page,
                size));
    }

    /**
     * 单患者依从性摘要（实时轻量）：近 7 日方案完成率百分比 + 当日用药完成率。
     * <p>详情页 Hero / 列表角标优先用本接口；完整日曲线仍用 {@code /patients/{id}/adherence}。
     */
    @GetMapping("/patients/{peopleId}/adherence/summary")
    public ApiResult<AdherencePatientSummaryDto> patientSummary(
            @PathVariable String peopleId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return ApiResult.ok(adherenceQueryService.patientSummary(tenantId, orgId, peopleId, date));
    }

    @GetMapping("/patients/{peopleId}/adherence")
    public ApiResult<AdherencePatientDetailDto> patientDetail(
            @PathVariable String peopleId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer days) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return ApiResult.ok(adherenceQueryService.patientDetail(tenantId, orgId, peopleId, date, days));
    }

    /**
     * 看板一键催办：开 OPEN 定期随访（+ FOLLOW_UP）和/或 PLAN_NUDGE 打卡跟进。
     * 默认开随访；streak≥3 时默认同时开打卡跟进。不做用药催办单。
     */
    @PostMapping("/adherence/patients/{peopleId}/escalate")
    public ApiResult<AdherenceBoardActionResultDto> escalate(
            @PathVariable String peopleId, @RequestBody(required = false) AdherenceBoardEscalateRequest body) {
        return ApiResult.ok(adherenceBoardActionService.escalate(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                body,
                SecurityUtils.requireContext().getAccountId()));
    }

    /** 一键提醒患者：向已绑定 C 账号发站内信（同员工同患者同日只保留一条）。 */
    @PostMapping("/adherence/patients/{peopleId}/nudge")
    public ApiResult<AdherenceNudgeResultDto> nudge(
            @PathVariable String peopleId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResult.ok(adherenceBoardActionService.nudgePatient(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                date,
                SecurityUtils.requireContext().getAccountId()));
    }
}
