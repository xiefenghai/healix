package com.healix.core.assessment.mapper;

import com.healix.core.assessment.domain.PeopleAssessmentSnapshot;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeopleAssessmentSnapshotMapper {

    int insert(PeopleAssessmentSnapshot row);

    PeopleAssessmentSnapshot findById(@Param("id") String id);

    List<PeopleAssessmentSnapshot> listLatestByPeople(
            @Param("tenantId") String tenantId, @Param("peopleId") String peopleId);

    List<PeopleAssessmentSnapshot> listHistory(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("engineCode") String engineCode,
            @Param("offset") int offset,
            @Param("limit") int limit);

    long countHistory(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("engineCode") String engineCode);
}
