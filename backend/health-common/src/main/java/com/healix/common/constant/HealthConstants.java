package com.healix.common.constant;

/**
 * 非枚举型运行时常量（阈值、缓存键等）。
 * 状态 / 类型 / 角色 / 审计动作见各域 {@code com.healix.core.*.enums}。
 */
public final class HealthConstants {

    private HealthConstants() {
    }


    public static final String REDIS_CHAT_MEMORY_PREFIX = "healix:chat:memory:";

    public static final String REDIS_STAFF_AGENT_SESSION_PREFIX = "healix:agent:staff-session:";

    /** 驾驶舱开场简报日缓存：healix:cockpit:briefing:{staffId}:{orgId}:{yyyy-MM-dd} */
    public static final String REDIS_COCKPIT_BRIEFING_PREFIX = "healix:cockpit:briefing:";

    public static final int DEFAULT_CHAT_MEMORY_TURNS = 3;

    public static final int DEFAULT_STAFF_AGENT_SESSION_TURNS = 10;

    public static final double GLUCOSE_EXERCISE_BLOCK_THRESHOLD = 10.0;
}
