package com.healix.core.medication.mapper;

import com.healix.core.medication.domain.PeopleMedicationFeedback;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeopleMedicationFeedbackMapper {

    int insert(PeopleMedicationFeedback row);

    int updateTask(@Param("id") String id, @Param("workTaskId") String workTaskId);

    int handle(PeopleMedicationFeedback row);

    PeopleMedicationFeedback findById(@Param("id") String id);

    List<PeopleMedicationFeedback> listByMedication(@Param("medicationId") String medicationId);

    List<PeopleMedicationFeedback> listByPeople(
            @Param("tenantId") String tenantId, @Param("peopleId") String peopleId, @Param("status") String status);
}
