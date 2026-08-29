package com.healix.core.people.mapper;

import com.healix.core.people.domain.PeopleBasicArchive;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeopleBasicArchiveMapper {

    PeopleBasicArchive findByTenantAndPeople(@Param("tenantId") String tenantId, @Param("peopleId") String peopleId);

    int insert(PeopleBasicArchive archive);

    int updateContent(PeopleBasicArchive archive);
}
