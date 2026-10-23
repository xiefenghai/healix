package com.healix.core.people.mapper;

import com.healix.core.people.domain.PeopleMobileState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeopleMobileStateMapper {

    PeopleMobileState findByPeople(@Param("peopleId") String peopleId);

    int upsert(PeopleMobileState state);
}
