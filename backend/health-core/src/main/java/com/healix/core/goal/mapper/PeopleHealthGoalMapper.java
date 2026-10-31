package com.healix.core.goal.mapper;

import com.healix.core.goal.domain.PeopleHealthGoal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeopleHealthGoalMapper {
    PeopleHealthGoal findByEnrollment(@Param("enrollmentId") String enrollmentId);

    PeopleHealthGoal findLatestByPeople(
            @Param("tenantId") String tenantId, @Param("peopleId") String peopleId);

    int insert(PeopleHealthGoal row);

    int update(PeopleHealthGoal row);
}
