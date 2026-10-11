package com.healix.web.b.allergy;

import com.healix.common.result.ApiResult;
import com.healix.core.allergy.dto.AllergyCatalogDto;
import com.healix.core.allergy.dto.AllergyItemDto;
import com.healix.core.allergy.dto.AllergySummaryDto;
import com.healix.core.allergy.service.AllergyService;
import com.healix.core.allergy.service.AllergyService.AllergyCommand;
import com.healix.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RestController;

/** B 端过敏史：录入、审核患者自述、设置「无已知过敏」与核对。 */
@Validated
@RestController
@RequestMapping("/api/b/v1")
@RequiredArgsConstructor
public class BAllergyController {

    private final AllergyService allergyService;

    @GetMapping("/allergies/catalog")
    public ApiResult<AllergyCatalogDto> catalog() {
        return ApiResult.ok(allergyService.catalog());
    }

    @GetMapping("/patients/{peopleId}/allergies")
    public ApiResult<AllergySummaryDto> summary(@PathVariable String peopleId) {
        return ApiResult.ok(allergyService.summaryForStaff(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), peopleId));
    }

    @PostMapping("/patients/{peopleId}/allergies")
    public ApiResult<AllergyItemDto> create(@PathVariable String peopleId, @RequestBody @Validated AllergyRequest request) {
        return ApiResult.ok(allergyService.createByStaff(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                request.toCommand()));
    }

    @PutMapping("/patients/{peopleId}/allergies/{allergyId}")
    public ApiResult<AllergyItemDto> update(
            @PathVariable String peopleId,
            @PathVariable String allergyId,
            @RequestBody @Validated AllergyRequest request) {
        return ApiResult.ok(allergyService.updateByStaff(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                allergyId,
                request.toCommand()));
    }

    /** 确认 / 排除（CONFIRMED / REFUTED）。 */
    @PutMapping("/patients/{peopleId}/allergies/{allergyId}/verification")
    public ApiResult<AllergyItemDto> verify(
            @PathVariable String peopleId,
            @PathVariable String allergyId,
            @RequestBody @Validated VerificationRequest request) {
        return ApiResult.ok(allergyService.verify(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                allergyId,
                request.verification()));
    }

    @DeleteMapping("/patients/{peopleId}/allergies/{allergyId}")
    public ApiResult<Void> delete(@PathVariable String peopleId, @PathVariable String allergyId) {
        allergyService.deleteByStaff(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                allergyId);
        return ApiResult.ok(null);
    }

    /** NKA（无已知过敏）/ UNKNOWN（未询问）。 */
    @PutMapping("/patients/{peopleId}/allergies/status")
    public ApiResult<AllergySummaryDto> setStatus(
            @PathVariable String peopleId, @RequestBody @Validated StatusRequest request) {
        return ApiResult.ok(allergyService.setStatusByStaff(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                request.status()));
    }

    @PostMapping("/patients/{peopleId}/allergies/review")
    public ApiResult<AllergySummaryDto> review(@PathVariable String peopleId) {
        return ApiResult.ok(allergyService.markReviewed(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId()));
    }

    public record AllergyRequest(
            @NotBlank String category,
            @Size(max = 48) String allergenCode,
            @Size(max = 64) String allergenName,
            String allergyType,
            @Size(max = 12) List<String> reactions,
            @Size(max = 128) String reactionNote,
            String severity,
            String clinicalStatus,
            String verification,
            @Size(max = 32) String onsetText,
            LocalDate lastOccurrence,
            @Size(max = 200) String note) {

        AllergyCommand toCommand() {
            return new AllergyCommand(
                    category,
                    allergenCode,
                    allergenName,
                    allergyType,
                    reactions,
                    reactionNote,
                    severity,
                    clinicalStatus,
                    verification,
                    onsetText,
                    lastOccurrence,
                    note);
        }
    }

    public record VerificationRequest(@NotBlank String verification) {}

    public record StatusRequest(@NotBlank String status) {}
}
