package com.healix.core.archive.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.dto.ArchiveViewDto;
import com.healix.core.field.FieldCatalog;
import com.healix.core.field.PeopleFieldService;
import com.healix.core.field.SensitiveFieldMask;
import com.healix.core.patient.enums.PatientArchiveSourceEnum;
import com.healix.core.people.domain.PeopleBasicArchive;
import com.healix.core.people.mapper.PeopleBasicArchiveMapper;
import com.healix.core.revision.annotation.RecordFieldRevision;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BasicArchiveService {

    private final PeopleBasicArchiveMapper basicArchiveMapper;
    private final ArchiveAccessService archiveAccessService;
    private final PeopleFieldService peopleFieldService;
    private final FieldCatalog fieldCatalog;

    public ArchiveViewDto get(String tenantId, String peopleId) {
        return get(tenantId, peopleId, true);
    }

    /** @param canViewSensitive false 时对 sensitive 字段脱敏为「已采集」 */
    public ArchiveViewDto get(String tenantId, String peopleId, boolean canViewSensitive) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        peopleFieldService.ensureMigrated(tenantId, peopleId);
        PeopleBasicArchive archive = basicArchiveMapper.findByTenantAndPeople(tenantId, peopleId);
        if (archive == null) {
            ArchiveViewDto empty = new ArchiveViewDto();
            empty.setPeopleId(peopleId);
            empty.setTenantId(tenantId);
            empty.setVersion(0);
            empty.setSchemaVersion(PeopleFieldService.SCHEMA_V2);
            empty.setContentJson(JsonUtils.emptyObject());
            return empty;
        }
        ArchiveViewDto view = toView(archive);
        if (!canViewSensitive && view.getContentJson() != null) {
            com.fasterxml.jackson.databind.JsonNode raw =
                    view.getContentJson() instanceof com.fasterxml.jackson.databind.JsonNode n
                            ? n
                            : JsonUtils.readTree(JsonUtils.toJson(view.getContentJson()));
            view.setContentJson(SensitiveFieldMask.maskBasicContent(raw, fieldCatalog, false));
        }
        return view;
    }

    @Transactional
    @RecordFieldRevision(bizType = "BASIC_ARCHIVE")
    public ArchiveViewDto save(
            String tenantId,
            String peopleId,
            int expectedVersion,
            String contentJson,
            ArchiveOperatorContext operator) {
        return save(tenantId, peopleId, expectedVersion, contentJson, operator, null);
    }

    @Transactional
    @RecordFieldRevision(bizType = "BASIC_ARCHIVE")
    public ArchiveViewDto save(
            String tenantId,
            String peopleId,
            int expectedVersion,
            String contentJson,
            ArchiveOperatorContext operator,
            java.util.Map<String, String> quotesByPath) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        if (operator.orgId() != null) {
            archiveAccessService.assertStaffCanAccessPeople(tenantId, operator.orgId(), peopleId);
        }
        PeopleBasicArchive archive =
                peopleFieldService.replaceBasicArchive(
                        tenantId,
                        peopleId,
                        expectedVersion,
                        contentJson,
                        operator.toFieldSource(),
                        quotesByPath);
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
        archive.setSchemaVersion(PeopleFieldService.SCHEMA_V2);
        archive.setContentJson("{}");
        archive.setVersion(1);
        archive.setSource(PatientArchiveSourceEnum.B_SIDE.name());
        archive.setCreatedByStaffId(staffId);
        archive.setUpdatedByStaffId(staffId);
        EntityMeta.onCreate(archive);
        basicArchiveMapper.insert(archive);
        return archive;
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
