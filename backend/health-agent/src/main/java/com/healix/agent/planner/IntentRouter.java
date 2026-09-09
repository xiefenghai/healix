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
        // 用药先于方案判断：「今天的药打卡了吗」同时命中两组关键词，用药更具体。
        if (containsAny(msg, "吃药", "服药", "用药", "药物", "漏服", "停药", "medication", "pill")) {
            return IntentType.QUERY_MEDICATION;
        }
        if (containsAny(msg, "方案", "打卡", "今日任务", "今天要做", "care plan", "checkin")) {
            return IntentType.QUERY_PLAN;
        }
        if (containsAny(msg, "随访", "回访", "复诊", "预约", "followup", "follow-up")) {
            return IntentType.QUERY_FOLLOWUP;
        }
        if (containsAny(msg, "报告", "月报", "季度", "评估结果", "report")) {
            return IntentType.QUERY_REPORT;
        }
        if (containsAny(msg, "步数", "血糖", "血压", "心率", "体重", "腰围", "睡眠", "指标", "vitals", "glucose", "steps")) {
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
