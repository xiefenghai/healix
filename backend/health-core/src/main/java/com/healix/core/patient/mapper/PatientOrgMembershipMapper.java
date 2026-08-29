package com.healix.core.patient.mapper;

import com.healix.core.patient.domain.PatientOrgMembership;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PatientOrgMembershipMapper {

    PatientOrgMembership findByOrgAndPeople(@Param("orgId") String orgId, @Param("peopleId") String peopleId);

    List<PatientOrgMembership> listActiveByPeople(@Param("peopleId") String peopleId);

    List<PatientOrgMembership> listActiveByOrg(@Param("orgId") String orgId);

    int countActiveInTenant(@Param("peopleId") String peopleId, @Param("tenantId") String tenantId);

    int insert(PatientOrgMembership membership);

    int markLeft(@Param("id") String id, @Param("leftAt") java.time.LocalDateTime leftAt);
}
