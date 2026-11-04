package com.healix.core.consent.mapper;

import com.healix.core.consent.domain.PeopleConsent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeopleConsentMapper {
    PeopleConsent findByPeopleAndCode(
            @Param("peopleId") String peopleId, @Param("consentCode") String consentCode);

    int insert(PeopleConsent row);

    int update(PeopleConsent row);
}
