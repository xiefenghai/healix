package com.healix.web.b.diet;

import com.healix.common.result.ApiResult;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.diet.dto.DietDayDto;
import com.healix.core.diet.dto.DietDaySummaryDto;
import com.healix.core.diet.dto.DietGoalDto;
import com.healix.core.diet.dto.DietGoalStatusDto;
import com.healix.core.diet.dto.DietMealDto;
import com.healix.core.diet.dto.FoodItemDto;
import com.healix.core.diet.service.DietGoalService;
import com.healix.core.diet.service.DietGoalService.GoalRequest;
import com.healix.core.diet.service.DietRecordService;
import com.healix.core.diet.service.DietRecordService.Actor;
import com.healix.core.diet.service.DietRecordService.MealInput;
import com.healix.core.diet.service.FoodCatalogService;
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

/** B 端患者饮食：热量目标设定、饮食记录查看 / 代录 / 点评。代录记录可改删，患者自录只读。 */
@Validated
@RestController
@RequestMapping("/api/b/v1")
@RequiredArgsConstructor
public class BDietController {

    private final DietGoalService goalService;
    private final DietRecordService recordService;
    private final FoodCatalogService catalogService;
    private final ArchiveAccessService accessService;

    public record CommentRequest(String comment) {}

    @GetMapping("/patients/{peopleId}/diet/goal")
    public ApiResult<DietGoalStatusDto> goal(@PathVariable String peopleId) {
        String tenantId = access(peopleId);
        return ApiResult.ok(goalService.status(tenantId, peopleId, false));
    }

    @PostMapping("/patients/{peopleId}/diet/goal/preview")
    public ApiResult<DietGoalDto> previewGoal(@PathVariable String peopleId, @RequestBody GoalRequest request) {
        String tenantId = access(peopleId);
        return ApiResult.ok(goalService.preview(tenantId, peopleId, request, true));
    }

    @PutMapping("/patients/{peopleId}/diet/goal")
    public ApiResult<DietGoalDto> saveGoal(@PathVariable String peopleId, @RequestBody GoalRequest request) {
        String tenantId = access(peopleId);
        return ApiResult.ok(goalService.saveByStaff(tenantId, peopleId, SecurityUtils.requireStaffId(), request));
    }

    @DeleteMapping("/patients/{peopleId}/diet/goal/override")
    public ApiResult<DietGoalDto> clearOverride(@PathVariable String peopleId) {
        String tenantId = access(peopleId);
        return ApiResult.ok(goalService.clearOverride(tenantId, peopleId));
    }

    @GetMapping("/patients/{peopleId}/diet/days/{date}")
    public ApiResult<DietDayDto> day(
            @PathVariable String peopleId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        String tenantId = access(peopleId);
        return ApiResult.ok(recordService.day(tenantId, peopleId, date));
    }

    @GetMapping("/patients/{peopleId}/diet/summary")
    public ApiResult<List<DietDaySummaryDto>> summary(
            @PathVariable String peopleId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String tenantId = access(peopleId);
        return ApiResult.ok(recordService.summary(tenantId, peopleId, from, to));
    }

    @PostMapping("/patients/{peopleId}/diet/meals")
    public ApiResult<DietMealDto> createMeal(@PathVariable String peopleId, @RequestBody MealInput request) {
        String tenantId = access(peopleId);
        return ApiResult.ok(recordService.create(tenantId, peopleId, request, Actor.staff(SecurityUtils.requireStaffId())));
    }

    @PutMapping("/patients/{peopleId}/diet/meals/{mealId}")
    public ApiResult<DietMealDto> updateMeal(
            @PathVariable String peopleId, @PathVariable String mealId, @RequestBody MealInput request) {
        String tenantId = access(peopleId);
        return ApiResult.ok(recordService.update(
                tenantId, peopleId, mealId, request, Actor.staff(SecurityUtils.requireStaffId())));
    }

    @DeleteMapping("/patients/{peopleId}/diet/meals/{mealId}")
    public ApiResult<Void> deleteMeal(@PathVariable String peopleId, @PathVariable String mealId) {
        String tenantId = access(peopleId);
        recordService.delete(tenantId, peopleId, mealId, Actor.staff(SecurityUtils.requireStaffId()));
        return ApiResult.ok(null);
    }

    @PutMapping("/patients/{peopleId}/diet/meals/{mealId}/comment")
    public ApiResult<DietMealDto> comment(
            @PathVariable String peopleId, @PathVariable String mealId, @RequestBody CommentRequest request) {
        String tenantId = access(peopleId);
        return ApiResult.ok(recordService.comment(
                tenantId, peopleId, mealId, SecurityUtils.requireStaffId(), request == null ? null : request.comment()));
    }

    @GetMapping("/foods/search")
    public ApiResult<List<FoodItemDto>> searchFoods(
            @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(catalogService.search(keyword, limit));
    }

    private String access(String peopleId) {
        String tenantId = SecurityUtils.requireTenantId();
        accessService.assertStaffCanAccessPeople(tenantId, SecurityUtils.requireCurrentOrgId(), peopleId);
        return tenantId;
    }
}
