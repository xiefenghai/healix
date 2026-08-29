package com.healix.core.care.mapper;

import com.healix.core.care.domain.CareTeamMember;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CareTeamMemberMapper {

    CareTeamMember findById(@Param("id") String id);

    List<CareTeamMember> listByTeam(@Param("teamId") String teamId);

    CareTeamMember findStaff(@Param("teamId") String teamId, @Param("staffId") String staffId);

    CareTeamMember findPeopleInOrg(@Param("orgId") String orgId, @Param("peopleId") String peopleId);

    int countActiveByTeam(@Param("teamId") String teamId);

    int insert(CareTeamMember member);

    int softDelete(
            @Param("id") String id,
            @Param("gmtDeleted") LocalDateTime gmtDeleted,
            @Param("gmtModified") LocalDateTime gmtModified);

    int softDeleteByTeam(
            @Param("teamId") String teamId,
            @Param("gmtDeleted") LocalDateTime gmtDeleted,
            @Param("gmtModified") LocalDateTime gmtModified);

    int countPrimaryTeamsForStaff(
            @Param("orgId") String orgId, @Param("staffId") String staffId);
}
