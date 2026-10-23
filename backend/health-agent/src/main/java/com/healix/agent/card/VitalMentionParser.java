package com.healix.agent.card;

import com.healix.core.vitals.support.VitalValueRules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从患者原话抽出血压、血糖、体重。数字只来自原文，不交给模型。
 */
public final class VitalMentionParser {

    private static final Pattern TIME_BLOCK = Pattern.compile(
            "昨天|昨晚|昨日|前日|前天|以前|上次|早上|早晨|清晨|上午|中午|午后|下午|晚上|傍晚|夜里|夜间|今晚");
    private static final Pattern MG = Pattern.compile("毫克|mg\\s*/\\s*dl", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY = Pattern.compile("怎么样|咋样|如何|多少|趋势|正常吗|高不高");
    private static final Pattern BP_PAIR = Pattern.compile(
            "(?:血压|高压|收缩压)(?:是|为)?\\s*[:：]?\\s*(\\d{2,3}(?:\\.\\d+)?)\\s*[/／]\\s*(\\d{2,3}(?:\\.\\d+)?)");
    private static final Pattern BP_HIGH_LOW = Pattern.compile(
            "高压(?:是|为)?\\s*[:：]?\\s*(\\d{2,3}(?:\\.\\d+)?)\\s*[,，、 ]*低压(?:是|为)?\\s*[:：]?\\s*(\\d{2,3}(?:\\.\\d+)?)");
    private static final Pattern BP_SYS_DIA = Pattern.compile(
            "收缩压(?:是|为)?\\s*[:：]?\\s*(\\d{2,3}(?:\\.\\d+)?).*?舒张压(?:是|为)?\\s*[:：]?\\s*(\\d{2,3}(?:\\.\\d+)?)");
    private static final Pattern BP_SLASH = Pattern.compile("(\\d{2,3}(?:\\.\\d+)?)\\s*[/／]\\s*(\\d{2,3}(?:\\.\\d+)?)");
    private static final Pattern BP_SINGLE = Pattern.compile(
            "(?:血压|高压|收缩压)(?:是|为)?\\s*[:：]?\\s*(\\d{2,3}(?:\\.\\d+)?)(?!\\s*[/／])");
    private static final Pattern GLUCOSE = Pattern.compile(
            "血糖(?:是|为)?\\s*[:：]?\\s*(\\d{1,3}(?:\\.\\d+)?)");
    private static final Pattern WEIGHT = Pattern.compile(
            "体重(?:是|为)?\\s*[:：]?\\s*(\\d{1,3}(?:\\.\\d+)?)\\s*(斤|公斤|kg)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern JIN = Pattern.compile("(\\d{2,3}(?:\\.\\d+)?)\\s*斤");

    private static final String CHECK = "请核对下面的记录，确认后才会记入。";
    private static final String TIME_HINT = "早上、中午、晚上或昨天的测量，请到指标页补记。我这里只记刚才这一次。";
    private static final String GLUCOSE_UNIT = "血糖请用 mmol/L 再说一次，例如 7.2。";
    private static final String BAD_NUMBER = "这个数对不上，请再说一次。";
    private static final String NEED_DIA = "请把舒张压也一起说，例如 165/98。";
    private static final String PAIR_ORDER = "收缩压应高于舒张压，请再说一次。";

    private VitalMentionParser() {}

    public record Outcome(boolean handled, String reply, List<VitalCardPayload> cards) {
        public static Outcome pass() {
            return new Outcome(false, "", List.of());
        }
    }

    public static Outcome parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return Outcome.pass();
        }
        boolean timeBlocked = TIME_BLOCK.matcher(text).find();
        boolean milligram = MG.matcher(text).find();
        List<String> notes = new ArrayList<>();
        List<VitalCardPayload> cards = new ArrayList<>();

        BpExtract bp = extractBp(text);
        if (bp.incomplete && bp.payload == null) {
            notes.add(NEED_DIA);
        } else if (bp.payload != null) {
            if (bp.payload.getSys().compareTo(bp.payload.getDia()) <= 0) {
                notes.add(PAIR_ORDER);
            } else if (!VitalValueRules.isPlausible("BLOOD_PRESSURE_SYS", bp.payload.getSys())
                    || !VitalValueRules.isPlausible("BLOOD_PRESSURE_DIA", bp.payload.getDia())) {
                notes.add(BAD_NUMBER);
            } else {
                cards.add(bp.payload);
            }
        }

        GlucoseExtract glucose = extractGlucose(text);
        if (glucose.present) {
            if (milligram || !VitalValueRules.isPlausible("BLOOD_GLUCOSE", glucose.value)) {
                notes.add(GLUCOSE_UNIT);
            } else {
                VitalCardPayload card = new VitalCardPayload();
                card.setMetric("GLUCOSE");
                card.setValue(glucose.value);
                card.setMealContext(mealContext(text));
                cards.add(card);
            }
        }

        WeightExtract weight = extractWeight(text);
        if (weight.present) {
            if (!VitalValueRules.isPlausible("WEIGHT", weight.kg)) {
                notes.add(BAD_NUMBER);
            } else {
                VitalCardPayload card = new VitalCardPayload();
                card.setMetric("WEIGHT");
                card.setValue(weight.kg);
                card.setJinRaw(weight.jinRaw);
                cards.add(card);
            }
        }

        if (timeBlocked && shouldRefuseTimedReading(text, cards, notes)) {
            return new Outcome(true, TIME_HINT, List.of());
        }

        if (cards.isEmpty() && notes.isEmpty()) {
            if (isPrompt(text, "血压")) {
                return new Outcome(true, "说一下刚才的血压，例如 165/98。", List.of());
            }
            if (isPrompt(text, "血糖")) {
                return new Outcome(true, "说一下刚才的血糖，例如 7.2。", List.of());
            }
            if (isPrompt(text, "体重")) {
                return new Outcome(true, "说一下刚才的体重，例如 70 公斤。", List.of());
            }
            if (QUERY.matcher(text).find()) {
                return Outcome.pass();
            }
            return Outcome.pass();
        }

        StringBuilder reply = new StringBuilder();
        if (!cards.isEmpty()) {
            reply.append(CHECK);
        }
        for (String note : notes) {
            if (reply.length() > 0) {
                reply.append('\n');
            }
            reply.append(note);
        }
        return new Outcome(true, reply.toString(), List.copyOf(cards));
    }

