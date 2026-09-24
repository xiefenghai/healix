package com.healix.core.followup.mapper;

import com.healix.core.followup.domain.FollowupRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FollowupRecordMapper {

    int insert(FollowupRecord row);

    int updateOnComplete(FollowupRecord row);

    /** OPEN 草稿：只改内容，不改 status / completed_* */
    int updateDraft(FollowupRecord row);

    int updateCancel(
            @Param("id") String id,
            @Param("status") String status,
            @Param("cancelReason") String cancelReason,
            @Param("gmtModified") LocalDateTime gmtModified);

    int updateWorkspaceTaskId(
            @Param("id") String id,
            @Param("workspaceTaskId") String workspaceTaskId,
            @Param("gmtModified") LocalDateTime gmtModified);

    FollowupRecord findById(@Param("id") String id);

    List<FollowupRecord> listByTaskId(@Param("workspaceTaskId") String workspaceTaskId);

    FollowupRecord findOpenByTaskId(@Param("workspaceTaskId") String workspaceTaskId);

    /** 患者当前 OPEN 的定期随访（一键催办复用）。 */
    FollowupRecord findOpenPeriodicByPeople(@Param("orgId") String orgId, @Param("peopleId") String peopleId);

    /** 患者当前 OPEN 的主动申请回访单（避免重复刷单）。 */
    FollowupRecord findOpenPatientRequestByPeople(
            @Param("orgId") String orgId, @Param("peopleId") String peopleId);

    FollowupRecord findLatestByTaskAndType(
            @Param("workspaceTaskId") String workspaceTaskId, @Param("recordType") String recordType);

    long countByOrg(
            @Param("orgId") String orgId,
            @Param("status") String status,
            @Param("recordType") String recordType,
            @Param("keyword") String keyword);

    List<FollowupRecord> listByOrg(
            @Param("orgId") String orgId,
            @Param("status") String status,
            @Param("recordType") String recordType,
            @Param("keyword") String keyword,
            @Param("offset") int offset,
            @Param("limit") int limit);

    List<FollowupRecord> listByPeople(
            @Param("orgId") String orgId,
            @Param("peopleId") String peopleId,
            @Param("recordType") String recordType,
            @Param("limit") int limit);

    /** C 端：租户内患者可见随访（不含已取消）。 */
    List<FollowupRecord> listVisibleForPatient(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("limit") int limit);

    List<FollowupRecord> listCompletedByPeopleRange(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}
