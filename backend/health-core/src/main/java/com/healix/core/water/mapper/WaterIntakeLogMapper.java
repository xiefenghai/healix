package com.healix.core.water.mapper;

import com.healix.core.water.domain.WaterIntakeLog;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WaterIntakeLogMapper {

    int insert(WaterIntakeLog row);

    WaterIntakeLog findById(@Param("id") String id);

    int softDelete(@Param("id") String id);

    int updateEntry(
            @Param("id") String id,
            @Param("amountMl") int amountMl,
            @Param("source") String source,
            @Param("recordedAt") java.time.LocalDateTime recordedAt);

    List<WaterIntakeLog> listByPeopleDate(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("bizDate") LocalDate bizDate);

    Integer sumByPeopleDate(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("bizDate") LocalDate bizDate);

    List<WaterIntakeLog> listByPeopleRange(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
