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
    PATIENT_ARCHIVE_CREATE,
    /** C 端激活码绑定就诊人卡片 */
    PATIENT_CARD_ACTIVATE,
    WORKSPACE_TASK_CLAIM,
    WORKSPACE_TASK_ASSIGN,
    WORKSPACE_TASK_RELEASE,
    WORKSPACE_TASK_DONE,
    /** @deprecated 已迁至 FOLLOWUP_RECORD_COMPLETE */
    FOLLOW_UP_FORM_SUBMIT,
    FOLLOWUP_RECORD_CREATE,
    FOLLOWUP_RECORD_COMPLETE,
    FOLLOWUP_RECORD_CANCEL,
    /** 依从性看板一键开随访 / 打卡跟进 */
    ADHERENCE_BOARD_ESCALATE,
    /** B 一键提醒患者（站内信 STAFF_NUDGE） */
    STAFF_NUDGE_SEND,
    /** Ops 调整租户配额上限 */
    TENANT_QUOTA_UPDATE,
    /** Ops 切换租户功能开关 */
    TENANT_FEATURE_FLAG_UPDATE,
    /** Ops 保存租户白标配置 */
    TENANT_CONFIG_UPDATE,
    /** B/Ops 账号启用 MFA */
    MFA_ENABLE,
    /** B/Ops 账号关闭 MFA */
    MFA_DISABLE,
    /** 患者档案合并 */
    PATIENT_MERGE;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
