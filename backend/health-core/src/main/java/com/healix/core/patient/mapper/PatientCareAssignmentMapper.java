package com.healix.core.patient.mapper;

import com.healix.core.patient.domain.PatientCareAssignment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PatientCareAssignmentMapper {

    PatientCareAssignment find(@Param("tenantId") String tenantId, @Param("peopleId") String peopleId);

    int insert(PatientCareAssignment assignment);

    int update(PatientCareAssignment assignment);
}
