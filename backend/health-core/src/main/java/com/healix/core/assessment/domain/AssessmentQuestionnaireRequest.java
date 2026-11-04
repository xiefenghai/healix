package com.healix.core.assessment.domain;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 健管师发给患者的问卷（7 天有效，48 小时未填提醒一次）。 */
@Getter
@Setter
public class AssessmentQuestionnaireRequest {
    public static final String PENDING = "PENDING";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String EXPIRED = "EXPIRED";
    public static final String CANCELLED = "CANCELLED";

    private String id;
    private String tenantId;
    private String peopleId;
    private String orgId;
    private String staffId;
    private String fieldsJson;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime remindedAt;
    private String submissionId;
    private LocalDateTime submittedAt;
    private LocalDateTime gmtCreated;
}
