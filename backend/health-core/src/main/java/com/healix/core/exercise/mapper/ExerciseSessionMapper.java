package com.healix.core.exercise.mapper;

import com.healix.core.exercise.domain.ExerciseSession;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ExerciseSessionMapper {

    int insert(ExerciseSession row);

    int update(ExerciseSession row);

    int updateAlertTask(@Param("id") String id, @Param("alertTaskId") String alertTaskId);

    int softDelete(@Param("id") String id);

    ExerciseSession findById(@Param("id") String id);

    List<ExerciseSession> listByPeopleRange(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** 最近使用的类型编码（按次数降序）。 */
    List<String> listFrequentTypeCodes(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("since") LocalDate since,
            @Param("limit") int limit);
}
