package com.healix.core.worktask.mapper;

import com.healix.core.worktask.domain.WorkspaceTask;
import com.healix.core.worktask.dto.WorkspaceTaskQuery;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkspaceTaskMapper {

    int insert(WorkspaceTask row);

    int updatePayload(@Param("id") String id, @Param("summary") String summary, @Param("payloadJson") String payloadJson);

    int claim(
            @Param("id") String id,
            @Param("staffId") String staffId,
            @Param("gmtModified") LocalDateTime gmtModified);

    int updateAssignee(
            @Param("id") String id,
            @Param("assigneeStaffId") String assigneeStaffId,
            @Param("gmtModified") LocalDateTime gmtModified);

    int close(
            @Param("id") String id,
            @Param("status") String status,
            @Param("closeReason") String closeReason,
            @Param("doneAt") LocalDateTime doneAt,
            @Param("doneByStaffId") String doneByStaffId,
            @Param("gmtModified") LocalDateTime gmtModified);

    int expireOrphans(@Param("orgId") String orgId, @Param("now") LocalDateTime now);

    int reassignPublicToStaff(
            @Param("orgId") String orgId,
            @Param("peopleId") String peopleId,
            @Param("staffId") String staffId,
            @Param("gmtModified") LocalDateTime gmtModified);

    int reassignFromStaff(
            @Param("orgId") String orgId,
            @Param("peopleId") String peopleId,
            @Param("fromStaffId") String fromStaffId,
            @Param("toStaffId") String toStaffId,
            @Param("gmtModified") LocalDateTime gmtModified);

    WorkspaceTask findById(@Param("id") String id);

    WorkspaceTask findOpen(
            @Param("tenantId") String tenantId,
            @Param("orgId") String orgId,
            @Param("taskType") String taskType,
            @Param("bizKey") String bizKey);

    WorkspaceTask findByBizKey(
            @Param("tenantId") String tenantId,
            @Param("orgId") String orgId,
            @Param("taskType") String taskType,
            @Param("bizKey") String bizKey);

    WorkspaceTask findLatestClosedNudge(
            @Param("orgId") String orgId, @Param("peopleId") String peopleId);

    List<WorkspaceTask> listOpenByPeople(@Param("orgId") String orgId, @Param("peopleId") String peopleId);

    List<WorkspaceTask> listMetricAlertsByPeople(
            @Param("orgId") String orgId, @Param("peopleId") String peopleId);

    List<WorkspaceTask> listMetricFamily(
            @Param("orgId") String orgId,
            @Param("peopleId") String peopleId,
            @Param("bizKeyPrefix") String bizKeyPrefix);

    List<WorkspaceTask> listMetricFamilyOpenedBetween(
            @Param("orgId") String orgId,
            @Param("peopleId") String peopleId,
            @Param("bizKeyPrefix") String bizKeyPrefix,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    long countByQuery(WorkspaceTaskQuery query);

    List<WorkspaceTask> listByQuery(WorkspaceTaskQuery query);

    long countOpenPublic(
            @Param("orgId") String orgId, @Param("excludeTaskTypes") List<String> excludeTaskTypes);

    long countOpenMine(@Param("orgId") String orgId, @Param("staffId") String staffId);

    long countDoneToday(
            @Param("tenantId") String tenantId,
            @Param("orgId") String orgId,
            @Param("staffId") String staffId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}
