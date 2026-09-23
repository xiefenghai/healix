package com.healix.web.c;

import com.healix.common.result.ApiResult;
import com.healix.core.report.dto.HealthReportListItemDto;
import com.healix.core.report.dto.HealthReportViewDto;
import com.healix.core.report.service.HealthReportService;
import com.healix.security.SecurityUtils;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** C 端管理报告：仅已发布。 */
@Validated
@RestController
@RequestMapping("/api/c/v1/health-reports")
@RequiredArgsConstructor
public class CHealthReportController {

    private final HealthReportService healthReportService;

    @GetMapping
    public ApiResult<List<HealthReportListItemDto>> list(@RequestParam(defaultValue = "50") int limit) {
        return ApiResult.ok(healthReportService.listPublishedForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), limit));
    }

    @GetMapping("/{id}")
    public ApiResult<HealthReportViewDto> detail(@PathVariable String id) {
        var ctx = SecurityUtils.requireContext();
        return ApiResult.ok(healthReportService.getPublishedForPatient(
                SecurityUtils.requireTenantId(),
                ctx.getAccountId(),
                ctx.getPatientId(),
                id));
    }
}
