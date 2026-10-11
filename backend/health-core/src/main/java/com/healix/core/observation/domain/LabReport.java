package com.healix.core.observation.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LabReport extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private String orgId;
    private String specimenType;
    private LocalDateTime sampledAt;
    private LocalDateTime reportedAt;
    private String source;
    private String note;
    private String createdByStaffId;
    private String updatedByStaffId;
}
