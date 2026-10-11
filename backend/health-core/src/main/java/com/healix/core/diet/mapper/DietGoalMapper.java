package com.healix.core.diet.mapper;

import com.healix.core.diet.domain.DietGoal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DietGoalMapper {

    int insert(DietGoal row);

    int softDelete(@Param("id") String id);

    DietGoal findCurrent(@Param("tenantId") String tenantId, @Param("peopleId") String peopleId);
}
