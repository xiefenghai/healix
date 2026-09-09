package com.healix.core.report.mapper;

import com.healix.core.report.domain.HealthReport;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface HealthReportMapper {

    int insert(HealthReport row);

    int updateContent(HealthReport row);

    int updateStatus(HealthReport row);

    int updateWorkspaceTaskId(
            @Param("id") String id,
            @Param("workspaceTaskId") String workspaceTaskId,
            @Param("gmtModified") LocalDateTime gmtModified);

    int softDelete(
            @Param("id") String id,
            @Param("gmtDeleted") LocalDateTime gmtDeleted,
            @Param("gmtModified") LocalDateTime gmtModified);

    HealthReport findById(@Param("id") String id);

    HealthReport findByPeriod(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("periodType") String periodType,
            @Param("periodStart") LocalDate periodStart);

    List<HealthReport> listByPeople(
            @Param("peopleId") String peopleId,
            @Param("status") String status,
            @Param("limit") int limit);

    List<String> listPeopleIdsByPrimaryOrg(
            @Param("tenantId") String tenantId, @Param("orgId") String orgId, @Param("limit") int limit);
}
