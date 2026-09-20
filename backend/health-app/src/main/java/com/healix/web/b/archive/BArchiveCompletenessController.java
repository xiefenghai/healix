package com.healix.web.b.archive;

import com.healix.common.result.ApiResult;
import com.healix.core.archive.dto.ArchiveCompletenessDto;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.archive.service.ArchiveCompletenessService;
import com.healix.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** B 端患者档案完整度（实时计算）。 */
@Validated
@RestController
@RequestMapping("/api/b/v1")
@RequiredArgsConstructor
public class BArchiveCompletenessController {

    private final ArchiveAccessService archiveAccessService;
    private final ArchiveCompletenessService archiveCompletenessService;

    /**
     * 查询患者档案完整度。
     *
     * <p>分母=基础档案字段字典；若已建病种档案则叠加对应病种字段。分子=有值字段数。
     */
    @GetMapping("/patients/{peopleId}/archive/completeness")
    public ApiResult<ArchiveCompletenessDto> getCompleteness(@PathVariable String peopleId) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return ApiResult.ok(archiveCompletenessService.compute(tenantId, peopleId));
    }
}
