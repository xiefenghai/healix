package com.healix.core.mood.support;

import org.springframework.util.StringUtils;

/** 心情记录与方案任务约定。 */
public final class MoodLogRules {

    public static final String TASK_CODE = "MOOD_LOG";
    public static final String CATEGORY = "MOOD";
    public static final int MIN_SCORE = 1;
    public static final int MAX_SCORE = 5;

    private MoodLogRules() {}

    public static boolean looksLikeMoodTask(String category, String title, String taskCode) {
        if (TASK_CODE.equalsIgnoreCase(taskCode) || CATEGORY.equalsIgnoreCase(category)) {
            return true;
        }
        if (!StringUtils.hasText(title)) {
            return false;
        }
        String t = title.trim();
        return t.contains("心情") || t.contains("情绪") || t.contains("心态");
    }

    public static String labelOf(int score) {
        return switch (score) {
            case 1 -> "很低落";
            case 2 -> "偏低";
            case 3 -> "平稳";
            case 4 -> "不错";
            case 5 -> "很好";
            default -> "平稳";
        };
    }
}
