package com.healix.web.b.archive.disease;

import com.healix.common.result.ApiResult;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.dto.DiseaseArchiveViewDto;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.archive.service.ArchiveOperatorContext;
import com.healix.core.archive.service.DiseaseArchiveService;
import com.healix.security.SecurityUtils;
import com.healix.web.b.archive.SaveArchiveRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端病种档案接口。
 * <p>按病种（如糖尿病、高血压）维护独立 JSON 档案；首次保存即创建并记修订。
 */
@Validated
@RestController
@RequestMapping("/api/b/v1")
@RequiredArgsConstructor
public class BDiseaseArchiveController {

    private final ArchiveAccessService archiveAccessService;
    private final DiseaseArchiveService diseaseArchiveService;

    /** 列出该患者已创建的全部病种档案（用于病种 Tab 回显）。 */
    @GetMapping("/patients/{peopleId}/archive/disease")
    public ApiResult<List<DiseaseArchiveViewDto>> listDiseaseArchives(@PathVariable String peopleId) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return ApiResult.ok(diseaseArchiveService.list(tenantId, peopleId));
    }

    /**
     * 查询指定病种档案。
     * <p>尚未建档时返回空内容、version=0，供前端首次编辑。
     */
    @GetMapping("/patients/{peopleId}/archive/disease/{diseaseCode}")
    public ApiResult<DiseaseArchiveViewDto> getDiseaseArchive(
            @PathVariable String peopleId, @PathVariable String diseaseCode) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return ApiResult.ok(diseaseArchiveService.get(tenantId, peopleId, diseaseCode));
    }

    /** 保存（或首次创建）指定病种档案；记录字段修订并同步元数据。 */
    @PutMapping("/patients/{peopleId}/archive/disease/{diseaseCode}")
    public ApiResult<DiseaseArchiveViewDto> saveDiseaseArchive(
            @PathVariable String peopleId,
            @PathVariable String diseaseCode,
            @RequestBody @Validated SaveArchiveRequest request) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        String staffId = SecurityUtils.requireStaffId();
        return ApiResult.ok(diseaseArchiveService.save(
                tenantId,
                peopleId,
                diseaseCode,
                request.version(),
                JsonUtils.toJson(request.contentJson()),
                ArchiveOperatorContext.staff(staffId, orgId)));
    }
}
