package com.healix.core.workspace.mapper;

import com.healix.core.workspace.domain.StaffPatientWatch;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StaffPatientWatchMapper {

    StaffPatientWatch findActive(
            @Param("staffId") String staffId,
            @Param("orgId") String orgId,
            @Param("peopleId") String peopleId);

    List<String> listPeopleIdsByStaffOrg(@Param("staffId") String staffId, @Param("orgId") String orgId);

    int countByStaffOrg(@Param("staffId") String staffId, @Param("orgId") String orgId);

    int insert(StaffPatientWatch row);

    int softDelete(
            @Param("staffId") String staffId,
            @Param("orgId") String orgId,
            @Param("peopleId") String peopleId,
            @Param("gmtDeleted") LocalDateTime gmtDeleted,
            @Param("gmtModified") LocalDateTime gmtModified);
}
