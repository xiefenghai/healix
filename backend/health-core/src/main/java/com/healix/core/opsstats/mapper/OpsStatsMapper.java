package com.healix.core.opsstats.mapper;

import com.healix.core.opsstats.dto.OpsStatsDayAggRow;
import com.healix.core.opsstats.dto.OpsStatsStaffAggRow;
import com.healix.core.opsstats.dto.OpsStatsTaskAggRow;
import com.healix.core.opsstats.dto.OpsStatsTypeSliceDto;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OpsStatsMapper {

    OpsStatsTaskAggRow aggregateTasks(
            @Param("orgId") String orgId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("now") LocalDateTime now,
            @Param("careTeamId") String careTeamId,
            @Param("staffId") String staffId);

    List<OpsStatsDayAggRow> seriesByDay(
            @Param("orgId") String orgId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("careTeamId") String careTeamId,
            @Param("staffId") String staffId);

    List<OpsStatsTypeSliceDto> doneByType(
            @Param("orgId") String orgId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("careTeamId") String careTeamId,
            @Param("staffId") String staffId);

    List<OpsStatsStaffAggRow> doneByStaff(
            @Param("orgId") String orgId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("careTeamId") String careTeamId,
            @Param("limit") int limit);

    List<OpsStatsStaffAggRow> overdueByStaff(
            @Param("orgId") String orgId,
            @Param("now") LocalDateTime now,
            @Param("careTeamId") String careTeamId);

    long overdueUnassigned(
            @Param("orgId") String orgId,
            @Param("now") LocalDateTime now,
            @Param("careTeamId") String careTeamId);

    List<OpsStatsStaffAggRow> followupDoneByStaff(
            @Param("orgId") String orgId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("careTeamId") String careTeamId);

    long countFollowupDone(
            @Param("orgId") String orgId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("careTeamId") String careTeamId,
            @Param("staffId") String staffId);

    long countPatientRequestFollowup(
            @Param("orgId") String orgId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("careTeamId") String careTeamId,
            @Param("staffId") String staffId);

    long countReportPublished(
            @Param("orgId") String orgId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("careTeamId") String careTeamId,
            @Param("staffId") String staffId);

    List<String> listStaffIdsInCareTeam(@Param("careTeamId") String careTeamId);

    List<String> listCareTeamIdsForStaff(@Param("orgId") String orgId, @Param("staffId") String staffId);
}
