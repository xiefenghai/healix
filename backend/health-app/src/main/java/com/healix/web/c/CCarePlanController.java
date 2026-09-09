package com.healix.web.c;

import com.healix.common.result.ApiResult;
import com.healix.core.careplan.dto.CarePlanBundleDto;
import com.healix.core.careplan.dto.CarePlanCheckinViewDto;
import com.healix.core.careplan.dto.CarePlanListItemDto;
import com.healix.core.careplan.dto.CarePlanTodayDto;
import com.healix.core.careplan.dto.CarePlanVersionDto;
import com.healix.core.careplan.service.CarePlanCheckinService;
import com.healix.core.careplan.service.CarePlanService;
import com.healix.security.SecurityUtils;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** C 端管理方案：概览、今日打卡、历史方案。 */
@Validated
@RestController
@RequestMapping("/api/c/v1/me/care-plan")
@RequiredArgsConstructor
public class CCarePlanController {

    private final CarePlanCheckinService checkinService;
    private final CarePlanService carePlanService;

    @GetMapping
    public ApiResult<CarePlanBundleDto> overview() {
        return ApiResult.ok(carePlanService.getPublishedForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId()));
    }

    @GetMapping("/versions")
    public ApiResult<List<CarePlanListItemDto>> versions() {
        return ApiResult.ok(carePlanService.listVersionsForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId()));
    }

    @GetMapping("/versions/{versionId}")
    public ApiResult<CarePlanVersionDto> versionDetail(@PathVariable String versionId) {
        return ApiResult.ok(carePlanService.getVersionForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), versionId));
    }

    @GetMapping("/versions/{versionId}/checkins")
    public ApiResult<List<CarePlanCheckinViewDto>> versionCheckins(
            @PathVariable String versionId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return ApiResult.ok(checkinService.listCheckinsByVersionForPatient(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requirePatientId(),
                versionId,
                from,
                to));
    }

    @GetMapping("/today")
    public ApiResult<CarePlanTodayDto> today(@RequestParam(required = false) LocalDate date) {
        return ApiResult.ok(checkinService.getPatientToday(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), date));
    }

    @PostMapping("/tasks/{taskId}/checkins")
    public ApiResult<CarePlanCheckinViewDto> checkin(
            @PathVariable String taskId, @RequestBody(required = false) @Validated CheckinRequest request) {
        CheckinRequest req = request == null ? new CheckinRequest(null, null, "DONE", null) : request;
        return ApiResult.ok(checkinService.upsertByPatient(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requirePatientId(),
                taskId,
                new CarePlanCheckinService.CheckinCommand(
                        req.checkinDate(), req.timeSlot(), req.status(), req.note())));
    }

    public record CheckinRequest(LocalDate checkinDate, String timeSlot, String status, String note) {}
}
