package com.healix.core.adherence.mapper;

import com.healix.core.adherence.domain.AdherenceDailySnapshot;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AdherenceDailySnapshotMapper {

    /** 重跑同一天时覆盖旧值。 */
    int upsert(AdherenceDailySnapshot row);

    List<AdherenceDailySnapshot> listRange(
            @Param("orgId") String orgId,
            @Param("careTeamId") String careTeamId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
