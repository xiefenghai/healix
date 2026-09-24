package com.healix.web.b.opsstats;

import com.healix.common.result.ApiResult;
import com.healix.core.opsstats.dto.OpsStatsByStaffResponseDto;
import com.healix.core.opsstats.dto.OpsStatsFollowupSummaryDto;
import com.healix.core.opsstats.dto.OpsStatsSeriesPointDto;
import com.healix.core.opsstats.dto.OpsStatsSummaryDto;
import com.healix.core.opsstats.dto.OpsStatsTypeSliceDto;
import com.healix.core.opsstats.service.OpsStatsService;
import com.healix.security.SecurityUtils;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/b/v1/ops-stats")
@RequiredArgsConstructor
public class BOpsStatsController {

    private final OpsStatsService opsStatsService;

    @GetMapping("/summary")
    public ApiResult<OpsStatsSummaryDto> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String careTeamId,
            @RequestParam(required = false) String staffId) {
        return ApiResult.ok(opsStatsService.summary(
                SecurityUtils.requireCurrentOrgId(), from, to, careTeamId, staffId));
    }

    @GetMapping("/tasks/series")
    public ApiResult<List<OpsStatsSeriesPointDto>> series(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String careTeamId,
            @RequestParam(required = false) String staffId) {
        return ApiResult.ok(opsStatsService.series(
                SecurityUtils.requireCurrentOrgId(), from, to, careTeamId, staffId));
    }

    @GetMapping("/tasks/by-type")
    public ApiResult<List<OpsStatsTypeSliceDto>> byType(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String careTeamId,
            @RequestParam(required = false) String staffId) {
        return ApiResult.ok(opsStatsService.byType(
                SecurityUtils.requireCurrentOrgId(), from, to, careTeamId, staffId));
    }

    @GetMapping("/tasks/by-staff")
    public ApiResult<OpsStatsByStaffResponseDto> byStaff(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String careTeamId) {
        return ApiResult.ok(opsStatsService.byStaff(
                SecurityUtils.requireCurrentOrgId(), from, to, careTeamId));
    }

    @GetMapping("/followups/summary")
    public ApiResult<OpsStatsFollowupSummaryDto> followups(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String careTeamId,
            @RequestParam(required = false) String staffId) {
        return ApiResult.ok(opsStatsService.followups(
                SecurityUtils.requireCurrentOrgId(), from, to, careTeamId, staffId));
    }
}
