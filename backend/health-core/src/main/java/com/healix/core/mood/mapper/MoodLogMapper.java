package com.healix.core.mood.mapper;

import com.healix.core.mood.domain.MoodLog;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MoodLogMapper {
    int insert(MoodLog row);

    int update(MoodLog row);

    int softDelete(@Param("id") String id);

    MoodLog findByPeopleDate(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("bizDate") LocalDate bizDate);

    List<MoodLog> listByPeopleRange(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
