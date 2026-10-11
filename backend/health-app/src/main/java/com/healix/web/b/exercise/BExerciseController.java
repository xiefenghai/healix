package com.healix.web.b.exercise;

import com.healix.common.result.ApiResult;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.exercise.dto.ExerciseSessionDto;
import com.healix.core.exercise.dto.ExerciseTodayDto;
import com.healix.core.exercise.dto.ExerciseTypeDto;
import com.healix.core.exercise.dto.ExerciseWeekDto;
import com.healix.core.exercise.service.ExerciseRecordService;
import com.healix.core.exercise.service.ExerciseRecordService.Actor;
import com.healix.core.exercise.service.ExerciseRecordService.SessionInput;
import com.healix.core.exercise.support.ExerciseCatalog;
import com.healix.security.SecurityUtils;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** B 端患者运动：计划执行概览、周汇总、记录查看 / 代录。目标调整走方案编辑。 */
@Validated
@RestController
@RequestMapping("/api/b/v1")
@RequiredArgsConstructor
public class BExerciseController {

    private final ExerciseRecordService recordService;
    private final ExerciseCatalog catalog;
    private final ArchiveAccessService accessService;

    @GetMapping("/patients/{peopleId}/exercise/today")
    public ApiResult<ExerciseTodayDto> today(@PathVariable String peopleId) {
        return ApiResult.ok(recordService.today(access(peopleId), peopleId, false));
    }

    @GetMapping("/patients/{peopleId}/exercise/week")
    public ApiResult<ExerciseWeekDto> week(
            @PathVariable String peopleId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResult.ok(recordService.week(access(peopleId), peopleId, date));
    }

    @GetMapping("/patients/{peopleId}/exercise/sessions")
    public ApiResult<List<ExerciseSessionDto>> sessions(
            @PathVariable String peopleId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResult.ok(recordService.list(access(peopleId), peopleId, from, to, false));
    }

    @PostMapping("/patients/{peopleId}/exercise/sessions")
    public ApiResult<ExerciseSessionDto> create(@PathVariable String peopleId, @RequestBody SessionInput request) {
        String tenantId = access(peopleId);
        return ApiResult.ok(recordService.create(tenantId, peopleId, request, Actor.staff(SecurityUtils.requireStaffId())));
    }

    @PutMapping("/patients/{peopleId}/exercise/sessions/{id}")
    public ApiResult<ExerciseSessionDto> update(
            @PathVariable String peopleId, @PathVariable String id, @RequestBody SessionInput request) {
        String tenantId = access(peopleId);
        return ApiResult.ok(recordService.update(
                tenantId, peopleId, id, request, Actor.staff(SecurityUtils.requireStaffId())));
    }

    @DeleteMapping("/patients/{peopleId}/exercise/sessions/{id}")
    public ApiResult<Void> delete(@PathVariable String peopleId, @PathVariable String id) {
        String tenantId = access(peopleId);
        recordService.delete(tenantId, peopleId, id, Actor.staff(SecurityUtils.requireStaffId()));
        return ApiResult.ok(null);
    }

    @GetMapping("/exercise/types")
    public ApiResult<List<ExerciseTypeDto>> types(
            @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "50") int limit) {
        return ApiResult.ok(catalog.search(keyword, limit));
    }

    private String access(String peopleId) {
        String tenantId = SecurityUtils.requireTenantId();
        accessService.assertStaffCanAccessPeople(tenantId, SecurityUtils.requireCurrentOrgId(), peopleId);
        return tenantId;
    }
}
