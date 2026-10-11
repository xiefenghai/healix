package com.healix.core.compliance;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 面向用户文案的诊疗用语替换（健康管理合规）：AI 输出、计划任务名落库前统一过一遍。
 *
 * <p>只改肯定语境；同一分句里出现「不构成 / 不做 / 非」等否定词时原样保留（免责声明本身要用这些词）。
 * 与 {@code scripts/check-sensitive-words.mjs} 的词表保持一致。
 */
public final class ComplianceText {

    /** MySQL REGEXP：筛出可能需要替换的历史数据 */
    public static final String MYSQL_REGEXP = "医嘱|处方|确诊|诊断|治疗方案|开药|疗效|治愈|根治|病历";

    private ComplianceText() {}

    private record Rule(Pattern pattern, String replacement) {}

    /** 长短语优先 */
    private static final List<Rule> RULES = List.of(
            rule("按医嘱(服药|服用|用药)", "按时$1"),
            rule("按医嘱(监测|测量|复查|复诊)", "按时$1"),
            rule("(遵照|遵从|遵|按照|按)医嘱", "按医生建议"),
            rule("医嘱", "医生建议"),
            rule("(运动|饮食|营养|膳食|康复)处方", "$1计划"),
            rule("处方药", "药品"),
            rule("处方", "用药单"),
            rule("治疗方案", "管理方案"),
            rule("已确诊", "已患"),
            rule("确诊为", "已患"),
            rule("确诊", "已患"),
            rule("诊断为", "评估为"),
            rule("诊断", "评估"),
            rule("开药", "配药"),
            rule("疗效", "效果"),
            rule("治愈", "改善"),
            rule("根治", "改善"),
            rule("病历", "健康记录"));

    private static final List<String> NEGATIONS = List.of(
            "不做", "不构成", "非", "不要", "不得", "禁止", "避免", "不替代", "不给", "不涉及", "不提供", "不作", "不开",
            "并非", "不能", "勿");

    /** 分句边界（保留在结果里） */
    private static final Pattern CLAUSE = Pattern.compile("[^，。；！？,;!?\\n]+|[，。；！？,;!?\\n]");

    private static Rule rule(String regex, String replacement) {
        return new Rule(Pattern.compile(regex), replacement);
    }

    public static String filter(String text) {
        if (!StringUtils.hasText(text) || !containsAny(text)) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        Matcher m = CLAUSE.matcher(text);
        while (m.find()) {
            out.append(filterClause(m.group()));
        }
        return out.toString();
    }

    private static String filterClause(String clause) {
        if (!containsAny(clause) || NEGATIONS.stream().anyMatch(clause::contains)) {
            return clause;
        }
        String s = clause;
        for (Rule r : RULES) {
            s = r.pattern().matcher(s).replaceAll(r.replacement());
        }
        return s;
    }

    private static boolean containsAny(String s) {
        return s.contains("医嘱") || s.contains("处方") || s.contains("诊") || s.contains("治疗方案")
                || s.contains("开药") || s.contains("疗效") || s.contains("治愈") || s.contains("根治")
                || s.contains("病历");
    }
}
