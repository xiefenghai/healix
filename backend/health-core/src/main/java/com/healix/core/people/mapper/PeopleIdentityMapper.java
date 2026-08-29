package com.healix.core.people.mapper;

import com.healix.core.people.domain.PeopleIdentity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeopleIdentityMapper {

    PeopleIdentity findByTenantTypeHash(
            @Param("tenantId") String tenantId,
            @Param("identityType") String identityType,
            @Param("identityValueHash") String identityValueHash);

    PeopleIdentity findByPeopleAndType(@Param("peopleId") String peopleId, @Param("identityType") String identityType);

    List<PeopleIdentity> listByPeople(@Param("peopleId") String peopleId);

    PeopleIdentity findPrimaryMask(@Param("peopleId") String peopleId);

    int insert(PeopleIdentity identity);
}
