package com.healix.core.followup.catalog;

public enum FollowupRecordSource {
    MANUAL,
    WORKSPACE_TASK,
    ESCALATE,
    /** 定期随访排期 Job 自动生成 */
    SCHEDULE_JOB,
    /** C 端患者主动申请回访 */
    PATIENT_REQUEST,
    /** 患者入组机构时自动开入组/首诊随访 */
    MEMBERSHIP_JOIN;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
