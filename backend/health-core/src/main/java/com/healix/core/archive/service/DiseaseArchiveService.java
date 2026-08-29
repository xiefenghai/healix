package com.healix.core.archive.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.domain.PeopleDiseaseArchive;
import com.healix.core.archive.dto.DiseaseArchiveViewDto;
import com.healix.core.archive.mapper.PeopleDiseaseArchiveMapper;
import com.healix.core.metadata.service.MetadataSyncService;
import com.healix.core.patient.enums.PatientArchiveSourceEnum;
import com.healix.core.revision.annotation.RecordFieldRevision;
import com.healix.core.revision.enums.RevisionBizTypeEnum;
import com.healix.core.revision.service.FieldRevisionService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DiseaseArchiveService {

    private static final String SCHEMA_VERSION = "1.0";
    private static final String META_BIZ = "DISEASE_ARCHIVE";

    private final PeopleDiseaseArchiveMapper diseaseArchiveMapper;
    private final ArchiveAccessService archiveAccessService;
    private final MetadataSyncService metadataSyncService;
    private final FieldRevisionService fieldRevisionService;

    public List<DiseaseArchiveViewDto> list(String tenantId, String peopleId) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        List<DiseaseArchiveViewDto> out = new ArrayList<>();
        for (PeopleDiseaseArchive row : diseaseArchiveMapper.listByTenantAndPeople(tenantId, peopleId)) {
            out.add(toView(row));
        }
        return out;
    }

    public DiseaseArchiveViewDto get(String tenantId, String peopleId, String diseaseCode) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        PeopleDiseaseArchive archive =
                diseaseArchiveMapper.findByTenantPeopleAndCode(tenantId, peopleId, diseaseCode);
        if (archive == null) {
            DiseaseArchiveViewDto empty = new DiseaseArchiveViewDto();
            empty.setPeopleId(peopleId);
            empty.setDiseaseCode(diseaseCode);
            empty.setVersion(0);
            empty.setSchemaVersion(SCHEMA_VERSION);
            empty.setContentJson(JsonUtils.emptyObject());
            return empty;
        }
        return toView(archive);
    }

    @Transactional
    @RecordFieldRevision(bizType = "DISEASE_ARCHIVE")
    public DiseaseArchiveViewDto save(
            String tenantId,
            String peopleId,
            String diseaseCode,
            int expectedVersion,
            String contentJson,
            ArchiveOperatorContext operator) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        if (operator.orgId() != null) {
            archiveAccessService.assertStaffCanAccessPeople(tenantId, operator.orgId(), peopleId);
        }
        String normalized = normalizeContent(contentJson);
        PeopleDiseaseArchive archive =
                diseaseArchiveMapper.findByTenantPeopleAndCode(tenantId, peopleId, diseaseCode);
        String oldJson = "{}";
        int versionBefore = 0;
        if (archive == null) {
            if (expectedVersion != 0) {
                throw new BusinessException(409, "档案版本冲突，请刷新后重试");
            }
            archive = new PeopleDiseaseArchive();
            archive.setTenantId(tenantId);
            archive.setPeopleId(peopleId);
            archive.setDiseaseCode(diseaseCode);
            archive.setSchemaVersion(SCHEMA_VERSION);
            archive.setContentJson(normalized);
            archive.setVersion(1);
            archive.setSource(PatientArchiveSourceEnum.B_SIDE.name());
            archive.setCreatedByStaffId("STAFF".equals(operator.operatorType()) ? operator.operatorId() : null);
            archive.setUpdatedByStaffId("STAFF".equals(operator.operatorType()) ? operator.operatorId() : null);
            EntityMeta.onCreate(archive);
            diseaseArchiveMapper.insert(archive);
        } else {
            versionBefore = archive.getVersion() == null ? 1 : archive.getVersion();
            if (expectedVersion != versionBefore) {
                throw new BusinessException(409, "档案版本冲突，请刷新后重试");
            }
            oldJson = archive.getContentJson() == null ? "{}" : archive.getContentJson();
            archive.setContentJson(normalized);
            archive.setSchemaVersion(SCHEMA_VERSION);
            archive.setVersion(versionBefore + 1);
            if ("STAFF".equals(operator.operatorType())) {
                archive.setUpdatedByStaffId(operator.operatorId());
            }
            EntityMeta.onUpdate(archive);
            diseaseArchiveMapper.updateContent(archive);
        }

        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                operator.operatorType(),
                operator.operatorId(),
                RevisionBizTypeEnum.DISEASE_ARCHIVE.name(),
                diseaseCode,
                versionBefore,
                archive.getVersion(),
                operator.orgId(),
                oldJson,
                normalized);

        metadataSyncService.syncDiseaseArchive(
                tenantId, peopleId, diseaseCode, normalized, META_BIZ, operator.sourceClientCode());
        return toView(archive);
    }

    private static String normalizeContent(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return "{}";
        }
        return JsonUtils.toJson(JsonUtils.readTree(contentJson));
    }

    private static DiseaseArchiveViewDto toView(PeopleDiseaseArchive archive) {
        DiseaseArchiveViewDto dto = new DiseaseArchiveViewDto();
        dto.setPeopleId(archive.getPeopleId());
        dto.setDiseaseCode(archive.getDiseaseCode());
        dto.setVersion(archive.getVersion());
        dto.setSchemaVersion(archive.getSchemaVersion());
        dto.setContentJson(JsonUtils.readTree(archive.getContentJson()));
        return dto;
    }
}
