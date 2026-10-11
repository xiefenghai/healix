package com.healix.core.report.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 管理报告（周/月/季）。 */
@Getter
@Setter
public class HealthReport extends BaseEntity {
    private String tenantId;
    /** 主管机构 */
    private String orgId;
    private String peopleId;
    private String careTeamId;
    private String periodType;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String title;
    private String status;
    private Integer schemaVersion;
    private String contentJson;
    private String staffComment;
    private String workspaceTaskId;
    private LocalDateTime publishedAt;
    private String publishedByStaffId;
    /** JOB / MANUAL */
    private String generatedBy;
}
