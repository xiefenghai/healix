package com.healix.core.observation.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExamReport extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private String orgId;
    private String examType;
    private LocalDateTime examinedAt;
    private String conclusion;
    private String findingsJson;
    /** MANUAL / PATIENT / STAFF … 见 {@link com.healix.core.observation.enums.HealthDataSourceEnum} */
    private String source;
    private String createdByStaffId;
    private String updatedByStaffId;
}
