package com.healix.core.assessment.dto;

import com.healix.core.assessment.dto.AssessmentOverviewDto.QuestionnaireRequestDto;
import com.healix.core.field.FieldOption;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 评估问卷：按适用引擎的输入字段动态出题。 */
public final class AssessmentQuestionnaireDto {

    private AssessmentQuestionnaireDto() {}

    public record View(
            List<Section> sections,
            List<FillHint> hints,
            int missingCount,
            int totalCount,
            QuestionnaireRequestDto pendingRequest,
            LocalDateTime lastSubmittedAt) {}

    public record Section(String code, String title, List<Question> questions) {}

    public record Question(
            String field,
            String label,
            String type,
            String text,
            String unit,
            BigDecimal min,
            BigDecimal max,
            List<FieldOption> options,
            String audience,
            boolean required,
            boolean missing,
            Object currentValue,
            String currentText,
            List<String> engines) {}

    /** 不进问卷的缺失项（血压、检验等），引导去对应入口录入。 */
    public record FillHint(String field, String label, String hint, List<String> engines) {}

    /**
     * @param labSampledAtByField 就地录入检验项的采样时间，键为字段编码（如 lab.CR），值为 ISO 本地时间
     */
    public record SubmitCommand(
            Map<String, Object> answers,
            List<String> confirmOverrides,
            String requestId,
            Map<String, String> labSampledAtByField) {

        /** 兼容旧调用（无采样时间）。 */
        public SubmitCommand(Map<String, Object> answers, List<String> confirmOverrides, String requestId) {
            this(answers, confirmOverrides, requestId, null);
        }
    }

    public record Conflict(
            String field, String label, Object currentValue, String currentText, Object newValue, String newText) {}

    /** status：OK / CONFLICT（需二次确认 conflicts 后带 confirmOverrides 重提）。 */
    public record SubmitResult(
            String status, List<Conflict> conflicts, String submissionId, AssessmentOverviewDto overview) {}

    /** channel：WECHAT / SMS（可选，默认走站内/微信通知通道）。 */
    public record SendRequestCommand(List<String> fields, String channel) {}

    /**
     * 问卷活动时间线：健管师发送的请求 + 已提交记录。
     *
     * <p>{@code kind}=REQUEST 为发给患者的邀请；{@code kind}=SUBMISSION 为已填写提交。
     */
    public record ActivityItem(
            String kind,
            String id,
            String status,
            String portal,
            String title,
            String summary,
            int answerCount,
            String requestId,
            LocalDateTime createdAt,
            LocalDateTime expiresAt,
            LocalDateTime submittedAt) {}
}
