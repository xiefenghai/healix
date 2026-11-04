package com.healix.core.sleep.support;

import com.healix.common.exception.BusinessException;
import org.springframework.util.StringUtils;

/** 睡眠记录与方案任务约定。 */
public final class SleepLogRules {

    public static final String TASK_CODE = "SLEEP_LOG";
    public static final String CATEGORY = "SLEEP";
    public static final String SEGMENT_MAIN = "MAIN";
    public static final String SEGMENT_NAP = "NAP";
    /** 主睡/午睡统一最短 15 分钟（午睡常不足 1 小时） */
    public static final int MIN_DURATION_MIN = 15;
    public static final int MAX_DURATION_MIN = 16 * 60;
    public static final int MIN_NAP_DURATION_MIN = 15;
    public static final int MAX_NAP_DURATION_MIN = 4 * 60;
    public static final int DEFAULT_TARGET_MIN = 7 * 60;

    private SleepLogRules() {}

    public static boolean looksLikeSleepTask(String category, String title, String taskCode) {
        if (TASK_CODE.equalsIgnoreCase(taskCode) || CATEGORY.equalsIgnoreCase(category)) {
            return true;
        }
        if (!StringUtils.hasText(title)) {
            return false;
        }
        String t = title.trim();
        return t.contains("睡眠") || t.contains("作息") || t.contains("早睡");
    }

    public static boolean validQuality(String quality) {
        if (!StringUtils.hasText(quality)) {
            return false;
        }
        return switch (quality.trim().toUpperCase()) {
            case "GOOD", "FAIR", "POOR", "1", "2", "3", "4", "5" -> true;
            default -> false;
        };
    }

    public static String normalizeQuality(String quality) {
        if (!StringUtils.hasText(quality)) {
            return "FAIR";
        }
        String q = quality.trim().toUpperCase();
        return switch (q) {
            case "1", "2" -> "POOR";
            case "3" -> "FAIR";
            case "4", "5" -> "GOOD";
            case "GOOD", "FAIR", "POOR" -> q;
            default -> "FAIR";
        };
    }

    public static String normalizeSegment(String segment) {
        if (!StringUtils.hasText(segment)) {
            return SEGMENT_MAIN;
        }
        String s = segment.trim().toUpperCase();
        return SEGMENT_NAP.equals(s) ? SEGMENT_NAP : SEGMENT_MAIN;
    }

    public static void assertDuration(String segment, Integer durationMin) {
        boolean nap = SEGMENT_NAP.equals(normalizeSegment(segment));
        int min = nap ? MIN_NAP_DURATION_MIN : MIN_DURATION_MIN;
        int max = nap ? MAX_NAP_DURATION_MIN : MAX_DURATION_MIN;
        if (durationMin == null || durationMin < min || durationMin > max) {
            throw new BusinessException(
                    400, nap ? "午睡时长需在 15 分钟–4 小时" : "睡眠时长需在 15 分钟–16 小时");
        }
    }
}
