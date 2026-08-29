package com.healix.agent.planner;

import com.healix.common.constant.IntentType;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Lightweight intent router (keyword heuristic). Replaced by LLM routing later.
 */
@Component
public class IntentRouter {

    public IntentType route(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return IntentType.UNKNOWN;
        }
        String msg = userMessage.toLowerCase(Locale.ROOT);
        if (containsAny(msg, "步数", "血糖", "心率", "体重", "睡眠", "vitals", "glucose", "steps")) {
            return IntentType.QUERY_VITALS;
        }
        if (containsAny(msg, "提醒", "闹钟", "remind")) {
            return IntentType.SET_REMINDER;
        }
        if (containsAny(msg, "饮食", "食谱", "吃什么", "diet", "meal")) {
            return IntentType.DIET_PLAN;
        }
        if (containsAny(msg, "运动", "锻炼", "健身", "exercise", "workout")) {
            return IntentType.EXERCISE_PLAN;
        }
        if (containsAny(msg, "周报", "weekly", "总结")) {
            return IntentType.WEEKLY_REPORT;
        }
        if (containsAny(msg, "为什么", "什么是", "科普", "知识")) {
            return IntentType.KNOWLEDGE_QA;
        }
        return IntentType.CHITCHAT;
    }

    private static boolean containsAny(String msg, String... keywords) {
        for (String kw : keywords) {
            if (msg.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
