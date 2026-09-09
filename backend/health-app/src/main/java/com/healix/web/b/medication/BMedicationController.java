package com.healix.web.b.medication;

import com.healix.common.result.ApiResult;
import com.healix.core.medication.dto.MedicationIntakeViewDto;
import com.healix.core.medication.dto.MedicationViewDto;
import com.healix.core.medication.enums.MedicationSourceEnum;
import com.healix.core.medication.service.MedicationService;
import com.healix.core.medication.service.MedicationService.IntakeCommand;
import com.healix.core.medication.service.MedicationService.MedicationCommand;
import com.healix.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
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

/**
 * B 端用药管理接口。
 * <p>患者级用药清单、处方批量开立、依从性打卡（C 端暂不开放）。
 */
@Validated
@RestController
@RequestMapping("/api/b/v1")
@RequiredArgsConstructor
public class BMedicationController {

    private final MedicationService medicationService;

    /** 列出患者用药清单（可按 ACTIVE/STOPPED 筛选）。 */
    @GetMapping("/patients/{peopleId}/medications")
    public ApiResult<List<MedicationViewDto>> list(
            @PathVariable String peopleId, @RequestParam(required = false) String status) {
        return ApiResult.ok(medicationService.list(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                status));
    }

    /** 查询单条用药记录。 */
    @GetMapping("/patients/{peopleId}/medications/{medicationId}")
    public ApiResult<MedicationViewDto> get(
            @PathVariable String peopleId, @PathVariable String medicationId) {
        return ApiResult.ok(medicationService.get(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                medicationId));
    }

    /** 手工新增一条用药（清单录入）。 */
    @PostMapping("/patients/{peopleId}/medications")
    public ApiResult<MedicationViewDto> create(
            @PathVariable String peopleId, @RequestBody @Validated MedicationRequest request) {
        return ApiResult.ok(medicationService.create(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                toCommand(request),
                resolveStaffCreateSource(request.source()),
                null));
    }

    /** 一次处方开立多条药品（共用 prescriptionGroupId）。 */
    @PostMapping("/patients/{peopleId}/medications/prescriptions")
    public ApiResult<List<MedicationViewDto>> createPrescription(
            @PathVariable String peopleId, @RequestBody @Validated PrescriptionRequest request) {
        List<MedicationCommand> items = request.items().stream().map(this::toCommand).toList();
        return ApiResult.ok(medicationService.createPrescription(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                items));
    }

    /** 更新用药记录。 */
    @PutMapping("/patients/{peopleId}/medications/{medicationId}")
    public ApiResult<MedicationViewDto> update(
            @PathVariable String peopleId,
            @PathVariable String medicationId,
            @RequestBody @Validated MedicationRequest request) {
        return ApiResult.ok(medicationService.update(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                medicationId,
                toCommand(request)));
    }

    /** 删除用药记录（软删）。 */
    @DeleteMapping("/patients/{peopleId}/medications/{medicationId}")
    public ApiResult<Void> delete(@PathVariable String peopleId, @PathVariable String medicationId) {
        medicationService.delete(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                medicationId);
        return ApiResult.ok(null);
    }

    /** 查询某条用药的依从性打卡记录。 */
    @GetMapping("/patients/{peopleId}/medications/{medicationId}/intakes")
    public ApiResult<List<MedicationIntakeViewDto>> listIntakes(
            @PathVariable String peopleId,
            @PathVariable String medicationId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return ApiResult.ok(medicationService.listIntakes(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                medicationId,
                from,
                to));
    }

    /** 按日期查询患者当日全部依从性打卡。 */
    @GetMapping("/patients/{peopleId}/medication-intakes")
    public ApiResult<List<MedicationIntakeViewDto>> listIntakesByDate(
            @PathVariable String peopleId, @RequestParam(required = false) LocalDate date) {
        return ApiResult.ok(medicationService.listIntakesByDate(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                date));
    }

    /** 记录或更新某日某时段依从性（已服/漏服/跳过）。 */
    @PostMapping("/patients/{peopleId}/medications/{medicationId}/intakes")
    public ApiResult<MedicationIntakeViewDto> upsertIntake(
            @PathVariable String peopleId,
            @PathVariable String medicationId,
            @RequestBody @Validated IntakeRequest request) {
        return ApiResult.ok(medicationService.upsertIntake(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                medicationId,
                new IntakeCommand(request.intakeDate(), request.timeSlot(), request.status(), request.note())));
    }

    private MedicationCommand toCommand(MedicationRequest request) {
        return new MedicationCommand(
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
                request.status());
    }

    /** B 端仅允许健管手工 / 处方来源，不可冒充患者自添加。 */
    private static String resolveStaffCreateSource(String source) {
        if (source == null || source.isBlank()) {
            return MedicationSourceEnum.MANUAL.name();
        }
        String normalized = source.trim().toUpperCase();
        if (MedicationSourceEnum.PATIENT.name().equals(normalized)) {
            throw new com.healix.common.exception.BusinessException("健管端不能使用患者自添加来源");
        }
        if (MedicationSourceEnum.MANUAL.name().equals(normalized)
                || MedicationSourceEnum.PRESCRIPTION.name().equals(normalized)) {
            return normalized;
        }
        throw new com.healix.common.exception.BusinessException("无效的用药来源");
    }

    public record MedicationRequest(
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
            String source) {}

    public record PrescriptionRequest(@NotEmpty List<@NotNull MedicationRequest> items) {}

    public record IntakeRequest(
            @NotNull LocalDate intakeDate,
            @NotBlank String timeSlot,
            @NotBlank String status,
            @Size(max = 200) String note) {}
}
