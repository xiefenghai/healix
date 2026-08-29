package com.healix.core.archive.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.dto.ArchiveViewDto;
import com.healix.core.metadata.service.MetadataSyncService;
import com.healix.core.patient.enums.PatientArchiveSourceEnum;
import com.healix.core.people.domain.PeopleBasicArchive;
import com.healix.core.people.mapper.PeopleBasicArchiveMapper;
import com.healix.core.revision.annotation.RecordFieldRevision;
import com.healix.core.revision.enums.RevisionBizTypeEnum;
import com.healix.core.revision.service.FieldRevisionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BasicArchiveService {

    private static final String SCHEMA_VERSION = "1.0";
    private static final String META_BIZ = "BASIC_ARCHIVE";

    private final PeopleBasicArchiveMapper basicArchiveMapper;
    private final ArchiveAccessService archiveAccessService;
    private final MetadataSyncService metadataSyncService;
    private final FieldRevisionService fieldRevisionService;

    public ArchiveViewDto get(String tenantId, String peopleId) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        PeopleBasicArchive archive = basicArchiveMapper.findByTenantAndPeople(tenantId, peopleId);
        if (archive == null) {
            ArchiveViewDto empty = new ArchiveViewDto();
            empty.setPeopleId(peopleId);
            empty.setTenantId(tenantId);
            empty.setVersion(0);
            empty.setSchemaVersion(SCHEMA_VERSION);
            empty.setContentJson(JsonUtils.emptyObject());
            return empty;
        }
        return toView(archive);
    }

    @Transactional
    @RecordFieldRevision(bizType = "BASIC_ARCHIVE")
    public ArchiveViewDto save(
            String tenantId,
            String peopleId,
            int expectedVersion,
            String contentJson,
            ArchiveOperatorContext operator) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        if (operator.orgId() != null) {
            archiveAccessService.assertStaffCanAccessPeople(tenantId, operator.orgId(), peopleId);
        }
        String normalized = normalizeContent(contentJson);
        PeopleBasicArchive archive = basicArchiveMapper.findByTenantAndPeople(tenantId, peopleId);
        String oldJson = "{}";
        int versionBefore = 0;
        if (archive == null) {
            if (expectedVersion != 0) {
                throw new BusinessException(409, "档案版本冲突，请刷新后重试");
            }
            archive = new PeopleBasicArchive();
            archive.setTenantId(tenantId);
            archive.setPeopleId(peopleId);
            archive.setSchemaVersion(SCHEMA_VERSION);
            archive.setContentJson(normalized);
            archive.setVersion(1);
            archive.setSource(PatientArchiveSourceEnum.B_SIDE.name());
            archive.setCreatedByStaffId("STAFF".equals(operator.operatorType()) ? operator.operatorId() : null);
            archive.setUpdatedByStaffId("STAFF".equals(operator.operatorType()) ? operator.operatorId() : null);
            EntityMeta.onCreate(archive);
            basicArchiveMapper.insert(archive);
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
            basicArchiveMapper.updateContent(archive);
        }

        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                operator.operatorType(),
                operator.operatorId(),
                RevisionBizTypeEnum.BASIC_ARCHIVE.name(),
                null,
                versionBefore,
                archive.getVersion(),
                operator.orgId(),
                oldJson,
                normalized);

        metadataSyncService.syncBasicArchive(
                tenantId, peopleId, normalized, META_BIZ, operator.sourceClientCode());
        return toView(archive);
    }

    public PeopleBasicArchive ensureEmpty(String tenantId, String peopleId, String staffId) {
        PeopleBasicArchive archive = basicArchiveMapper.findByTenantAndPeople(tenantId, peopleId);
        if (archive != null) {
            return archive;
        }
        archive = new PeopleBasicArchive();
        archive.setTenantId(tenantId);
        archive.setPeopleId(peopleId);
        archive.setSchemaVersion(SCHEMA_VERSION);
        archive.setContentJson("{}");
        archive.setVersion(1);
        archive.setSource(PatientArchiveSourceEnum.B_SIDE.name());
        archive.setCreatedByStaffId(staffId);
        archive.setUpdatedByStaffId(staffId);
        EntityMeta.onCreate(archive);
        basicArchiveMapper.insert(archive);
        return archive;
    }

    private static String normalizeContent(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return "{}";
        }
        return JsonUtils.toJson(JsonUtils.readTree(contentJson));
    }

    private static ArchiveViewDto toView(PeopleBasicArchive archive) {
        ArchiveViewDto dto = new ArchiveViewDto();
        dto.setPeopleId(archive.getPeopleId());
        dto.setTenantId(archive.getTenantId());
        dto.setVersion(archive.getVersion());
        dto.setSchemaVersion(archive.getSchemaVersion());
        dto.setContentJson(JsonUtils.readTree(archive.getContentJson()));
        return dto;
    }
}
