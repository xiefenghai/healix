package com.healix.core.revision.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RevisionBatchViewDto {
    private String batchId;
    private String operatorType;
    private String operatorId;
    /** 操作者显示名（员工 displayName / 患者 displayName） */
    private String operatorName;
    /** 操作者岗位角色（STAFF 时如 CARE_MANAGER / DOCTOR） */
    private String operatorRoleCode;
    private String bizType;
    private String bizKey;
    private Integer versionBefore;
    private Integer versionAfter;
    private LocalDateTime gmtCreated;
    private List<RevisionItemViewDto> items;
}
