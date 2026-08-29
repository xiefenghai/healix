package com.healix.core.people.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 患者基础档案（租户级） */
@Getter
@Setter
public class PeopleBasicArchive extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private String schemaVersion;
    private String contentJson;
    private Integer version;
    private String source;
    private String createdByStaffId;
    private String updatedByStaffId;
    private String remark;
}
