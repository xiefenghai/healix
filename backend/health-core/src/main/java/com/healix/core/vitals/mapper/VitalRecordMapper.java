package com.healix.core.vitals.mapper;

import com.healix.core.vitals.domain.VitalRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface VitalRecordMapper {

    int insert(VitalRecord record);

    int update(VitalRecord record);

    int softDelete(@Param("id") String id);

    int softDeleteByGroupId(@Param("groupId") String groupId);

    VitalRecord findById(@Param("id") String id);

    List<VitalRecord> listByPeople(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("metricType") String metricType,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("limit") Integer limit);

    List<VitalRecord> listByRange(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("metricType") String metricType,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    List<VitalRecord> listByGroupId(@Param("groupId") String groupId);

    /** 按 recorded_at 倒序取最近一批，供分槽 latest 聚合。 */
    List<VitalRecord> listRecentForLatest(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("limit") int limit);

    Double averageValue(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("metricType") String metricType,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}
