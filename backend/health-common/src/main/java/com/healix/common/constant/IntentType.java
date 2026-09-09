package com.healix.common.constant;

public enum IntentType {
    QUERY_VITALS,
    SET_REMINDER,
    KNOWLEDGE_QA,
    DIET_PLAN,
    EXERCISE_PLAN,
    WEEKLY_REPORT,
    /** 今日方案打卡进度与未打任务 */
    QUERY_PLAN,
    /** 在用药清单与今日服药进度 */
    QUERY_MEDICATION,
    /** 待办随访与申请回访 */
    QUERY_FOLLOWUP,
    /** 管理报告 */
    QUERY_REPORT,
    CHITCHAT,
    UNKNOWN
}
