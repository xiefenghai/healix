package com.healix.core.org.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 机构（协作任务/统计边界） */
@Getter
@Setter
public class Organization extends BaseEntity {
    /** 所属租户ID */
    private String tenantId;
    /** 上级机构ID（预留树形） */
    private String parentOrgId;
    /** 机构名称 */
    private String name;
    /** 机构编码（01 开头 32 位） */
    private String orgCode;
    /** 机构类型，见 {@link com.healix.core.org.enums.OrgTypeEnum} */
    private String orgType;
    /** 状态，见 {@link com.healix.core.identity.enums.EnableStatusEnum} */
    private String status;
}
