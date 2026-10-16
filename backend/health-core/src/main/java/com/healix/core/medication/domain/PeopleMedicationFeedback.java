package com.healix.core.medication.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 患者用药反馈：不适 / 申请调整 / 自述停药 / 过敏冲突。 */
@Getter
@Setter
public class PeopleMedicationFeedback extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private String medicationId;
    private String kind;
    private String symptomsJson;
    private String severity;
    private String content;
    private String status;
    private String workTaskId;
    private String handledByStaffId;
    private LocalDateTime handledAt;
    private String reply;
}
