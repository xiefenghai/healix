package com.healix.core.careplan.mapper;

import com.healix.core.careplan.domain.CarePlanDraft;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CarePlanDraftMapper {

    int insert(CarePlanDraft row);

    int update(CarePlanDraft row);

    int softDelete(@Param("id") String id);

    CarePlanDraft findById(@Param("id") String id);

    CarePlanDraft findByPlanId(@Param("planId") String planId);
}
