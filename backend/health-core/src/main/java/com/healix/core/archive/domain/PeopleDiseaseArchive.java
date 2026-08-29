package com.healix.core.archive.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PeopleDiseaseArchive extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private String diseaseCode;
    private String schemaVersion;
    private String contentJson;
    private Integer version;
    private String source;
    private String createdByStaffId;
    private String updatedByStaffId;
}
