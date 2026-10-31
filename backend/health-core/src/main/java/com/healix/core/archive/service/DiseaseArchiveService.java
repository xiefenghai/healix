package com.healix.core.archive.service;

import com.healix.common.util.JsonUtils;
import com.healix.core.archive.domain.PeopleDiseaseArchive;
import com.healix.core.archive.dto.DiseaseArchiveViewDto;
import com.healix.core.archive.mapper.PeopleDiseaseArchiveMapper;
import com.healix.core.field.PeopleFieldService;
import com.healix.core.revision.annotation.RecordFieldRevision;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DiseaseArchiveService {

    private final PeopleDiseaseArchiveMapper diseaseArchiveMapper;
    private final ArchiveAccessService archiveAccessService;
    private final PeopleFieldService peopleFieldService;

    public List<DiseaseArchiveViewDto> list(String tenantId, String peopleId) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        peopleFieldService.ensureMigrated(tenantId, peopleId);
        List<DiseaseArchiveViewDto> out = new ArrayList<>();
        for (PeopleDiseaseArchive row : diseaseArchiveMapper.listByTenantAndPeople(tenantId, peopleId)) {
            out.add(toView(row));
        }
        return out;
    }

    public DiseaseArchiveViewDto get(String tenantId, String peopleId, String diseaseCode) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        peopleFieldService.ensureMigrated(tenantId, peopleId);
        PeopleDiseaseArchive archive =
                diseaseArchiveMapper.findByTenantPeopleAndCode(tenantId, peopleId, diseaseCode);
        if (archive == null) {
            DiseaseArchiveViewDto empty = new DiseaseArchiveViewDto();
            empty.setPeopleId(peopleId);
            empty.setDiseaseCode(diseaseCode);
            empty.setVersion(0);
            empty.setSchemaVersion(PeopleFieldService.SCHEMA_V2);
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
        PeopleDiseaseArchive archive =
                peopleFieldService.replaceDiseaseArchive(
                        tenantId,
                        peopleId,
                        diseaseCode,
                        expectedVersion,
                        contentJson,
                        operator.toFieldSource());
        return toView(archive);
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
