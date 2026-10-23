package com.healix.core.science.mapper;

import com.healix.core.science.domain.ScienceDaily;
import com.healix.core.science.domain.ScienceWatch;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScienceMapper {

    ScienceDaily findDaily(@Param("peopleId") String peopleId, @Param("bizDate") LocalDate bizDate);

    int insertDaily(ScienceDaily row);

    int replaceClip(
            @Param("id") String id,
            @Param("clipCode") String clipCode,
            @Param("watched") int watched,
            @Param("gmtModified") LocalDateTime gmtModified);

    int markWatched(
            @Param("id") String id, @Param("watched") int watched, @Param("gmtModified") LocalDateTime gmtModified);

    List<ScienceWatch> listWatched(@Param("peopleId") String peopleId);
}
