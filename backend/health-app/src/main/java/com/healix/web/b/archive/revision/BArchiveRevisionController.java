package com.healix.web.b.archive.revision;

import com.healix.common.result.ApiResult;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.revision.dto.RevisionBatchViewDto;
import com.healix.core.revision.service.RevisionQueryService;
import com.healix.security.SecurityUtils;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端患者修订历史接口。
 * <p>患者级操作修订批次：基础档案、病种档案、用药管理、依从性打卡等。
 */
@Validated
@RestController
@RequestMapping("/api/b/v1")
@RequiredArgsConstructor
public class BArchiveRevisionController {

    private final ArchiveAccessService archiveAccessService;
    private final RevisionQueryService revisionQueryService;

    /**
     * 查询患者修订批次列表（默认返回全部业务类型，按时间倒序）。
     *
     * @param bizType 可选，如 BASIC_ARCHIVE / DISEASE_ARCHIVE / MEDICATION / MEDICATION_INTAKE
     * @param bizKey  可选，病种 code 或用药记录 ID
     */
    @GetMapping("/patients/{peopleId}/archive/revisions")
    public ApiResult<List<RevisionBatchViewDto>> listRevisions(
            @PathVariable String peopleId,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String bizKey) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return ApiResult.ok(revisionQueryService.listRevisions(tenantId, peopleId, bizType, bizKey));
    }
}
