package com.healix.web.b;

import com.healix.common.result.ApiResult;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.dto.ArchiveViewDto;
import com.healix.core.archive.dto.DiseaseArchiveViewDto;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.archive.service.ArchiveOperatorContext;
import com.healix.core.archive.service.BasicArchiveService;
import com.healix.core.archive.service.DiseaseArchiveService;
import com.healix.core.dict.dto.DictItemDto;
import com.healix.core.dict.service.DictService;
import com.healix.core.revision.dto.RevisionBatchViewDto;
import com.healix.core.revision.service.RevisionQueryService;
import com.healix.core.workspace.dto.OrgPatientListItem;
import com.healix.core.workspace.service.OrgWorkspaceService;
import com.healix.security.SecurityUtils;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/b/v1")
@RequiredArgsConstructor
public class BArchiveController {

    private final DictService dictService;
    private final ArchiveAccessService archiveAccessService;
    private final BasicArchiveService basicArchiveService;
    private final DiseaseArchiveService diseaseArchiveService;
    private final RevisionQueryService revisionQueryService;
    private final OrgWorkspaceService orgWorkspaceService;

    @GetMapping("/dict")
    public ApiResult<List<DictItemDto>> listDict(
            @RequestParam String dictType, @RequestParam(required = false) String parentCode) {
        String tenantId = SecurityUtils.requireTenantId();
        return ApiResult.ok(dictService.listMerged(tenantId, dictType, parentCode));
    }

    @GetMapping("/patients/{peopleId}")
    public ApiResult<OrgPatientListItem> getPatient(@PathVariable String peopleId) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        return ApiResult.ok(orgWorkspaceService.getOrgPatient(orgId, peopleId));
    }

    @GetMapping("/patients/{peopleId}/archive/basic")
    public ApiResult<ArchiveViewDto> getBasicArchive(@PathVariable String peopleId) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return ApiResult.ok(basicArchiveService.get(tenantId, peopleId));
    }

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

    @GetMapping("/patients/{peopleId}/archive/disease")
    public ApiResult<List<DiseaseArchiveViewDto>> listDiseaseArchives(@PathVariable String peopleId) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return ApiResult.ok(diseaseArchiveService.list(tenantId, peopleId));
    }

    @GetMapping("/patients/{peopleId}/archive/disease/{diseaseCode}")
    public ApiResult<DiseaseArchiveViewDto> getDiseaseArchive(
            @PathVariable String peopleId, @PathVariable String diseaseCode) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return ApiResult.ok(diseaseArchiveService.get(tenantId, peopleId, diseaseCode));
    }

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

    public record SaveArchiveRequest(@NotNull Integer version, @NotNull Map<String, Object> contentJson) {}
}
