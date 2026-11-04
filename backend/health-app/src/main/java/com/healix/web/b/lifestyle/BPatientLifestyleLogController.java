package com.healix.web.b.lifestyle;

import com.healix.common.result.ApiResult;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.mood.dto.MoodDayDto;
import com.healix.core.mood.service.MoodLogService;
import com.healix.core.sleep.dto.SleepDayDto;
import com.healix.core.sleep.service.SleepLogService;
import com.healix.core.water.dto.WaterDayDto;
import com.healix.core.water.service.WaterIntakeService;
import com.healix.security.SecurityUtils;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

/** B 端患者饮水 / 睡眠 / 心情：查看与代录。代录记录可改删，患者自录只读。 */
@Validated
@RestController
@RequestMapping("/api/b/v1/patients/{peopleId}")
@RequiredArgsConstructor
public class BPatientLifestyleLogController {

    private final SleepLogService sleepLogService;
    private final MoodLogService moodLogService;
    private final WaterIntakeService waterIntakeService;
    private final ArchiveAccessService archiveAccessService;

    public record WaterRequest(LocalDate date, Integer amountMl, LocalDateTime recordedAt) {}

    public record SleepRequest(
            String id,
            LocalDate date,
            String segment,
            Integer durationMin,
            String quality,
            String bedtime,
            String wakeTime,
            String note) {}

    public record MoodRequest(LocalDate date, Integer score, List<String> tags, String note) {}

    @GetMapping("/water/days/{date}")
    public ApiResult<WaterDayDto> waterDay(
            @PathVariable String peopleId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResult.ok(waterIntakeService.day(access(peopleId), peopleId, date));
    }

    @GetMapping("/water/today")
    public ApiResult<WaterDayDto> waterToday(
            @PathVariable String peopleId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResult.ok(waterIntakeService.day(access(peopleId), peopleId, date != null ? date : LocalDate.now()));
    }

    @PostMapping("/water/intake")
    public ApiResult<WaterDayDto> addWater(@PathVariable String peopleId, @RequestBody WaterRequest body) {
        String tenantId = access(peopleId);
        return ApiResult.ok(waterIntakeService.add(
                tenantId,
                peopleId,
                body == null ? null : body.date(),
                amountOf(body),
                null,
                body == null ? null : body.recordedAt(),
                SecurityUtils.requireStaffId()));
    }

    @PutMapping("/water/intake/{id}")
    public ApiResult<WaterDayDto> updateWater(
            @PathVariable String peopleId, @PathVariable String id, @RequestBody WaterRequest body) {
        String tenantId = access(peopleId);
        return ApiResult.ok(waterIntakeService.update(
                tenantId, peopleId, id, amountOf(body), body == null ? null : body.recordedAt(),
                SecurityUtils.requireStaffId()));
    }

    @DeleteMapping("/water/intake/{id}")
    public ApiResult<WaterDayDto> deleteWater(@PathVariable String peopleId, @PathVariable String id) {
        String tenantId = access(peopleId);
        return ApiResult.ok(waterIntakeService.delete(tenantId, peopleId, id, SecurityUtils.requireStaffId()));
    }

    @GetMapping("/sleep/days/{date}")
    public ApiResult<SleepDayDto> sleepDay(
            @PathVariable String peopleId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResult.ok(sleepLogService.day(access(peopleId), peopleId, date));
    }

    @GetMapping("/sleep/today")
    public ApiResult<SleepDayDto> sleepToday(
            @PathVariable String peopleId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResult.ok(sleepLogService.day(access(peopleId), peopleId, date != null ? date : LocalDate.now()));
    }

    @PostMapping("/sleep/log")
    public ApiResult<SleepDayDto> saveSleep(@PathVariable String peopleId, @RequestBody SleepRequest body) {
        String tenantId = access(peopleId);
        if (body == null) {
            body = new SleepRequest(null, null, null, null, null, null, null, null);
        }
        return ApiResult.ok(sleepLogService.upsert(
                tenantId,
                peopleId,
                body.id(),
                body.date(),
                body.segment(),
                body.durationMin(),
                body.quality(),
                body.bedtime(),
                body.wakeTime(),
                body.note(),
                SecurityUtils.requireStaffId()));
    }

    @DeleteMapping("/sleep/segments/{id}")
    public ApiResult<SleepDayDto> deleteSleep(@PathVariable String peopleId, @PathVariable String id) {
        String tenantId = access(peopleId);
        return ApiResult.ok(sleepLogService.delete(tenantId, peopleId, id, SecurityUtils.requireStaffId()));
    }

    @GetMapping("/mood/days/{date}")
    public ApiResult<MoodDayDto> moodDay(
            @PathVariable String peopleId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResult.ok(moodLogService.day(access(peopleId), peopleId, date));
    }

    @GetMapping("/mood/today")
    public ApiResult<MoodDayDto> moodToday(
            @PathVariable String peopleId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResult.ok(moodLogService.day(access(peopleId), peopleId, date != null ? date : LocalDate.now()));
    }

    @PostMapping("/mood/log")
    public ApiResult<MoodDayDto> saveMood(@PathVariable String peopleId, @RequestBody MoodRequest body) {
        String tenantId = access(peopleId);
        return ApiResult.ok(moodLogService.upsert(
                tenantId,
                peopleId,
                body == null ? null : body.date(),
                body == null ? null : body.score(),
                body == null ? null : body.tags(),
                body == null ? null : body.note(),
                SecurityUtils.requireStaffId()));
    }

    @DeleteMapping("/mood/days/{date}")
    public ApiResult<MoodDayDto> deleteMood(
            @PathVariable String peopleId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        String tenantId = access(peopleId);
        return ApiResult.ok(moodLogService.delete(tenantId, peopleId, date, SecurityUtils.requireStaffId()));
    }

    private static int amountOf(WaterRequest body) {
        return body != null && body.amountMl() != null ? body.amountMl() : 0;
    }

    private String access(String peopleId) {
        String tenantId = SecurityUtils.requireTenantId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, SecurityUtils.requireCurrentOrgId(), peopleId);
        return tenantId;
    }
}
