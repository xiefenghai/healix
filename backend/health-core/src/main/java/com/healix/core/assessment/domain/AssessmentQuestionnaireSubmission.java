package com.healix.core.assessment.domain;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssessmentQuestionnaireSubmission {
    private String id;
    private String tenantId;
    private String peopleId;
    private String portal;
    private String submittedBy;
    private String requestId;
    private String answersJson;
    private String overridesJson;
    private LocalDateTime submittedAt;
}
