package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.core.compliance.ComplianceText;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 饮食 notes（患者可见「执行提醒」）合规清洗：去掉内部质检话术、拆分冗长句、去医疗化用语。
 */
public final class CarePlanDietNotesGuard {

    private static final Pattern STAFF_ONLY = Pattern.compile(
            "过敏忌口尚未采集|过敏史未(?:采集|录入|确认)|无法排除.{0,12}过敏|请尽快补(?:全|采).{0,8}过敏|如已知对.{0,20}过敏|饮食建议无法排除过敏风险");

    private CarePlanDietNotesGuard() {}

    public static void sanitize(ObjectNode diet) {
        if (diet == null || !diet.has("notes")) {
            return;
        }
        String raw = diet.path("notes").asText("");
        if (!StringUtils.hasText(raw)) {
            return;
        }
        List<String> lines = split(raw);
        Set<String> out = new LinkedHashSet<>();
        for (String line : lines) {
            String n = normalize(line);
            if (StringUtils.hasText(n)) {
                out.add(n);
            }
        }
        if (out.isEmpty()) {
            diet.put("notes", "");
        } else {
            diet.put("notes", String.join("；", out));
        }
    }

    static String normalize(String raw) {
        String t = StringUtils.hasText(raw) ? raw.trim() : "";
        t = t.replaceFirst("^[☑✓✔☐□]\\s*", "").trim();
        if (!StringUtils.hasText(t) || STAFF_ONLY.matcher(t).find()) {
            return "";
        }
        if (containsAny(t, "先吃蔬菜", "先吃菜", "进餐顺序") && containsAny(t, "主食", "蛋白质", "蛋白")) {
            return "进餐顺序：建议先吃蔬菜和蛋白质，最后吃主食，有助于平稳餐后血糖。";
        }
        if (containsAny(t, "第一口饭", "随餐", "跟餐", "服药配合", "阿卡波糖")
                && (t.contains("服") || t.contains("药"))) {
            return "服药配合：若您正在使用需要随餐服用的药物（如阿卡波糖），请记得与第一口饭同服。";
        }
        if (containsAny(t, "外出就餐", "外食", "少盐少油", "酱料")) {
            return "外食技巧：外出就餐时，主动要求少盐少油，酱料尽量另放。";
        }
        if (t.contains("降压") && containsAny(t, "按时", "起身", "头晕", "医生建议", "遵")) {
            return "日常习惯：降压相关药物请按医生建议按时服用，起身时动作放缓，避免头晕。";
        }
        if (containsAny(t, "睡眠呼吸监测", "多导睡眠")
                || (t.contains("打鼾") && containsAny(t, "呼吸内科", "耳鼻喉", "监测"))) {
            return CarePlanOsaHintSupport.HINT;
        }
        if (containsAny(t, "关注睡眠呼吸", "留意睡眠呼吸", "注意睡眠呼吸")) {
            // 过弱表述留给 OsaHintSupport 按档案决定是否升格；此处先丢掉以免双写
            return "";
        }
        t = t.replace("遵医嘱", "按医生建议")
                .replace("按医嘱", "按医生建议")
                .replace("医嘱", "医生建议")
                .replace("需随第一口饭同服", "请记得与第一口饭同服")
                .replace("不要空腹服药", "尽量避免空腹服药");
        t = ComplianceText.filter(t);
        return t.replaceAll("^[；;，,。]+|[；;，,。]+$", "").trim();
    }

    private static boolean containsAny(String text, String... needles) {
        if (!StringUtils.hasText(text) || needles == null) {
            return false;
        }
        for (String n : needles) {
            if (StringUtils.hasText(n) && text.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> split(String raw) {
        List<String> out = new ArrayList<>();
        // 按分号/句号/换行切开，避免「服药 + 进餐顺序」粘成一句只映射成一条
        for (String part : raw.split("[；;\\n。！？.]+")) {
            String p = part.trim();
            if (StringUtils.hasText(p)) {
                out.add(p);
            }
        }
        return out;
    }
}
