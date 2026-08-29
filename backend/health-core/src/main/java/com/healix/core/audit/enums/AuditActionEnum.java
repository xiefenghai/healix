package com.healix.core.audit.enums;

/** 审计动作 */
public enum AuditActionEnum {
    /** Ops 查看患者敏感信息 */
    OPS_PHI_VIEW,
    /** 创建租户 */
    TENANT_CREATE,
    /** 创建员工账号 */
    STAFF_ACCOUNT_CREATE,
    /** 更新员工账号 */
    STAFF_ACCOUNT_UPDATE,
    /** 授予/变更员工角色 */
    STAFF_ROLE_GRANT,
    /** 查看租户账号页 */
    TENANT_STAFF_TAB_VIEW,
    /** 创建机构 */
    ORG_CREATE,
    /** 更新机构 */
    ORG_UPDATE,
    /** 替换员工机构绑定 */
    STAFF_ORG_BIND_REPLACE,
    /** 患者入组 */
    MEMBERSHIP_JOIN,
    /** 创建健管组 */
    CARE_TEAM_CREATE,
    /** 更新健管组 */
    CARE_TEAM_UPDATE,
    /** 删除健管组 */
    CARE_TEAM_DELETE,
    /** 变更健管组主责 */
    CARE_TEAM_PRIMARY_CHANGE,
    /** 健管组添加成员 */
    CARE_TEAM_MEMBER_ADD,
    /** 健管组移除成员 */
    CARE_TEAM_MEMBER_REMOVE,
    /** 机构新开医护 */
    ORG_STAFF_CREATE,
    /** 机构解绑医护 */
    ORG_STAFF_UNBIND,
    /** B 端患者建档 */
    PATIENT_ARCHIVE_CREATE;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
