package com.healix.core.program.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PeopleProgramEnrollment extends BaseEntity {
    private String tenantId;
    private String orgId;
    private String peopleId;
    private String programCode;
    private String status;
    private LocalDateTime enrolledAt;
    private LocalDateTime onboardedAt;
    private LocalDateTime closedAt;
    private String ownerStaffId;
    private String intakeResponseId;
    private String closeReason;
}
