package com.healix.core.careplan.mapper;

import com.healix.core.careplan.domain.CarePlanTask;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CarePlanTaskMapper {

    int insert(CarePlanTask row);

    List<CarePlanTask> listByVersionId(@Param("planVersionId") String planVersionId);

    List<CarePlanTask> listActiveByPeople(
            @Param("tenantId") String tenantId, @Param("peopleId") String peopleId);

    List<CarePlanTask> listActiveByTenantAndPeopleIds(
            @Param("tenantId") String tenantId, @Param("peopleIds") List<String> peopleIds);
}
