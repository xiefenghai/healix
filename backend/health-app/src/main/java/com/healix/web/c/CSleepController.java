package com.healix.web.c;

import com.healix.common.result.ApiResult;
import com.healix.core.sleep.dto.SleepDayDto;
import com.healix.core.sleep.service.SleepLogService;
import com.healix.security.SecurityUtils;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/c/v1/me/sleep")
@RequiredArgsConstructor
public class CSleepController {

    private final SleepLogService sleepLogService;

    @GetMapping("/days/{date}")
    public ApiResult<SleepDayDto> day(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResult.ok(sleepLogService.day(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), date));
    }

    @PostMapping("/log")
    public ApiResult<SleepDayDto> upsert(@RequestBody UpsertRequest body) {
        LocalDate date = body != null && body.date() != null ? body.date() : LocalDate.now();
        return ApiResult.ok(sleepLogService.upsert(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requirePatientId(),
                body == null ? null : body.id(),
                date,
                body == null ? null : body.segment(),
                body == null ? null : body.durationMin(),
                body == null ? null : body.quality(),
                body == null ? null : body.bedtime(),
                body == null ? null : body.wakeTime(),
                body == null ? null : body.note()));
    }

    @DeleteMapping("/segments/{id}")
    public ApiResult<SleepDayDto> delete(@PathVariable String id) {
        return ApiResult.ok(sleepLogService.delete(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), id));
    }

    public record UpsertRequest(
            String id,
            LocalDate date,
            String segment,
            Integer durationMin,
            String quality,
            String bedtime,
            String wakeTime,
            String note) {}
}
