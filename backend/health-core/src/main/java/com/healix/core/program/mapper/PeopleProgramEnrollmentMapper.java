package com.healix.core.program.mapper;

import com.healix.core.program.domain.PeopleProgramEnrollment;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeopleProgramEnrollmentMapper {
    PeopleProgramEnrollment findById(@Param("id") String id);

    PeopleProgramEnrollment findOpen(
            @Param("tenantId") String tenantId,
            @Param("orgId") String orgId,
            @Param("peopleId") String peopleId,
            @Param("programCode") String programCode);

    List<PeopleProgramEnrollment> listByPeople(
            @Param("tenantId") String tenantId,
            @Param("orgId") String orgId,
            @Param("peopleId") String peopleId);

    int insert(PeopleProgramEnrollment row);

    int updateStatus(PeopleProgramEnrollment row);
}
