package com.healix.web.c;

import com.healix.agent.ocr.MedOcrRecognitionService;
import com.healix.common.exception.BusinessException;
import com.healix.common.result.ApiResult;
import com.healix.core.medication.dto.DrugCatalogItemDto;
import com.healix.core.medication.dto.MedOcrPrefillDto;
import com.healix.core.medication.dto.MedicationAdherenceDto;
import com.healix.core.medication.dto.MedicationFeedbackDto;
import com.healix.core.medication.dto.MedicationIntakeViewDto;
import com.healix.core.medication.dto.MedicationReminderDto;
import com.healix.core.medication.dto.MedicationScheduleDto;
import com.healix.core.medication.dto.MedicationViewDto;
import com.healix.core.medication.service.DrugCatalogService;
import com.healix.core.medication.service.MedicationAdherenceService;
import com.healix.core.medication.service.MedicationAllergyGuard;
import com.healix.core.medication.service.MedicationFeedbackService;
import com.healix.core.medication.service.MedicationFeedbackService.FeedbackCommand;
import com.healix.core.medication.service.MedicationReminderService;
import com.healix.core.medication.service.MedicationScheduleService;
import com.healix.core.medication.service.MedicationService;
import com.healix.core.medication.service.MedicationService.IntakeCommand;
import com.healix.core.medication.service.MedicationService.MedicationCommand;
import com.healix.core.medication.support.MedicationSlotRules;
import com.healix.security.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
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

/** C 端用药：今日服药、药箱、服药记录、提醒设置、反馈与停药、拍照添加。 */
@Validated
@RestController
@RequestMapping("/api/c/v1/me")
@RequiredArgsConstructor
public class CMedicationController {

    private final MedicationService medicationService;
    private final MedicationScheduleService scheduleService;
    private final MedicationAdherenceService adherenceService;
    private final MedicationReminderService reminderService;
    private final MedicationFeedbackService feedbackService;
    private final MedicationAllergyGuard allergyGuard;
    private final DrugCatalogService drugCatalogService;
    private final MedOcrRecognitionService medOcrRecognitionService;

    // ------------------------------------------------------------------ 药箱

    @GetMapping("/medications")
    public ApiResult<List<MedicationViewDto>> list(@RequestParam(required = false) String status) {
        String filter = status;
        if (filter != null && "ALL".equalsIgnoreCase(filter.trim())) {
            filter = null;
        } else if (filter == null || filter.isBlank()) {
            filter = "ACTIVE";
        }
        return ApiResult.ok(medicationService.listForPatient(tenantId(), peopleId(), filter));
    }

    @GetMapping("/medications/{medicationId}")
    public ApiResult<MedicationViewDto> detail(@PathVariable String medicationId) {
        return ApiResult.ok(medicationService.getForPatient(tenantId(), peopleId(), medicationId));
    }

    @PostMapping("/medications")
    public ApiResult<MedicationViewDto> create(@RequestBody @Validated CreateMedicationRequest request) {
        return ApiResult.ok(medicationService.createByPatient(tenantId(), peopleId(), toCommand(request, true)));
    }

    /** 拍照识别确认后批量添加。 */
    @PostMapping("/medications/batch")
    public ApiResult<List<MedicationViewDto>> createBatch(@RequestBody @Validated BatchCreateRequest request) {
        List<MedicationCommand> items = request.items().stream().map(r -> toCommand(r, true)).toList();
        return ApiResult.ok(medicationService.createBatchByPatient(tenantId(), peopleId(), items));
    }

    @PutMapping("/medications/{medicationId}")
    public ApiResult<MedicationViewDto> update(
            @PathVariable String medicationId, @RequestBody @Validated CreateMedicationRequest request) {
        return ApiResult.ok(medicationService.updateByPatient(
                tenantId(), peopleId(), medicationId, toCommand(request, false)));
    }

    @DeleteMapping("/medications/{medicationId}")
    public ApiResult<Void> delete(@PathVariable String medicationId) {
        medicationService.deleteByPatient(tenantId(), peopleId(), medicationId);
        return ApiResult.ok(null);
    }

