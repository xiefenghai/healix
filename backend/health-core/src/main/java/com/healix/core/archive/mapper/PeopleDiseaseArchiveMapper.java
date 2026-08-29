package com.healix.core.archive.mapper;

import com.healix.core.archive.domain.PeopleDiseaseArchive;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeopleDiseaseArchiveMapper {

    PeopleDiseaseArchive findByTenantPeopleAndCode(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("diseaseCode") String diseaseCode);

    List<PeopleDiseaseArchive> listByTenantAndPeople(
            @Param("tenantId") String tenantId, @Param("peopleId") String peopleId);

    int insert(PeopleDiseaseArchive archive);

    int updateContent(PeopleDiseaseArchive archive);
}
