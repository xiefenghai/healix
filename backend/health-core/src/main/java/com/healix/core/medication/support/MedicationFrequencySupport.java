package com.healix.core.medication.support;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 用药频次解析：把自由文本 frequency 归一为「每日应服次数」与建议时段。
 *
 * <p>{@code frequency} 是 128 字自由文本（医嘱抄录），既可能是 QD/BID/TID 这类拉丁缩写，
 * 也可能是「每日两次」「2次/日」「q12h」。解析不出来时按每日 1 次兜底，
 * 避免把未知写法直接判成不依从。
 *
 * <p>PRN（按需）不计入应服次数：无固定医嘱次数，漏服无从判定。
 */
public final class MedicationFrequencySupport {

    /** 时段码，与 people_medication_intake.time_slot 一致 */
    public static final String MORNING = "MORNING";
    public static final String NOON = "NOON";
    public static final String EVENING = "EVENING";
    public static final String BEDTIME = "BEDTIME";
    public static final String OTHER = "OTHER";

    /** 解析失败时的兜底次数 */
    public static final int DEFAULT_DOSES_PER_DAY = 1;
    /** 单日应服次数上限，防止 q1h 之类写法把依从率分母打爆 */
    public static final int MAX_DOSES_PER_DAY = 6;

    private static final Pattern TIMES_PER_DAY =
            Pattern.compile("(\\d+)\\s*(?:次|回)?\\s*/\\s*(?:日|天|d|day)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PER_DAY_TIMES =
            Pattern.compile("(?:每日|每天|1日|一日|日)\\s*(\\d+)\\s*(?:次|回)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVERY_N_HOURS =
            Pattern.compile("q\\s*(\\d+)\\s*h", Pattern.CASE_INSENSITIVE);

    private MedicationFrequencySupport() {}

    /** 按需服药：不计应服次数。 */
    public static boolean isPrn(String frequency) {
        if (!StringUtils.hasText(frequency)) {
            return false;
        }
        String s = normalize(frequency);
        return s.contains("PRN") || s.contains("按需") || s.contains("必要时");
    }

    /**
     * 每日应服次数。PRN 返回 0；无法识别返回 {@link #DEFAULT_DOSES_PER_DAY}。
     */
    public static int dosesPerDay(String frequency) {
        if (isPrn(frequency)) {
            return 0;
        }
        if (!StringUtils.hasText(frequency)) {
            return DEFAULT_DOSES_PER_DAY;
        }
        String s = normalize(frequency);

        Integer explicit = matchExplicitCount(s);
        if (explicit != null) {
            return clamp(explicit);
        }
        Integer latin = matchLatinAbbrev(s);
        if (latin != null) {
            return clamp(latin);
        }
        Integer chinese = matchChineseCount(s);
        if (chinese != null) {
            return clamp(chinese);
        }
        // 隔日 / 每周 等低频写法：单日应服按 1 次算（不判漏服由 isDueOnDay 另行处理）
        return DEFAULT_DOSES_PER_DAY;
    }

    /**
     * 该频次在指定日期是否应服。
     *
     * <p>隔日（QOD）与每周（QW）等低频医嘱缺少起算基准，这里保守返回 true，
     * 由健管师在随访中核对，避免漏提醒。
     */
    public static boolean isDueOnDay(String frequency) {
        return !isPrn(frequency);
    }

    /**
     * 建议打卡时段；供 C 端渲染打卡格子。
     *
     * <p>每日一次与 PRN 统一用 {@link #OTHER}：C 端历史打卡都写在 OTHER 槽，
     * 换成 MORNING 会让已打卡的记录在界面上「消失」。5 次以上封顶到 5 个槽。
     */
    public static List<String> suggestedSlots(String frequency) {
        return switch (dosesPerDay(frequency)) {
            case 0, 1 -> List.of(OTHER);
            case 2 -> List.of(MORNING, EVENING);
            case 3 -> List.of(MORNING, NOON, EVENING);
            case 4 -> List.of(MORNING, NOON, EVENING, BEDTIME);
            default -> List.of(MORNING, NOON, EVENING, BEDTIME, OTHER);
        };
    }

    private static Integer matchExplicitCount(String s) {
        Matcher m = TIMES_PER_DAY.matcher(s);
        if (m.find()) {
            return parse(m.group(1));
        }
        m = PER_DAY_TIMES.matcher(s);
        if (m.find()) {
            return parse(m.group(1));
        }
        m = EVERY_N_HOURS.matcher(s);
        if (m.find()) {
            Integer hours = parse(m.group(1));
            if (hours != null && hours > 0) {
                return Math.max(1, 24 / hours);
            }
        }
        return null;
    }

    private static Integer matchLatinAbbrev(String s) {
        // 先匹配长缩写，避免 QID 被 QD 前缀误吞
        if (s.contains("QID")) {
            return 4;
        }
        if (s.contains("TID") || s.contains("TDS")) {
            return 3;
        }
        if (s.contains("BID") || s.contains("BD")) {
            return 2;
        }
        if (s.contains("QD") || s.contains("SID") || s.contains("QN") || s.contains("HS")) {
            return 1;
        }
        return null;
    }

    private static Integer matchChineseCount(String s) {
        if (s.contains("四次")) {
            return 4;
        }
        if (s.contains("三次")) {
            return 3;
        }
        if (s.contains("两次") || s.contains("二次")) {
            return 2;
        }
        if (s.contains("一次") || s.contains("每日") || s.contains("每天")) {
            return 1;
        }
        return null;
    }

    private static String normalize(String frequency) {
        return frequency.trim().toUpperCase().replace(" ", "");
    }

    private static Integer parse(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int clamp(int doses) {
        if (doses < 1) {
            return DEFAULT_DOSES_PER_DAY;
        }
        return Math.min(doses, MAX_DOSES_PER_DAY);
    }
}
