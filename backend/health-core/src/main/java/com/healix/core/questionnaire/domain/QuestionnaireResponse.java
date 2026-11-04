package com.healix.core.questionnaire.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QuestionnaireResponse extends BaseEntity {
    private String tenantId;
    private String orgId;
    private String peopleId;
    private String questionnaireCode;
    private Integer questionnaireVersion;
    private String purpose;
    private String enrollmentId;
    private String followupRecordId;
    private String requestId;
    private String status;
    private String channel;
    private String programCode;
    private String prefillJson;
    private String answersJson;
    private String snapshotJson;
    private String changesJson;
    private String highlightsJson;
    private String completenessJson;
    private String filledByType;
    private String filledById;
    private LocalDateTime submittedAt;
}
