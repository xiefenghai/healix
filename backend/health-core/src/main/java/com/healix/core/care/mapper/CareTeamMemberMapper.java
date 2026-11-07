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

    /**
     * 含已软删记录：取该患者在机构内最近一次入组记录（按 joined_at）。
     * 用于服务进度「分组」节点完成时间，以及移出后的 broken 判定。
     */
    CareTeamMember findLatestPeopleInOrgIncludingDeleted(
            @Param("orgId") String orgId, @Param("peopleId") String peopleId);

    List<CareTeamMember> listPatientsByOrg(@Param("orgId") String orgId);

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
