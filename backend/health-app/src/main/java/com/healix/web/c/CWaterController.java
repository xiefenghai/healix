package com.healix.web.c;

import com.healix.common.result.ApiResult;
import com.healix.core.water.dto.WaterDayDto;
import com.healix.core.water.service.WaterIntakeService;
import com.healix.core.water.support.WaterIntakeRules;
import com.healix.security.SecurityUtils;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/c/v1/me/water")
@RequiredArgsConstructor
public class CWaterController {

    private final WaterIntakeService waterIntakeService;

    @GetMapping("/days/{date}")
    public ApiResult<WaterDayDto> day(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResult.ok(waterIntakeService.day(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), date));
    }

    @PostMapping("/intake")
    public ApiResult<WaterDayDto> add(@RequestBody AddRequest body) {
        int ml = body != null && body.amountMl() != null ? body.amountMl() : 0;
        if (body != null && "SMALL".equalsIgnoreCase(body.cup())) {
            ml = WaterIntakeRules.CUP_SMALL_ML;
        } else if (body != null && "LARGE".equalsIgnoreCase(body.cup())) {
            ml = WaterIntakeRules.CUP_LARGE_ML;
        }
        LocalDate date = body != null && body.date() != null ? body.date() : LocalDate.now();
        String source = body != null && body.cup() != null ? "CUP" : "CUSTOM";
        return ApiResult.ok(waterIntakeService.add(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requirePatientId(),
                date,
                ml,
                source,
                resolveTime(date, body == null ? null : body.recordedAt(), body == null ? null : body.time())));
    }

    @PutMapping("/intake/{id}")
    public ApiResult<WaterDayDto> update(@PathVariable String id, @RequestBody UpdateRequest body) {
        int ml = body != null && body.amountMl() != null ? body.amountMl() : 0;
        LocalDate date = body != null ? body.date() : null;
        return ApiResult.ok(waterIntakeService.update(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requirePatientId(),
                id,
                ml,
                resolveTime(date, body == null ? null : body.recordedAt(), body == null ? null : body.time())));
    }

    @DeleteMapping("/intake/{id}")
    public ApiResult<WaterDayDto> delete(@PathVariable String id) {
        return ApiResult.ok(waterIntakeService.delete(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), id));
    }

    @GetMapping("/meta")
    public ApiResult<Map<String, Object>> meta() {
        return ApiResult.ok(Map.of(
                "cupSmallMl", WaterIntakeRules.CUP_SMALL_ML,
                "cupLargeMl", WaterIntakeRules.CUP_LARGE_ML,
                "defaultTargetMl", WaterIntakeRules.DEFAULT_TARGET_ML));
    }

    private static LocalDateTime resolveTime(LocalDate date, LocalDateTime recordedAt, String time) {
        if (recordedAt != null) {
            return recordedAt;
        }
        if (!StringUtils.hasText(time)) {
            return null;
        }
        LocalDate d = date != null ? date : LocalDate.now();
        LocalTime t = LocalTime.parse(time.trim());
        return LocalDateTime.of(d, t);
    }

    public record AddRequest(LocalDate date, Integer amountMl, String cup, LocalDateTime recordedAt, String time) {}

    public record UpdateRequest(LocalDate date, Integer amountMl, LocalDateTime recordedAt, String time) {}
}
