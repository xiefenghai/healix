package com.healix.core.allergy.mapper;

import com.healix.core.allergy.domain.PeopleAllergyProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeopleAllergyProfileMapper {

    int insert(PeopleAllergyProfile row);

    int update(PeopleAllergyProfile row);

    PeopleAllergyProfile findByPeople(@Param("tenantId") String tenantId, @Param("peopleId") String peopleId);
}
