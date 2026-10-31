package com.healix.core.questionnaire.mapper;

import com.healix.core.questionnaire.domain.QuestionnaireResponse;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QuestionnaireResponseMapper {
    QuestionnaireResponse findById(@Param("id") String id);

    QuestionnaireResponse findDraft(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("purpose") String purpose,
            @Param("followupRecordId") String followupRecordId,
            @Param("requestId") String requestId);

    List<QuestionnaireResponse> listByPeople(
            @Param("tenantId") String tenantId, @Param("peopleId") String peopleId);

    QuestionnaireResponse findLatestSubmitted(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("purpose") String purpose);

    List<QuestionnaireResponse> listSubmitted(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("purpose") String purpose,
            @Param("limit") int limit);

    int insert(QuestionnaireResponse row);

    int updateDraft(QuestionnaireResponse row);

    int markSubmitted(QuestionnaireResponse row);
}
