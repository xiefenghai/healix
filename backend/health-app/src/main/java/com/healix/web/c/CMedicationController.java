package com.healix.web.c;

import com.healix.common.result.ApiResult;
import com.healix.core.medication.dto.MedicationIntakeViewDto;
import com.healix.core.medication.dto.MedicationViewDto;
import com.healix.core.medication.service.MedicationService;
import com.healix.core.medication.service.MedicationService.IntakeCommand;
import com.healix.core.medication.service.MedicationService.MedicationCommand;
import com.healix.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** C 端用药：清单查询、自助添加/编辑、服药打卡。 */
@Validated
@RestController
@RequestMapping("/api/c/v1/me")
@RequiredArgsConstructor
public class CMedicationController {

    private final MedicationService medicationService;

    @GetMapping("/medications")
    public ApiResult<List<MedicationViewDto>> list(@RequestParam(required = false) String status) {
        String filter = status;
        if (filter != null && "ALL".equalsIgnoreCase(filter.trim())) {
            filter = null;
        } else if (filter == null || filter.isBlank()) {
            filter = "ACTIVE";
        }
        return ApiResult.ok(medicationService.listForPatient(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requirePatientId(),
                filter));
    }

    @PostMapping("/medications")
    public ApiResult<MedicationViewDto> create(@RequestBody @Validated CreateMedicationRequest request) {
        return ApiResult.ok(medicationService.createByPatient(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requirePatientId(),
                new MedicationCommand(
                        request.drugName(),
                        request.usageMethod(),
                        request.frequency(),
                        request.doseAmount(),
                        request.doseUnit(),
                        request.startDate() != null ? request.startDate() : LocalDate.now(),
                        request.stopDate(),
                        request.timingNote(),
                        request.courseDays(),
                        request.hasAdverseReaction(),
                        request.remark(),
                        request.status() != null ? request.status() : "ACTIVE")));
    }

    @PutMapping("/medications/{medicationId}")
    public ApiResult<MedicationViewDto> update(
            @PathVariable String medicationId, @RequestBody @Validated CreateMedicationRequest request) {
        return ApiResult.ok(medicationService.updateByPatient(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requirePatientId(),
                medicationId,
                new MedicationCommand(
                        request.drugName(),
                        request.usageMethod(),
                        request.frequency(),
                        request.doseAmount(),
                        request.doseUnit(),
                        request.startDate(),
                        request.stopDate(),
                        request.timingNote(),
                        request.courseDays(),
                        request.hasAdverseReaction(),
                        request.remark(),
                        request.status())));
    }

    @GetMapping("/medication-intakes")
    public ApiResult<List<MedicationIntakeViewDto>> intakesToday(
            @RequestParam(required = false) LocalDate date) {
        return ApiResult.ok(medicationService.listIntakesByDateForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), date));
    }

    @PostMapping("/medications/{medicationId}/intakes")
    public ApiResult<MedicationIntakeViewDto> checkin(
            @PathVariable String medicationId, @RequestBody @Validated IntakeRequest request) {
        return ApiResult.ok(medicationService.upsertIntakeByPatient(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requirePatientId(),
                medicationId,
                new IntakeCommand(
                        request.intakeDate() != null ? request.intakeDate() : LocalDate.now(),
                        request.timeSlot(),
                        request.status(),
                        request.note())));
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
            String status) {}

    public record IntakeRequest(
            LocalDate intakeDate,
            @NotBlank String timeSlot,
            @NotBlank String status,
            @Size(max = 200) String note) {}
}
