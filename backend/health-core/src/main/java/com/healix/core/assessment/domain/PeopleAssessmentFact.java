package com.healix.core.assessment.domain;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 问卷补充的档案外事实（每人每字段只存最新值）。 */
@Getter
@Setter
public class PeopleAssessmentFact {
    private String id;
    private String tenantId;
    private String peopleId;
    private String fieldCode;
    private String valueJson;
    private String source;
    private String submissionId;
    private LocalDateTime recordedAt;
    private String recordedBy;
}
