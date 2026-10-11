package com.healix.core.careplan.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CarePlanDraft extends BaseEntity {
    private String planId;
    private String tenantId;
    private String peopleId;
    /** 草稿乐观锁，非规则版本 */
    private Integer version;
    /** 产品规则版本 */
    private Integer schemaVersion;
    private String source;
    private String exerciseJson;
    private String dietJson;
    private String executionJson;
    private String safetyFlagsJson;
    private String contextSnapshotJson;
    private String baseVersionId;
    private String generationLogId;
    private String updatedByStaffId;
}
