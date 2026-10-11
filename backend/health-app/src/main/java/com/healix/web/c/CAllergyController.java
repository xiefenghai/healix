package com.healix.web.c;

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

/** C 端过敏史自填：新增条目记为「患者自述」，医护确认后只读。 */
@Validated
@RestController
@RequestMapping("/api/c/v1/me")
@RequiredArgsConstructor
public class CAllergyController {

    private final AllergyService allergyService;

    @GetMapping("/allergies/catalog")
    public ApiResult<AllergyCatalogDto> catalog() {
        return ApiResult.ok(allergyService.catalog());
    }

    @GetMapping("/allergies")
    public ApiResult<AllergySummaryDto> summary() {
        return ApiResult.ok(allergyService.summaryForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId()));
    }

    @PostMapping("/allergies")
    public ApiResult<AllergyItemDto> create(@RequestBody @Validated AllergyRequest request) {
        return ApiResult.ok(allergyService.createByPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), request.toCommand()));
    }

    @PutMapping("/allergies/{allergyId}")
    public ApiResult<AllergyItemDto> update(
            @PathVariable String allergyId, @RequestBody @Validated AllergyRequest request) {
        return ApiResult.ok(allergyService.updateByPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), allergyId, request.toCommand()));
    }

    @DeleteMapping("/allergies/{allergyId}")
    public ApiResult<Void> delete(@PathVariable String allergyId) {
        allergyService.deleteByPatient(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), allergyId);
        return ApiResult.ok(null);
    }

    /** NKA（我没有过敏）/ UNKNOWN（不确定）。 */
    @PutMapping("/allergies/status")
    public ApiResult<AllergySummaryDto> setStatus(@RequestBody @Validated StatusRequest request) {
        return ApiResult.ok(allergyService.setStatusByPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), request.status()));
    }

    /** 患者端不提交核实状态，服务端固定为「患者自述」。 */
    public record AllergyRequest(
            @NotBlank String category,
            @Size(max = 48) String allergenCode,
            @Size(max = 64) String allergenName,
            String allergyType,
            @Size(max = 12) List<String> reactions,
            @Size(max = 128) String reactionNote,
            String severity,
            String clinicalStatus,
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
                    null,
                    onsetText,
                    lastOccurrence,
                    note);
        }
    }

    public record StatusRequest(@NotBlank String status) {}
}
