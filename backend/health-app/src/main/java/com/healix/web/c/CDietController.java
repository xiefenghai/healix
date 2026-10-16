package com.healix.web.c;

import com.healix.agent.diet.DietRecognitionService;
import com.healix.common.exception.BusinessException;
import com.healix.common.result.ApiResult;
import com.healix.core.diet.dto.DietDayDto;
import com.healix.core.diet.dto.DietDraftDto;
import com.healix.core.diet.dto.DietDaySummaryDto;
import com.healix.core.diet.dto.DietGoalDto;
import com.healix.core.diet.dto.DietGoalStatusDto;
import com.healix.core.diet.dto.DietMealDto;
import com.healix.core.diet.dto.FoodItemDto;
import com.healix.core.diet.service.DietGoalService;
import com.healix.core.diet.service.DietGoalService.GoalRequest;
import com.healix.core.diet.service.DietRecordService;
import com.healix.core.diet.service.DietRecordService.Actor;
import com.healix.core.diet.service.DietRecordService.FrequentFoodDto;
import com.healix.core.diet.service.DietRecordService.MealInput;
import com.healix.core.diet.service.DietRecordService.MealItemInput;
import com.healix.core.diet.service.FoodCatalogService;
import com.healix.security.SecurityUtils;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

/** C 端饮食记录：热量目标、饮食日记、餐次增删改、食物库检索。 */
@Validated
@RestController
@RequestMapping("/api/c/v1/me")
@RequiredArgsConstructor
public class CDietController {

    private final DietGoalService goalService;
    private final DietRecordService recordService;
    private final FoodCatalogService catalogService;
    private final DietRecognitionService recognitionService;

    @GetMapping("/diet/goal")
    public ApiResult<DietGoalStatusDto> goal() {
        return ApiResult.ok(goalService.status(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), true));
    }

    @PostMapping("/diet/goal/preview")
    public ApiResult<DietGoalDto> previewGoal(@RequestBody GoalRequest request) {
        return ApiResult.ok(goalService.preview(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), withoutStaffFields(request), false));
    }

    @PutMapping("/diet/goal")
    public ApiResult<DietGoalDto> saveGoal(@RequestBody GoalRequest request) {
        return ApiResult.ok(goalService.saveByPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), withoutStaffFields(request)));
    }

    @GetMapping("/diet/days/{date}")
    public ApiResult<DietDayDto> day(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResult.ok(recordService.day(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), date));
    }

    @GetMapping("/diet/summary")
    public ApiResult<List<DietDaySummaryDto>> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResult.ok(recordService.summary(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), from, to));
    }

    @GetMapping("/diet/meals/{mealId}")
    public ApiResult<DietMealDto> meal(@PathVariable String mealId) {
        return ApiResult.ok(recordService.get(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), mealId));
    }

    @PostMapping("/diet/meals")
    public ApiResult<DietMealDto> createMeal(@RequestBody MealInput request) {
        String peopleId = SecurityUtils.requirePatientId();
        return ApiResult.ok(recordService.create(SecurityUtils.requireTenantId(), peopleId, request, Actor.patient(peopleId)));
    }

    @PutMapping("/diet/meals/{mealId}")
    public ApiResult<DietMealDto> updateMeal(@PathVariable String mealId, @RequestBody MealInput request) {
        String peopleId = SecurityUtils.requirePatientId();
        return ApiResult.ok(recordService.update(
                SecurityUtils.requireTenantId(), peopleId, mealId, request, Actor.patient(peopleId)));
    }

    /** 同一餐次合并保存：mealIds 为页面上一并编辑的已有记录，items 为空即删除这些记录。 */
    @PutMapping("/diet/meal-slot")
    public ApiResult<DietMealDto> saveMealSlot(@RequestBody MealSlotRequest request) {
        String peopleId = SecurityUtils.requirePatientId();
        MealInput input = new MealInput(
                request.mealDate(), request.mealType(), request.eatenAt(), request.source(), request.note(), request.items());
        return ApiResult.ok(recordService.saveSlot(
                SecurityUtils.requireTenantId(), peopleId, request.mealIds(), input, Actor.patient(peopleId)));
    }

    public record MealSlotRequest(
            List<String> mealIds,
            LocalDate mealDate,
            String mealType,
            LocalDateTime eatenAt,
            String source,
            String note,
            List<MealItemInput> items) {}

    @DeleteMapping("/diet/meals/{mealId}")
    public ApiResult<Void> deleteMeal(@PathVariable String mealId) {
        String peopleId = SecurityUtils.requirePatientId();
        recordService.delete(SecurityUtils.requireTenantId(), peopleId, mealId, Actor.patient(peopleId));
        return ApiResult.ok(null);
    }

    /** 拍照识别：同一餐可传多张 file，一次识别并合并重复食物；只返回草稿，图片不保存。 */
    @PostMapping(value = "/diet/recognize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<DietDraftDto> recognizePhoto(
            @RequestParam("file") List<MultipartFile> files,
            @RequestParam(required = false) String mealType,
            @RequestParam(required = false) String hint) {
        SecurityUtils.requirePatientId();
        List<DietRecognitionService.Photo> photos = new ArrayList<>();
        try {
            for (MultipartFile f : files == null ? List.<MultipartFile>of() : files) {
                if (f != null && !f.isEmpty()) {
                    photos.add(new DietRecognitionService.Photo(f.getBytes(), f.getContentType()));
                }
            }
        } catch (IOException e) {
            throw new BusinessException("读取图片失败");
        }
        if (photos.isEmpty()) {
            throw new BusinessException("请上传图片");
        }
        return ApiResult.ok(recognitionService.recognizePhotos(photos, mealType, hint));
    }

    @PostMapping("/diet/recognize-text")
    public ApiResult<DietDraftDto> recognizeText(@RequestBody RecognizeTextRequest request) {
        SecurityUtils.requirePatientId();
        return ApiResult.ok(recognitionService.recognizeText(
                request == null ? null : request.text(), request == null ? null : request.mealType()));
    }

    public record RecognizeTextRequest(String text, String mealType) {}

    @GetMapping("/foods/search")
    public ApiResult<List<FoodItemDto>> searchFoods(
            @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(catalogService.search(keyword, limit));
    }

    @GetMapping("/foods/frequent")
    public ApiResult<List<FrequentFoodDto>> frequentFoods(@RequestParam(defaultValue = "12") int limit) {
        return ApiResult.ok(recordService.frequent(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), limit));
    }

    @GetMapping("/foods/{code}")
    public ApiResult<FoodItemDto> food(@PathVariable String code) {
        return ApiResult.ok(catalogService.get(code));
    }

    private static GoalRequest withoutStaffFields(GoalRequest r) {
        if (r == null) {
            return new GoalRequest(null, null, null, null, null, null, null);
        }
        return new GoalRequest(r.goalType(), r.rateKgPerWeek(), r.activityLevel(), r.heightCm(), r.weightKg(), null, null);
    }
}
