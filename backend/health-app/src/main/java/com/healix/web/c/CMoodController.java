package com.healix.web.c;

import com.healix.common.result.ApiResult;
import com.healix.core.mood.dto.MoodDayDto;
import com.healix.core.mood.service.MoodLogService;
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
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/c/v1/me/mood")
@RequiredArgsConstructor
public class CMoodController {

    private final MoodLogService moodLogService;

    @GetMapping("/days/{date}")
    public ApiResult<MoodDayDto> day(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResult.ok(moodLogService.day(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), date));
    }

    @PostMapping("/log")
    public ApiResult<MoodDayDto> upsert(@RequestBody UpsertRequest body) {
        LocalDate date = body != null && body.date() != null ? body.date() : LocalDate.now();
        return ApiResult.ok(moodLogService.upsert(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requirePatientId(),
                date,
                body == null ? null : body.score(),
                body == null ? null : body.tags(),
                body == null ? null : body.note()));
    }

    public record UpsertRequest(LocalDate date, Integer score, List<String> tags, String note) {}
}
