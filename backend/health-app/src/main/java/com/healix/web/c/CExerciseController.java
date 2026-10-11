package com.healix.web.c;

import com.healix.common.result.ApiResult;
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

/** C 端运动记录：今日计划、周汇总、记录增删改、运动类型检索。 */
@Validated
@RestController
@RequestMapping("/api/c/v1/me")
@RequiredArgsConstructor
public class CExerciseController {

    private final ExerciseRecordService recordService;
    private final ExerciseCatalog catalog;

    @GetMapping("/exercise/today")
    public ApiResult<ExerciseTodayDto> today() {
        return ApiResult.ok(recordService.today(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), true));
    }

    @GetMapping("/exercise/week")
    public ApiResult<ExerciseWeekDto> week(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResult.ok(recordService.week(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), date));
    }

    @GetMapping("/exercise/sessions")
    public ApiResult<List<ExerciseSessionDto>> sessions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResult.ok(recordService.list(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), from, to, true));
    }

    @GetMapping("/exercise/sessions/{id}")
    public ApiResult<ExerciseSessionDto> session(@PathVariable String id) {
        return ApiResult.ok(recordService.get(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), id, true));
    }

    @PostMapping("/exercise/sessions")
    public ApiResult<ExerciseSessionDto> create(@RequestBody SessionInput request) {
        String peopleId = SecurityUtils.requirePatientId();
        return ApiResult.ok(recordService.create(SecurityUtils.requireTenantId(), peopleId, request, Actor.patient(peopleId)));
    }

    @PutMapping("/exercise/sessions/{id}")
    public ApiResult<ExerciseSessionDto> update(@PathVariable String id, @RequestBody SessionInput request) {
        String peopleId = SecurityUtils.requirePatientId();
        return ApiResult.ok(recordService.update(
                SecurityUtils.requireTenantId(), peopleId, id, request, Actor.patient(peopleId)));
    }

    @DeleteMapping("/exercise/sessions/{id}")
    public ApiResult<Void> delete(@PathVariable String id) {
        String peopleId = SecurityUtils.requirePatientId();
        recordService.delete(SecurityUtils.requireTenantId(), peopleId, id, Actor.patient(peopleId));
        return ApiResult.ok(null);
    }

    @GetMapping("/exercise/types")
    public ApiResult<List<ExerciseTypeDto>> types(
            @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "50") int limit) {
        return ApiResult.ok(catalog.search(keyword, limit));
    }

    @GetMapping("/exercise/types/frequent")
    public ApiResult<List<ExerciseTypeDto>> frequentTypes(@RequestParam(defaultValue = "8") int limit) {
        return ApiResult.ok(recordService.frequentTypes(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), limit));
    }
}
