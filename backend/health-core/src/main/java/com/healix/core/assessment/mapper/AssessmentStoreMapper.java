package com.healix.core.assessment.mapper;

import com.healix.core.assessment.domain.AssessmentQuestionnaireRequest;
import com.healix.core.assessment.domain.AssessmentQuestionnaireSubmission;
import com.healix.core.assessment.domain.PeopleAssessmentFact;
import com.healix.core.assessment.domain.PeopleAssessmentLatest;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 评估模块辅助表：最新结果 / 待重算标记 / 问卷事实 / 问卷提交与请求。 */
@Mapper
public interface AssessmentStoreMapper {

    record PeopleRef(String tenantId, String peopleId) {}

    // ---- latest ----
    int upsertLatest(PeopleAssessmentLatest row);

    int touchLatest(
            @Param("peopleId") String peopleId,
            @Param("engineCode") String engineCode,
            @Param("evaluatedAt") LocalDateTime evaluatedAt);

    int deactivateLatest(
            @Param("peopleId") String peopleId,
            @Param("engineCode") String engineCode,
            @Param("evaluatedAt") LocalDateTime evaluatedAt);

    List<PeopleAssessmentLatest> listLatest(@Param("tenantId") String tenantId, @Param("peopleId") String peopleId);

    List<PeopleAssessmentLatest> listLatestByPeopleIds(
            @Param("tenantId") String tenantId, @Param("peopleIds") Collection<String> peopleIds);

    long countLatest();

    // ---- state ----
    int markDirty(@Param("tenantId") String tenantId, @Param("peopleId") String peopleId, @Param("at") LocalDateTime at);

    List<PeopleRef> listDirty(@Param("limit") int limit);

    int markEvaluated(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("startedAt") LocalDateTime startedAt);

    /** 每日全量：有在管机构的患者，或已有评估结果的患者。 */
    List<PeopleRef> listSweepCandidates(@Param("afterPeopleId") String afterPeopleId, @Param("limit") int limit);

    // ---- facts ----
    List<PeopleAssessmentFact> listFacts(@Param("tenantId") String tenantId, @Param("peopleId") String peopleId);

    int upsertFact(PeopleAssessmentFact row);

    // ---- submissions ----
    int insertSubmission(AssessmentQuestionnaireSubmission row);

    AssessmentQuestionnaireSubmission findLatestSubmission(
            @Param("tenantId") String tenantId, @Param("peopleId") String peopleId);

    List<AssessmentQuestionnaireSubmission> listSubmissions(
            @Param("tenantId") String tenantId, @Param("peopleId") String peopleId, @Param("limit") int limit);

    // ---- requests ----
    int insertRequest(AssessmentQuestionnaireRequest row);

    AssessmentQuestionnaireRequest findPendingRequest(@Param("tenantId") String tenantId, @Param("peopleId") String peopleId);

    AssessmentQuestionnaireRequest findLatestRequest(@Param("tenantId") String tenantId, @Param("peopleId") String peopleId);

    List<AssessmentQuestionnaireRequest> listRequests(
            @Param("tenantId") String tenantId, @Param("peopleId") String peopleId, @Param("limit") int limit);

    int markRequestSubmitted(
            @Param("id") String id, @Param("submissionId") String submissionId, @Param("at") LocalDateTime at);

    int cancelPendingRequests(@Param("tenantId") String tenantId, @Param("peopleId") String peopleId);

    List<AssessmentQuestionnaireRequest> listRequestsToRemind(@Param("createdBefore") LocalDateTime createdBefore);

    int markRequestReminded(@Param("id") String id, @Param("at") LocalDateTime at);

    List<AssessmentQuestionnaireRequest> listExpiredRequests(@Param("now") LocalDateTime now);

    int markRequestExpired(@Param("id") String id);

    // ---------------------------------------------------------------- 患者合并

    int repointQuestionnaire(@Param("sourcePeopleId") String sourcePeopleId, @Param("targetPeopleId") String targetPeopleId);

    int repointRequests(@Param("sourcePeopleId") String sourcePeopleId, @Param("targetPeopleId") String targetPeopleId);

    /** 目标已有同字段的事实时保留目标值 */
    int repointFacts(@Param("sourcePeopleId") String sourcePeopleId, @Param("targetPeopleId") String targetPeopleId);

    int deleteFacts(@Param("peopleId") String peopleId);

    int deleteLatest(@Param("peopleId") String peopleId);

    int deleteState(@Param("peopleId") String peopleId);
}