    @PostMapping("/medications/{medicationId}/stop")
    public ApiResult<MedicationViewDto> stop(
            @PathVariable String medicationId, @RequestBody @Validated StopRequest request) {
        return ApiResult.ok(medicationService.stopByPatient(
                tenantId(), peopleId(), medicationId, request.reason(), request.note()));
    }

    @PostMapping("/medications/{medicationId}/feedback")
    public ApiResult<MedicationFeedbackDto> feedback(
            @PathVariable String medicationId, @RequestBody @Validated FeedbackRequest request) {
        return ApiResult.ok(feedbackService.submitByPatient(
                tenantId(),
                peopleId(),
                medicationId,
                new FeedbackCommand(request.kind(), request.symptoms(), request.severity(), request.content())));
    }

    @GetMapping("/medications/{medicationId}/feedback")
    public ApiResult<List<MedicationFeedbackDto>> feedbackList(@PathVariable String medicationId) {
        return ApiResult.ok(feedbackService.listByMedicationForPatient(tenantId(), peopleId(), medicationId));
    }

    /** 保存前预检药物过敏；命中只提醒不拦截。 */
    @PostMapping("/medications/allergy-check")
    public ApiResult<List<String>> allergyCheck(@RequestBody AllergyCheckRequest request) {
        String peopleId = peopleId();
        if (request == null || !StringUtils.hasText(request.drugName()) && !StringUtils.hasText(request.drugCode())) {
            return ApiResult.ok(List.of());
        }
        return ApiResult.ok(allergyGuard.check(peopleId, request.drugCode(), request.drugName()));
    }

