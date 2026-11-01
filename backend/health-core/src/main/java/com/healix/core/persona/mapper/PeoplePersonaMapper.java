package com.healix.core.persona.mapper;

import com.healix.core.persona.domain.PeoplePersona;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeoplePersonaMapper {

    int insert(PeoplePersona row);

    int update(PeoplePersona row);

    PeoplePersona findByPeople(@Param("tenantId") String tenantId, @Param("peopleId") String peopleId);

    /** 乐观抢 GENERATING：仅当当前非 GENERATING 时成功。 */
    int tryMarkGenerating(
            @Param("id") String id,
            @Param("gmtModified") java.time.LocalDateTime gmtModified);
}
