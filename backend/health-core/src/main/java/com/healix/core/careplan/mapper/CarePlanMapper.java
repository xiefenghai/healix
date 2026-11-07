package com.healix.core.careplan.mapper;

import com.healix.core.careplan.domain.CarePlan;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CarePlanMapper {

    int insert(CarePlan row);

    int update(CarePlan row);

    CarePlan findById(@Param("id") String id);

    CarePlan findByTenantAndPeople(@Param("tenantId") String tenantId, @Param("peopleId") String peopleId);

    List<CarePlan> listActiveByTenantAndPeopleIds(
            @Param("tenantId") String tenantId, @Param("peopleIds") List<String> peopleIds);

    /** 批量查方案头（任意状态），供驾驶舱左栏花名册叠方案阶段角标。 */
    List<CarePlan> listByTenantAndPeopleIds(
            @Param("tenantId") String tenantId, @Param("peopleIds") List<String> peopleIds);
}
