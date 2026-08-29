package com.healix.core.vitals.mapper;

import com.healix.core.vitals.domain.VitalRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface VitalRecordMapper {

    int insert(VitalRecord record);

    List<VitalRecord> listByRange(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("metricType") String metricType,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    Double averageValue(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("metricType") String metricType,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}
