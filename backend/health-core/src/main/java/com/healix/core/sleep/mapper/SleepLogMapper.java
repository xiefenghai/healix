package com.healix.core.sleep.mapper;

import com.healix.core.sleep.domain.SleepLog;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SleepLogMapper {
    int insert(SleepLog row);

    int update(SleepLog row);

    int softDelete(@Param("id") String id);

    SleepLog findById(@Param("id") String id);

    List<SleepLog> listByPeopleDate(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("bizDate") LocalDate bizDate);

    SleepLog findMainByPeopleDate(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("bizDate") LocalDate bizDate);

    List<SleepLog> listByPeopleRange(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
