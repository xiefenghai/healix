package com.healix.core.metadata.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 患者字段值（字段值唯一来源）：一行一个元数据编码，记录当前值与来源。 */
@Getter
@Setter
public class PeopleMetadataInfo extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private String metadataCode;
    /** {@code {"value": ...}} */
    private String metadataValue;
    /** 来源业务，见 FieldSourceBiz */
    private String sourceBizCode;
    private String sourceClientCode;
    /** 来源单据，如作答 id */
    private String sourceRefId;
    private String updatedByType;
    private String updatedById;
}
