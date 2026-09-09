package com.healix.core.notify.enums;

/** 幂等命中时的处理策略。 */
public enum NotifyDuplicatePolicy {
    /** 不改正文与已读（报告） */
    IGNORE,
    /** 刷新正文，保留已读（digest） */
    UPSERT_BODY_KEEP_READ
}