    /** 点了时段又在报数或要求记下，整句都不出卡。纯提问仍交给原来的问答。 */
    private static boolean shouldRefuseTimedReading(String text, List<VitalCardPayload> cards, List<String> notes) {
        if (!cards.isEmpty() || !notes.isEmpty()) {
            return true;
        }
        if (!mentionsMetric(text)) {
            return false;
        }
        return text.matches(".*\\d.*") || text.contains("记一下") || text.contains("帮我记");
    }

    private static boolean mentionsMetric(String text) {
        return text.contains("血压") || text.contains("血糖") || text.contains("体重") || text.contains("高压");
    }

    private static boolean isPrompt(String text, String metric) {
        return (text.contains("记一下") || text.contains("帮我记")) && text.contains(metric) && !text.matches(".*\\d.*");
    }

    private static BpExtract extractBp(String text) {
        BigDecimal[] nums = matchPair(text);
        if (nums != null) {
            VitalCardPayload card = new VitalCardPayload();
            card.setMetric("BP");
            card.setSys(nums[0]);
            card.setDia(nums[1]);
            card.setBpContext(bpContext(text));
            return new BpExtract(card, false);
        }
        if (BP_SINGLE.matcher(text).find()) {
            return new BpExtract(null, true);
        }
        return new BpExtract(null, false);
    }

    private static BigDecimal[] matchPair(String text) {
        Matcher m = BP_PAIR.matcher(text);
        if (m.find()) {
            return new BigDecimal[] {decimal(m.group(1)), decimal(m.group(2))};
        }
        m = BP_HIGH_LOW.matcher(text);
        if (m.find()) {
            return new BigDecimal[] {decimal(m.group(1)), decimal(m.group(2))};
        }
        m = BP_SYS_DIA.matcher(text);
        if (m.find()) {
            return new BigDecimal[] {decimal(m.group(1)), decimal(m.group(2))};
        }
        if (text.contains("血压")) {
            m = BP_SLASH.matcher(text);
            if (m.find()) {
                return new BigDecimal[] {decimal(m.group(1)), decimal(m.group(2))};
            }
        }
        return null;
    }

    private static GlucoseExtract extractGlucose(String text) {
        Matcher m = GLUCOSE.matcher(text);
        if (!m.find()) {
            return new GlucoseExtract(false, null);
        }
        return new GlucoseExtract(true, decimal(m.group(1)));
    }

    private static WeightExtract extractWeight(String text) {
        Matcher m = WEIGHT.matcher(text);
        if (m.find()) {
            BigDecimal raw = decimal(m.group(1));
            String unit = m.group(2);
            if (unit != null && unit.contains("斤")) {
                return converted(raw);
            }
            return new WeightExtract(true, raw, null);
        }
        m = JIN.matcher(text);
        if (m.find()) {
            return converted(decimal(m.group(1)));
        }
        return new WeightExtract(false, null, null);
    }

    private static WeightExtract converted(BigDecimal jin) {
        BigDecimal kg = jin.divide(new BigDecimal("2"), 1, RoundingMode.HALF_UP);
        return new WeightExtract(true, kg, jin);
    }

    private static String bpContext(String text) {
        int clinic = indexOfAny(text, "诊室", "医院", "门诊");
        int home = indexOfAny(text, "家里", "在家", "家庭");
        if (clinic >= 0 && (home < 0 || clinic < home)) {
            return "CLINIC";
        }
        return "HOME";
    }

    private static String mealContext(String text) {
        int fasting = text.indexOf("空腹");
        int after = indexOfAny(text, "餐后", "饭后");
        if (fasting >= 0 && (after < 0 || fasting < after)) {
            return "FASTING";
        }
        if (after >= 0) {
            return "POSTPRANDIAL";
        }
        return "RANDOM";
    }

    private static int indexOfAny(String text, String... words) {
        int best = -1;
        for (String word : words) {
            int i = text.indexOf(word);
            if (i >= 0 && (best < 0 || i < best)) {
                best = i;
            }
        }
        return best;
    }

    private static BigDecimal decimal(String raw) {
        return new BigDecimal(raw);
    }

    private record BpExtract(VitalCardPayload payload, boolean incomplete) {}

    private record GlucoseExtract(boolean present, BigDecimal value) {}

    private record WeightExtract(boolean present, BigDecimal kg, BigDecimal jinRaw) {}
}
