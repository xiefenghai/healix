package com.healix.core.careplan.enums;

/** 方案头状态：草稿 → 已审核 → 沟通中 → 已生效。 */
public enum CarePlanStatusEnum {
    DRAFT,
    /** 健管师/营养师审核通过，待客户沟通 */
    REVIEWED,
    /** 已发起客户沟通，等待结果 */
    COMMUNICATING,
    ACTIVE,
    SUPERSEDED,
    ARCHIVED
}