    /** 拍药盒 / 处方识别，只返回预填列表，不落库。 */
    @PostMapping(value = "/medications/ocr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<MedOcrPrefillDto> ocr(@RequestParam("file") MultipartFile file) {
        peopleId();
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请上传图片");
        }
        try {
            return ApiResult.ok(medOcrRecognitionService.recognize(file.getBytes(), file.getContentType()));
        } catch (IOException e) {
            throw new BusinessException("读取图片失败");
        }
    }

    @GetMapping("/drug-catalog")
    public ApiResult<List<DrugCatalogItemDto>> drugCatalog(
            @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "20") int limit) {
        peopleId();
        return ApiResult.ok(drugCatalogService.search(keyword, Math.max(1, Math.min(limit, 50))));
    }

    // ------------------------------------------------------------------ 打卡

    /** 某日时间线（今天 / 昨天可打卡）。 */
    @GetMapping("/medications/schedule")
    public ApiResult<MedicationScheduleDto> schedule(@RequestParam(required = false) LocalDate date) {
        return ApiResult.ok(scheduleService.build(tenantId(), peopleId(), date));
    }

    @GetMapping("/medication-intakes")
    public ApiResult<List<MedicationIntakeViewDto>> intakesByDate(@RequestParam(required = false) LocalDate date) {
        return ApiResult.ok(medicationService.listIntakesByDateForPatient(tenantId(), peopleId(), date));
    }

    /** 定时药按时段打卡；需要时服用的药不传时段即「记录一次」。 */
    @PostMapping("/medications/{medicationId}/intakes")
    public ApiResult<MedicationIntakeViewDto> checkin(
            @PathVariable String medicationId, @RequestBody @Validated IntakeRequest request) {
        LocalDate day = request.intakeDate() != null ? request.intakeDate() : MedicationScheduleService.today();
        if (!StringUtils.hasText(request.timeSlot()) || MedicationSlotRules.PRN.equalsIgnoreCase(request.timeSlot())) {
            return ApiResult.ok(medicationService.addPrnDoseByPatient(
                    tenantId(), peopleId(), medicationId, day, request.takenAt()));
        }
        if (!StringUtils.hasText(request.status())) {
            throw new BusinessException("请选择服药状态");
        }
        return ApiResult.ok(medicationService.upsertIntakeByPatient(
                tenantId(),
                peopleId(),
                medicationId,
                new IntakeCommand(day, request.timeSlot(), request.status(), request.note(), request.takenAt())));
    }

    /** 「全部已服」：同一时段多药一起打卡。 */
    @PostMapping("/medications/intakes/batch")
    public ApiResult<List<MedicationIntakeViewDto>> batchCheckin(@RequestBody @Validated BatchIntakeRequest request) {
        LocalDate day = request.intakeDate() != null ? request.intakeDate() : MedicationScheduleService.today();
        return ApiResult.ok(medicationService.batchIntakeByPatient(
                tenantId(),
                peopleId(),
                request.medicationIds(),
                new IntakeCommand(day, request.timeSlot(), request.status(), null, request.takenAt())));
    }

    @DeleteMapping("/medications/{medicationId}/intakes/{intakeId}")
    public ApiResult<Void> undoCheckin(@PathVariable String medicationId, @PathVariable String intakeId) {
        medicationService.deleteIntakeByPatient(tenantId(), peopleId(), medicationId, intakeId);
        return ApiResult.ok(null);
    }

    @GetMapping("/medications/adherence")
    public ApiResult<MedicationAdherenceDto> adherence(@RequestParam(defaultValue = "14") int days) {
        return ApiResult.ok(adherenceService.range(tenantId(), peopleId(), Math.min(days, 31)));
    }

    // ------------------------------------------------------------------ 提醒

    @GetMapping("/medications/reminder")
    public ApiResult<MedicationReminderDto> reminder() {
        return ApiResult.ok(reminderService.get(tenantId(), peopleId()));
    }

    @PutMapping("/medications/reminder")
    public ApiResult<MedicationReminderDto> saveReminder(@RequestBody @NotNull MedicationReminderDto request) {
        return ApiResult.ok(reminderService.save(tenantId(), peopleId(), request));
    }

    // ------------------------------------------------------------------

    private static String tenantId() {
        return SecurityUtils.requireTenantId();
    }

    private static String peopleId() {
        return SecurityUtils.requirePatientId();
    }

    private static MedicationCommand toCommand(CreateMedicationRequest r, boolean create) {
        LocalDate start = r.startDate() != null || !create ? r.startDate() : MedicationScheduleService.today();
        String status = r.status() != null || !create ? r.status() : "ACTIVE";
        return new MedicationCommand(
                r.drugName(),
                r.usageMethod(),
                r.frequency(),
                r.doseAmount(),
                r.doseUnit(),
                start,
                r.stopDate(),
                r.timingNote(),
                r.courseDays(),
                r.hasAdverseReaction(),
                r.remark(),
                status,
                r.drugCode(),
                r.slots());
    }

    public record CreateMedicationRequest(
            @NotBlank String drugName,
            @NotBlank String usageMethod,
            String frequency,
            String doseAmount,
            String doseUnit,
            LocalDate startDate,
            LocalDate stopDate,
            @Size(max = 128) String timingNote,
            Integer courseDays,
            Boolean hasAdverseReaction,
            @Size(max = 150) String remark,
            String status,
            @Size(max = 64) String drugCode,
            List<String> slots) {}

    public record BatchCreateRequest(@NotEmpty @Size(max = 10) List<@NotNull @Valid CreateMedicationRequest> items) {}

    public record IntakeRequest(
            LocalDate intakeDate,
            String timeSlot,
            String status,
            @Size(max = 200) String note,
            LocalDateTime takenAt) {}

    public record BatchIntakeRequest(
            LocalDate intakeDate,
            @NotBlank String timeSlot,
            @NotBlank String status,
            @NotEmpty List<String> medicationIds,
            LocalDateTime takenAt) {}

    public record StopRequest(@NotBlank String reason, @Size(max = 200) String note) {}

    public record FeedbackRequest(
            @NotBlank String kind, List<String> symptoms, String severity, @Size(max = 500) String content) {}

    public record AllergyCheckRequest(String drugCode, String drugName) {}
}
