package com.healix.web.b.archive.basic;

import com.healix.common.result.ApiResult;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.dto.ArchiveViewDto;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.archive.service.ArchiveOperatorContext;
import com.healix.core.archive.service.BasicArchiveService;
import com.healix.security.SecurityUtils;
import com.healix.web.b.archive.SaveArchiveRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端基础档案接口。
 * <p>管理患者基础健康信息（现有疾病、家族史、既往史、饮食/运动/睡眠/生活习惯等）。
 */
@Validated
@RestController
@RequestMapping("/api/b/v1")
@RequiredArgsConstructor
public class BBasicArchiveController {

    private final ArchiveAccessService archiveAccessService;
    private final BasicArchiveService basicArchiveService;

    /** 查询患者基础档案全文（含版本号，用于乐观锁编辑）。 */
    @GetMapping("/patients/{peopleId}/archive/basic")
    public ApiResult<ArchiveViewDto> getBasicArchive(@PathVariable String peopleId) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return ApiResult.ok(basicArchiveService.get(tenantId, peopleId));
    }

    /** 保存患者基础档案；写入字段修订历史并同步元数据索引。 */
    @PutMapping("/patients/{peopleId}/archive/basic")
    public ApiResult<ArchiveViewDto> saveBasicArchive(
            @PathVariable String peopleId, @RequestBody @Validated SaveArchiveRequest request) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        String staffId = SecurityUtils.requireStaffId();
        return ApiResult.ok(basicArchiveService.save(
                tenantId,
                peopleId,
                request.version(),
                JsonUtils.toJson(request.contentJson()),
                ArchiveOperatorContext.staff(staffId, orgId)));
    }
}
