package com.healix.core.adherence.mapper;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 依从性读模型：批量读取饮食 / 运动记录的按人按日聚合。 */
@Mapper
public interface LifestyleActivityMapper {

    /** 某人某天的记录聚合；饮食 {@code minutes} 恒为 0。 */
    record PeopleDayRow(String peopleId, LocalDate day, int minutes) {}

    List<PeopleDayRow> listDietDays(
            @Param("tenantId") String tenantId,
            @Param("peopleIds") List<String> peopleIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    List<PeopleDayRow> listExerciseDays(
            @Param("tenantId") String tenantId,
            @Param("peopleIds") List<String> peopleIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
