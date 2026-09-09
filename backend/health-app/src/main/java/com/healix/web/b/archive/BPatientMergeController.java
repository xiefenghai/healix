package com.healix.web.b.archive;

import com.healix.common.exception.BusinessException;
import com.healix.common.result.ApiResult;
import com.healix.core.identity.enums.StaffRoleEnum;
import com.healix.core.people.service.PatientMergeService;
import com.healix.core.people.service.PatientMergeService.MergeResult;
import com.healix.core.portal.enums.PortalEnum;
import com.healix.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 患者档案合并（同人重复建档收口），仅租户管理员可用。 */
@Validated
@RestController
@RequestMapping("/api/b/v1/patients")
@RequiredArgsConstructor
public class BPatientMergeController {

    private final PatientMergeService patientMergeService;

    @PostMapping("/merge")
    public ApiResult<MergeResult> merge(@RequestBody @Validated MergeRequest request) {
        var ctx = SecurityUtils.requireContext();
        if (!ctx.getRoles().contains(StaffRoleEnum.TENANT_ADMIN.name())) {
            throw new BusinessException(403, "仅租户管理员可合并档案");
        }
        return ApiResult.ok(patientMergeService.merge(
                ctx.getTenantId(),
                request.sourcePeopleId(),
                request.targetPeopleId(),
                ctx.getAccountId(),
                "STAFF",
                PortalEnum.B.code(),
                request.reason()));
    }

    /**
     * @param sourcePeopleId 被合并（将作废）的档案
     * @param targetPeopleId 保留的档案
     */
    public record MergeRequest(
            @NotBlank String sourcePeopleId, @NotBlank String targetPeopleId, String reason) {}
}
