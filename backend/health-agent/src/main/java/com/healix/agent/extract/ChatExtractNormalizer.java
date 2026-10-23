package com.healix.agent.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.core.vitals.support.VitalValueRules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 把模型 JSON 收成核对卡：白名单、时间、单位、习惯句都在这里收口。 */
public final class ChatExtractNormalizer {

    static final int MAX_QUOTE = 80;
    static final int MAX_TEXT = 40;

    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2})\\s*[:：]\\s*(\\d{1,2})");
    private static final Pattern CLOCK_HALF = Pattern.compile("(\\d{1,2})\\s*点半");
    private static final Pattern CLOCK_MINUTE = Pattern.compile("(\\d{1,2})\\s*点\\s*(\\d{1,2})\\s*分");
    private static final Pattern CLOCK_HOUR = Pattern.compile("(\\d{1,2})\\s*点(?!半)");
    private static final Pattern DATE_YMD =
            Pattern.compile("(\\d{4})\\s*[年\\-]\\s*(\\d{1,2})\\s*[月\\-]\\s*(\\d{1,2})\\s*[日号]?");
    private static final Pattern DATE_MD = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]");
    /** 说了时段，但落不到具体某一天。出现后不再沿用前面的日期和钟点。 */
    private static final String[] VAGUE_PERIODS = {"前几天", "前几日", "最近", "上周"};
    private static final Pattern QUIT_YEARS = Pattern.compile("戒了\\s*([0-9一二两三四五六七八九十]{1,3})\\s*年");
    private static final Pattern QUIT_YEAR = Pattern.compile("((?:19|20)\\d{2})\\s*年");
    private static final Pattern BP_PAIR = Pattern.compile("(\\d{2,3})\\s*[/／]\\s*(\\d{2,3})");
    private static final Pattern ONE_OFF = Pattern.compile("昨晚|今天|今日|刚才|这一顿|这顿|今早|今晨");
    private static final Pattern HABIT = Pattern.compile("平时|一般|每天|每日|常常|经常|已经|戒了|从来不|从不|习惯");

    private ChatExtractNormalizer() {}

    public static ChatExtractCard normalize(String sourceText, ChatExtractLlmPayload raw, LocalDate today) {
        ChatExtractCard card = new ChatExtractCard();
        String text = sourceText == null ? "" : sourceText;
        card.setLabHint(text.contains("参考范围") || text.contains("参考值"));
        if (raw != null && raw.getIgnored() != null) {
            for (ChatExtractLlmPayload.Ignored ignored : raw.getIgnored()) {
                addIgnored(card, ignored == null ? null : ignored.getQuote(), ignored == null ? null : ignored.getReason());
            }
        }
        List<ChatExtractLlmPayload.Item> items = raw == null || raw.getItems() == null ? List.of() : raw.getItems();
        List<ChatExtractLlmPayload.Item> merged = mergeBloodPressure(items);
        WhenContext when = WhenContext.of(text, today);
        int index = 1;
        for (ChatExtractLlmPayload.Item item : merged) {
            Built built = build(item, when);
            if (built.ignoredReason != null) {
                addIgnored(card, item == null ? null : item.getQuote(), built.ignoredReason);
                continue;
            }
            built.row.setId("r" + index++);
            card.getItems().add(built.row);
        }
        index = promoteDietType(card, text, index);
        index = promoteSpokenHabits(card, text, index, today);
        markArchiveConflicts(card.getItems());
        for (ChatExtractIgnored ignored : card.getIgnored()) {
            if ("LAB_PANEL".equals(ignored.getReason())) {
                card.setLabHint(true);
            }
        }
        return card;
    }

    /** 低盐、低糖说的是习惯时写入饮食类型，并从饮食偏好里拿掉，避免只进自由文本。 */
    private static int promoteDietType(ChatExtractCard card, String source, int index) {
        boolean hasType = card.getItems().stream()
                .anyMatch(row -> "diet.type".equals(row.getCode())
                        && row.getArchiveValue() != null
                        && !row.getArchiveValue().isBlank());
        String evidence = dietTypeEvidence(source);
        String code = evidence == null ? null : dietTypeCode(evidence);
        if (!hasType && code != null) {
            ChatExtractCatalog.ArchiveField field = ChatExtractCatalog.ARCHIVE.get("diet.type");
            ChatExtractRow row = new ChatExtractRow();
            row.setId("r" + index++);
            row.setTarget("ARCHIVE");
            row.setCode("diet.type");
            row.setLabel(field.label());
            Quote quote = clipQuote(evidence);
            row.setQuote(quote.text);
            row.setQuoteTruncated(quote.truncated);
            row.setOptions(field.options());
            row.setArchiveValue(code);
            ready(row);
            card.getItems().add(row);
        }
        card.getItems().removeIf(row -> {
            if (!"diet.preference".equals(row.getCode()) || row.getArchiveValue() == null) {
                return false;
            }
            String stripped = stripDietTypeWords(row.getArchiveValue());
            row.setArchiveValue(stripped);
            return stripped.isBlank();
        });
        return index;
    }

    private static String dietTypeEvidence(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        StringBuilder evidence = new StringBuilder();
        boolean salt = false;
        boolean sugar = false;
        boolean vegetarian = false;
        boolean normal = false;
        for (String sentence : sentences(source)) {
            if (!mentionsDietType(sentence) || !habitSentence(sentence)) {
                continue;
            }
            if (ONE_OFF.matcher(sentence).find() && !HABIT.matcher(sentence).find() && !sentence.contains("一直")) {
                continue;
            }
            salt |= sentence.contains("低盐");
            sugar |= sentence.contains("低糖");
            vegetarian |= sentence.contains("素食");
            normal |= sentence.contains("普通饮食");
            if (!evidence.isEmpty()) {
                evidence.append(' ');
            }
            evidence.append(sentence.trim());
        }
        if (!salt && !sugar && !vegetarian && !normal) {
            return null;
        }
        return evidence.toString();
    }

    private static boolean mentionsDietType(String sentence) {
        return sentence.contains("低盐")
                || sentence.contains("低糖")
                || sentence.contains("素食")
                || sentence.contains("普通饮食");
    }

    private static boolean habitSentence(String sentence) {
        return HABIT.matcher(sentence).find() || sentence.contains("一直") || sentence.contains("长期");
    }

    private static String dietTypeCode(String text) {
        boolean salt = text.contains("低盐");
        boolean sugar = text.contains("低糖");
        if (salt && sugar) {
            return "LOW_SALT_SUGAR";
        }
        if (salt) {
            return "LOW_SALT";
        }
        if (sugar) {
            return "LOW_SUGAR";
        }
        if (text.contains("素食")) {
            return "VEGETARIAN";
        }
        if (text.contains("普通饮食")) {
            return "NORMAL";
        }
        return null;
    }

    private static String stripDietTypeWords(String text) {
        String out = text;
        for (String word : List.of("低盐低糖", "低糖低盐", "普通饮食", "低盐", "低糖", "素食")) {
            out = out.replace(word, "");
        }
        out = out.replaceAll("^[，,、；;\\s]+|[，,、；;\\s]+$", "");
        out = out.replaceAll("(?:[，,、]){2,}", "，");
        return out.trim();
    }

    private static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '\n' || c == '；') {
                String sentence = current.toString().trim();
                if (!sentence.isEmpty()) {
                    out.add(sentence);
                }
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            out.add(current.toString().trim());
        }
        return out;
    }

    /** 习惯说法里已经点名的选项，补进对应字段，不留在自由文本里。 */
    private static int promoteSpokenHabits(ChatExtractCard card, String source, int index, LocalDate today) {
        StringBuilder exerciseEvidence = new StringBuilder();
        for (String sentence : sentences(source)) {
            if (!lifestyleSentence(sentence)) {
                continue;
            }
            index = ensureArchive(card, index, "diet.appetite", appetiteCode(sentence), sentence);
            index = ensureArchive(card, index, "diet.habit", dietHabitCode(sentence), sentence);
            if (exerciseSentence(sentence)) {
                exerciseEvidence.append(sentence);
                index = ensureArchive(card, index, "exercise.frequency", exerciseFrequency(sentence), sentence);
                index = ensureArchive(card, index, "exercise.intensity", intensityCode(sentence), sentence);
                index = ensureArchive(card, index, "exercise.durationMin", durationMinutes(sentence), sentence);
                index = ensureArchive(card, index, "exercise.type", activity(sentence), sentence);
            }
            if (sentence.contains("睡")) {
                index = ensureArchive(card, index, "sleep.quality", sleepQuality(sentence), sentence);
                index = ensureArchive(card, index, "sleep.hours", sleepHours(sentence), sentence);
                index = ensureArchive(card, index, "sleep.disorder", sleepDisorder(sentence), sentence);
            }
            if (smokingSentence(sentence)) {
                index = ensureArchive(card, index, "lifestyle.smoking.status", smokingStatus(sentence), sentence);
                index = ensureArchive(card, index, "lifestyle.smoking.cigarettesPerDay", cigarettes(sentence), sentence);
                index = ensureArchive(card, index, "lifestyle.smoking.years", smokingYears(sentence), sentence);
                index = ensureQuitYear(card, index, sentence, today);
            }
            if (drinkingSentence(sentence)) {
                index = ensureArchive(card, index, "lifestyle.drinking.status", drinkingStatus(sentence), sentence);
                String status = drinkingStatus(sentence);
                if (!"NEVER".equals(status) && !"FORMER".equals(status)) {
                    index = ensureArchive(card, index, "lifestyle.drinking.frequency", drinkingFrequency(sentence), sentence);
                    index = ensureArchive(card, index, "lifestyle.drinking.type", liquor(sentence), sentence);
                    index = ensureArchive(card, index, "lifestyle.drinking.amountPerDay", drinkAmount(sentence), sentence);
                }
            }
        }
        tidyExerciseType(card, exerciseEvidence.toString());
        tidyDrinkingText(card);
        tidyPreference(card);
        return index;
    }

    private static int ensureArchive(ChatExtractCard card, int index, String code, String value, String evidence) {
        if (value == null || value.isBlank() || hasArchive(card, code)) {
            return index;
        }
        ChatExtractCatalog.ArchiveField field = ChatExtractCatalog.ARCHIVE.get(code);
        if (field == null) {
            return index;
        }
        ChatExtractRow row = new ChatExtractRow();
        row.setId("r" + index++);
        row.setTarget("ARCHIVE");
        row.setCode(code);
        row.setLabel(field.label());
        Quote quote = clipQuote(evidence);
        row.setQuote(quote.text);
        row.setQuoteTruncated(quote.truncated);
        row.setTextField(field.text());
        row.setOptions(field.options());
        row.setArchiveValue(value);
        ready(row);
        card.getItems().add(row);
        return index;
    }

    private static int ensureQuitYear(ChatExtractCard card, int index, String sentence, LocalDate today) {
        if (hasArchive(card, "lifestyle.smoking.quitYear") || !sentence.contains("戒")) {
            return index;
        }
        ChatExtractRow row = new ChatExtractRow();
        applyQuitYear(row, sentence, "", today);
        if (row.getArchiveValue() == null || row.getArchiveValue().isBlank()) {
            return index;
        }
        ChatExtractCatalog.ArchiveField field = ChatExtractCatalog.ARCHIVE.get("lifestyle.smoking.quitYear");
        row.setId("r" + index++);
        row.setTarget("ARCHIVE");
        row.setCode(field.code());
        row.setLabel(field.label());
        Quote quote = clipQuote(sentence);
        row.setQuote(quote.text);
        row.setQuoteTruncated(quote.truncated);
        ready(row);
        card.getItems().add(row);
        return index;
    }

    private static boolean hasArchive(ChatExtractCard card, String code) {
        return card.getItems().stream()
                .anyMatch(row -> code.equals(row.getCode())
                        && row.getArchiveValue() != null
                        && !row.getArchiveValue().isBlank());
    }

    private static boolean lifestyleSentence(String sentence) {
        if (sentence == null || sentence.isBlank()) {
            return false;
        }
        boolean oneOff = ONE_OFF.matcher(sentence).find();
        if (oneOff && !habitSentence(sentence)) {
            return false;
        }
        return habitSentence(sentence) || weeklyHabit(sentence);
    }

    private static boolean weeklyHabit(String sentence) {
        if (sentence.contains("上一周") || sentence.contains("上星期")) {
            return false;
        }
        return sentence.contains("每周") || sentence.contains("每星期") || sentence.contains("天天") || sentence.contains("一周");
    }

    private static boolean exerciseSentence(String sentence) {
        return sentence.contains("运动")
                || sentence.contains("锻炼")
                || sentence.contains("快走")
                || sentence.contains("散步")
                || sentence.contains("跑步")
                || sentence.contains("慢跑")
                || sentence.contains("游泳")
                || sentence.contains("骑车")
                || sentence.contains("瑜伽")
                || sentence.contains("太极")
                || sentence.contains("打球");
    }

    private static boolean smokingSentence(String sentence) {
        return !sentence.contains("抽血") && (sentence.contains("烟") || sentence.contains("一包"));
    }

    private static boolean drinkingSentence(String sentence) {
        return sentence.contains("酒");
    }

    private static String appetiteCode(String sentence) {
        if (!sentence.contains("食欲")) {
            return null;
        }
        if (sentence.contains("食欲较差") || sentence.contains("食欲不好") || sentence.contains("食欲差") || sentence.contains("没什么食欲")) {
            return "POOR";
        }
        if (sentence.contains("食欲一般")) {
            return "FAIR";
        }
        if (sentence.contains("食欲正常")) {
            return "NORMAL";
        }
        if (sentence.contains("食欲好") || sentence.contains("食欲不错") || sentence.contains("食欲很好")) {
            return "GOOD";
        }
        return null;
    }

    private static String dietHabitCode(String sentence) {
        if (!sentence.contains("吃") && !sentence.contains("饭") && !sentence.contains("饮食") && !sentence.contains("三餐")) {
            return null;
        }
        if (sentence.contains("不规律")) {
            return "POOR";
        }
        if (sentence.contains("还算规律") || sentence.contains("比较规律")) {
            return "FAIR";
        }
        if (sentence.contains("规律") || sentence.contains("按时吃")) {
            return "GOOD";
        }
        return null;
    }

    private static String exerciseFrequency(String sentence) {
        if (sentence.contains("基本不运动")
                || sentence.contains("不怎么运动")
                || sentence.contains("不运动")
                || sentence.contains("不锻炼")
                || sentence.contains("很少运动")) {
            return "NONE";
        }
        int times = weekTimes(sentence);
        if (times >= 3) {
            return "WEEKLY_3";
        }
        if (times > 0 || sentence.contains("一两次") || sentence.contains("1-2次") || sentence.contains("1～2次")) {
            return "WEEKLY_1";
        }
        if (sentence.contains("每天") || sentence.contains("天天") || sentence.contains("每日")) {
            return "DAILY";
        }
        return null;
    }

    private static String intensityCode(String sentence) {
        if (sentence.contains("剧烈") || sentence.contains("高强度") || sentence.contains("重度")) {
            return "VIGOROUS";
        }
        if (sentence.contains("中等") || sentence.contains("中度")) {
            return "MODERATE";
        }
        if (sentence.contains("轻度")) {
            return "LIGHT";
        }
        return null;
    }

    private static String durationMinutes(String sentence) {
        Matcher minutes = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*分钟").matcher(sentence);
        if (minutes.find()) {
            int amount = (int) Math.round(Double.parseDouble(minutes.group(1)));
            if (amount >= 1 && amount <= 300) {
                return String.valueOf(amount);
            }
        }
        if (sentence.contains("一个半小时")) {
            return "90";
        }
        if (sentence.contains("半小时") || sentence.contains("半个小时")) {
            return "30";
        }
        Matcher hours = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*个?小时").matcher(sentence);
        if (hours.find() && (sentence.contains("每次") || sentence.contains("运动"))) {
            int amount = (int) Math.round(Double.parseDouble(hours.group(1)) * 60);
            if (amount >= 1 && amount <= 300) {
                return String.valueOf(amount);
            }
        }
        return null;
    }

    private static String sleepQuality(String sentence) {
        if (sentence.contains("睡不好")
                || sentence.contains("睡得差")
                || sentence.contains("睡眠差")
                || sentence.contains("睡眠较差")
                || sentence.contains("睡眠质量差")) {
            return "POOR";
        }
        if (sentence.contains("睡眠一般") || sentence.contains("睡得一般")) {
            return "FAIR";
        }
        if (sentence.contains("睡得好") || sentence.contains("睡眠好") || sentence.contains("睡眠良好") || sentence.contains("睡得不错")) {
            return "GOOD";
        }
        return null;
    }

    private static String sleepHours(String sentence) {
        Matcher matcher = Pattern.compile("睡(?:着|眠|了)?\\s*(\\d+(?:\\.\\d+)?)\\s*个?小时").matcher(sentence);
        if (!matcher.find()) {
            return null;
        }
        double hours = Double.parseDouble(matcher.group(1));
        if (hours < 0.5 || hours > 24) {
            return null;
        }
        return BigDecimal.valueOf(hours).stripTrailingZeros().toPlainString();
    }

    private static String sleepDisorder(String sentence) {
        boolean snore = sentence.contains("打呼噜")
                || sentence.contains("睡眠呼吸")
                || (sentence.contains("打鼾") && !sentence.contains("不打鼾") && !sentence.contains("没有打鼾"));
        boolean insomnia = sentence.contains("失眠") && !sentence.contains("没有失眠") && !sentence.contains("不失眠");
        if (snore) {
            return "SNORING";
        }
        if (insomnia) {
            return "INSOMNIA";
        }
        if (sentence.contains("不打鼾") || sentence.contains("没有失眠") || sentence.contains("睡眠没问题")) {
            return "NONE";
        }
        return null;
    }

    private static String smokingStatus(String sentence) {
        if (sentence.contains("不抽烟")
                || sentence.contains("不吸烟")
                || sentence.contains("从不抽")
                || sentence.contains("从来不抽")
                || sentence.contains("没有抽烟")) {
            return "NEVER";
        }
        if (sentence.contains("戒")) {
            return "FORMER";
        }
        if (sentence.contains("偶尔")) {
            return "OCCASIONAL";
        }
        if (sentence.contains("还在抽") || sentence.contains("每天抽") || sentence.contains("天天抽") || sentence.contains("一直抽")) {
            return "CURRENT";
        }
        return null;
    }

    private static String cigarettes(String sentence) {
        if (sentence.contains("两包")) {
            return "40";
        }
        if (sentence.contains("半包")) {
            return "10";
        }
        if (sentence.contains("一包")) {
            return "20";
        }
        Matcher matcher = Pattern.compile("(?:每天|一天|每日)\\s*([0-9]+|[一二两三四五六七八九十]{1,3})\\s*(?:支|根)")
                .matcher(sentence);
        if (!matcher.find()) {
            return null;
        }
        int amount = chineseNumber(matcher.group(1));
        if (amount < 0 || amount > 100) {
            return null;
        }
        return String.valueOf(amount);
    }

    private static String smokingYears(String sentence) {
        String scope = sentence.replaceAll("戒了\\s*[0-9一二两三四五六七八九十]{1,3}\\s*年", "");
        Matcher matcher = Pattern.compile("(?:烟龄|抽了)\\s*([0-9]+|[一二两三四五六七八九十]{1,3})\\s*年").matcher(scope);
        if (!matcher.find()) {
            return null;
        }
        int years = chineseNumber(matcher.group(1));
        if (years < 0 || years > 80) {
            return null;
        }
        return String.valueOf(years);
    }

    private static String drinkingStatus(String sentence) {
        if (sentence.contains("不喝酒") || sentence.contains("从不喝酒") || sentence.contains("滴酒不沾") || sentence.contains("不饮酒")) {
            return "NEVER";
        }
        if (sentence.contains("戒酒") || sentence.contains("已戒酒") || sentence.contains("戒了")) {
            return "FORMER";
        }
        if (sentence.contains("偶尔")) {
            return "OCCASIONAL";
        }
        if (sentence.contains("每天") || sentence.contains("天天") || sentence.contains("还在喝") || sentence.contains("一直喝")) {
            return "CURRENT";
        }
        return null;
    }

    private static String drinkingFrequency(String sentence) {
        if (sentence.contains("每月") || sentence.contains("一个月")) {
            return "MONTHLY";
        }
        int times = weekTimes(sentence);
        if (times >= 3) {
            return "WEEKLY_3";
        }
        if (times > 0 || sentence.contains("一两次")) {
            return "WEEKLY_1_2";
        }
        if (sentence.contains("每天") || sentence.contains("天天") || sentence.contains("每日")) {
            return "DAILY";
        }
        return null;
    }

    private static int weekTimes(String sentence) {
        Matcher matcher = Pattern.compile("(?:每周|一周|每星期).{0,12}?([0-9一二两三四五六七八九十]+)\\s*次").matcher(sentence);
        if (!matcher.find()) {
            return -1;
        }
        return weekCount(matcher.group(1));
    }

    private static int weekCount(String token) {
        int number = chineseNumber(token);
        if (number > 0) {
            return number;
        }
        if (token.chars().anyMatch(ch -> "三四五六七八九".indexOf(ch) >= 0)) {
            return 3;
        }
        if (token.chars().anyMatch(ch -> "一二两".indexOf(ch) >= 0)) {
            return 1;
        }
        return -1;
    }

    private static int chineseNumber(String token) {
        if (token == null || token.isBlank()) {
            return -1;
        }
        if (token.matches("\\d+")) {
            return Integer.parseInt(token);
        }
        int ten = token.indexOf('十');
        if (ten >= 0) {
            int high = ten == 0 ? 1 : yearsToken(token.substring(0, ten));
            int low = ten == token.length() - 1 ? 0 : yearsToken(token.substring(ten + 1));
            if (high < 0 || low < 0) {
                return -1;
            }
            return high * 10 + low;
        }
        return yearsToken(token);
    }

    private static String liquor(String text) {
        String[] words = {"红葡萄酒", "葡萄酒", "啤酒", "白酒", "红酒", "黄酒", "米酒", "洋酒"};
        List<String> found = new ArrayList<>();
        for (String word : words) {
            if (text.contains(word)) {
                found.add(word);
            }
        }
        return found.isEmpty() ? null : String.join("、", found);
    }

    private static String drinkAmount(String text) {
        Matcher matcher = Pattern.compile("([0-9一二两三四五六七八九十]{1,3})(两|瓶|杯|罐|听)").matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1) + matcher.group(2);
    }

    private static String activity(String text) {
        String[] words = {"快走", "散步", "慢跑", "跑步", "游泳", "骑行", "骑车", "太极", "瑜伽", "爬山", "走路", "步行", "羽毛球", "乒乓球", "篮球", "广场舞", "健身", "打球"};
        List<String> found = new ArrayList<>();
        for (String word : words) {
            if (text.contains(word)) {
                found.add(word);
            }
        }
        return found.isEmpty() ? null : String.join("、", found);
    }

    private static void tidyExerciseType(ChatExtractCard card, String evidence) {
        for (ChatExtractRow row : card.getItems()) {
            if (!"exercise.type".equals(row.getCode()) || row.getArchiveValue() == null) {
                continue;
            }
            String named = activity(row.getArchiveValue() + evidence);
            if (named != null) {
                row.setArchiveValue(named);
            }
        }
    }

    private static void tidyDrinkingText(ChatExtractCard card) {
        for (ChatExtractRow row : card.getItems()) {
            if (row.getArchiveValue() == null) {
                continue;
            }
            if ("lifestyle.drinking.type".equals(row.getCode())) {
                String named = liquor(row.getArchiveValue());
                if (named != null) {
                    row.setArchiveValue(named);
                }
            }
            if ("lifestyle.drinking.amountPerDay".equals(row.getCode())) {
                String amount = drinkAmount(row.getArchiveValue());
                if (amount != null) {
                    row.setArchiveValue(amount);
                }
            }
        }
    }

    private static void tidyPreference(ChatExtractCard card) {
        card.getItems().removeIf(row -> {
            if (!"diet.preference".equals(row.getCode()) || row.getArchiveValue() == null) {
                return false;
            }
            String stripped = stripDietTypeWords(row.getArchiveValue());
            for (String phrase : List.of(
                    "食欲较差", "食欲不好", "食欲正常", "食欲一般", "食欲不错", "食欲好",
                    "吃饭还算规律", "吃饭比较规律", "吃饭不规律", "饮食不规律", "饮食规律", "还算规律", "比较规律", "不规律")) {
                stripped = stripped.replace(phrase, "");
            }
            stripped = stripped.replaceAll("^[，,、；;\\s]+|[，,、；;\\s]+$", "").replaceAll("(?:[，,、]){2,}", "，").trim();
            row.setArchiveValue(stripped);
            return stripped.isBlank();
        });
    }

    private static List<ChatExtractLlmPayload.Item> mergeBloodPressure(List<ChatExtractLlmPayload.Item> items) {
        ChatExtractLlmPayload.Item sys = null;
        ChatExtractLlmPayload.Item dia = null;
        List<ChatExtractLlmPayload.Item> rest = new ArrayList<>();
        for (ChatExtractLlmPayload.Item item : items) {
            if (item == null || item.getCode() == null) {
                rest.add(item);
                continue;
            }
            String code = item.getCode().trim();
            if ("BLOOD_PRESSURE_SYS".equals(code)) {
                sys = item;
            } else if ("BLOOD_PRESSURE_DIA".equals(code)) {
                dia = item;
            } else {
                rest.add(item);
            }
        }
        if (sys != null || dia != null) {
            ChatExtractLlmPayload.Item pair = new ChatExtractLlmPayload.Item();
            pair.setTarget("VITAL");
            pair.setCode("BLOOD_PRESSURE");
            pair.setSystolic(sys == null ? null : firstNumber(sys.getSystolic(), decimal(sys.getValue())));
            pair.setDiastolic(dia == null ? null : firstNumber(dia.getDiastolic(), decimal(dia.getValue())));
            pair.setQuote(sys != null && sys.getQuote() != null ? sys.getQuote() : dia == null ? null : dia.getQuote());
            pair.setBpContext(sys != null ? sys.getBpContext() : dia == null ? null : dia.getBpContext());
            rest.add(0, pair);
        }
        return rest;
    }

    private static Built build(ChatExtractLlmPayload.Item item, WhenContext when) {
        if (item == null || item.getCode() == null || item.getCode().isBlank()) {
            return Built.ignore("NOT_IN_CATALOG");
        }
        String code = item.getCode().trim();
        if (code.startsWith("basic.")) {
            code = code.substring("basic.".length());
        }
        Quote quote = clipQuote(item.getQuote());
        if (quote.text.isBlank()) {
            return Built.ignore("NOT_IN_CATALOG");
        }
        if ("BLOOD_PRESSURE".equals(code) || "BLOOD_PRESSURE_SYS".equals(code)) {
            return vitalPressure(item, quote, when);
        }
        if (ChatExtractCatalog.VITALS.containsKey(code)) {
            return vitalSingle(code, item, quote, when);
        }
        ChatExtractCatalog.ArchiveField field = ChatExtractCatalog.ARCHIVE.get(code);
        if (field == null) {
            return Built.ignore(code.endsWith(".note") || "note".equals(code) ? "NOTE_FIELD" : "NOT_IN_CATALOG");
        }
        if (!isHabit(item, quote.text)) {
            return Built.ignore("NOT_HABIT");
        }
        return archive(field, code, item, quote, when.today);
    }

    private static Built vitalPressure(ChatExtractLlmPayload.Item item, Quote quote, WhenContext when) {
        BigDecimal sys = firstNumber(item.getSystolic(), null);
        BigDecimal dia = firstNumber(item.getDiastolic(), null);
        Matcher pair = BP_PAIR.matcher(quote.text);
        if ((sys == null || dia == null) && pair.find()) {
            sys = new BigDecimal(pair.group(1));
            dia = new BigDecimal(pair.group(2));
        }
        if (sys == null || dia == null) {
            return Built.ignore("INCOMPLETE_BP");
        }
        ChatExtractRow row = baseVital("BLOOD_PRESSURE", "血压", quote);
        row.setSystolic(sys);
        row.setDiastolic(dia);
        row.setUnit("mmHg");
        row.setBpContext(bpContext(quote.text));
        applyWhen(row, quote.text, "血压", when);
        if (!VitalValueRules.isPlausible("BLOOD_PRESSURE_SYS", sys)
                || !VitalValueRules.isPlausible("BLOOD_PRESSURE_DIA", dia)) {
            block(row, "OUT_OF_RANGE", "超出合理范围");
        } else if (sys.compareTo(dia) <= 0) {
            block(row, "BP_ORDER", "收缩压不高于舒张压，请改数字");
        }
        finishVital(row);
        return Built.row(row);
    }

    private static Built vitalSingle(String code, ChatExtractLlmPayload.Item item, Quote quote, WhenContext when) {
        ChatExtractCatalog.VitalField field = ChatExtractCatalog.VITALS.get(code);
        ChatExtractRow row = baseVital(code, field.label(), quote);
        BigDecimal value = decimal(item.getValue());
        if ("WEIGHT".equals(code)) {
            applyWeight(row, quote.text, value, item.getSourceValue(), firstText(item.getSourceUnit(), item.getUnit()));
        } else {
            row.setValue(value);
            row.setUnit(field.unit());
        }
        if ("BLOOD_GLUCOSE".equals(code)) {
            String scene = mealContext(quote.text);
            if (scene == null) {
                scene = mealContext(when.around(when.anchor(quote.text, "血糖")));
            }
            row.setMealContext(scene);
            row.setUnit("mmol/L");
        }
        applyWhen(row, quote.text, field.label(), when);
        if (row.getValue() == null) {
            return Built.ignore("NOT_IN_CATALOG");
        }
        if ("WEIGHT".equals(code) && row.getUnit() == null) {
            block(row, "MISSING_UNIT", "请选择体重单位");
        } else if ("BLOOD_GLUCOSE".equals(code) && row.getMealContext() == null) {
            block(row, "MISSING_SCENE", "请选择空腹、餐后或随机");
        } else if (!VitalValueRules.isPlausible(code, row.getValue())) {
            block(row, "OUT_OF_RANGE", "超出合理范围");
        }
        finishVital(row);
        return Built.row(row);
    }

    private static Built archive(
            ChatExtractCatalog.ArchiveField field,
            String code,
            ChatExtractLlmPayload.Item item,
            Quote quote,
            LocalDate today) {
        String textValue = text(item.getValue());
        ChatExtractRow row = new ChatExtractRow();
        row.setTarget("ARCHIVE");
        row.setCode(code);
        row.setLabel(field.label());
        row.setQuote(quote.text);
        row.setQuoteTruncated(quote.truncated);
        row.setTextField(field.text());
        row.setOptions(field.options());
        if ("lifestyle.smoking.quitYear".equals(code)) {
            applyQuitYear(row, quote.text, textValue, today);
            if (row.getArchiveValue() == null || row.getArchiveValue().isBlank()) {
                return Built.ignore("NOT_IN_CATALOG");
            }
        } else if (field.text()) {
            String text = textValue.isBlank() ? "" : textValue.trim();
            if (text.length() > MAX_TEXT) {
                return Built.ignore("TOO_LONG");
            }
            if (text.isBlank()) {
                return Built.ignore("NOT_IN_CATALOG");
            }
            row.setArchiveValue(text);
        } else if (field.options() != null && !field.options().isEmpty()) {
            String codeValue = canonicalOption(field, textValue);
            if (codeValue == null) {
                return Built.ignore("NOT_IN_CATALOG");
            }
            row.setArchiveValue(codeValue);
        } else {
            BigDecimal number = decimal(item.getValue());
            if (number == null) {
                return Built.ignore("NOT_IN_CATALOG");
            }
            if (field.min() != null && number.doubleValue() < field.min()) {
                block(row, "OUT_OF_RANGE", "超出合理范围");
            } else if (field.max() != null && number.doubleValue() > field.max()) {
                block(row, "OUT_OF_RANGE", "超出合理范围");
            }
            row.setArchiveValue(number.stripTrailingZeros().toPlainString());
        }
        if (Boolean.TRUE.equals(item.getDerived())) {
            row.setDerived(true);
        }
        if (row.getStatus() == null) {
            ready(row);
        }
        return Built.row(row);
    }

    private static void applyQuitYear(ChatExtractRow row, String quote, String modelValue, LocalDate today) {
        Matcher years = QUIT_YEARS.matcher(quote);
        if (years.find()) {
            int n = yearsToken(years.group(1));
            if (n > 0) {
                row.setArchiveValue(String.valueOf(today.getYear() - n));
                row.setDerived(true);
                return;
            }
        }
        Matcher year = QUIT_YEAR.matcher(quote);
        if (year.find() && (quote.contains("戒") )) {
            row.setArchiveValue(year.group(1));
            return;
        }
        if (modelValue != null && modelValue.matches("(19|20)\\d{2}")) {
            row.setArchiveValue(modelValue);
            return;
        }
        row.setArchiveValue("");
    }

    private static int yearsToken(String token) {
        if (token.matches("\\d+")) {
            return Integer.parseInt(token);
        }
        return switch (token) {
            case "一" -> 1;
            case "两", "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            default -> -1;
        };
    }

    private static void applyWeight(
            ChatExtractRow row, String quote, BigDecimal value, BigDecimal sourceValue, String sourceUnit) {
        boolean kg = quote.contains("公斤") || quote.contains("千克") || quote.toLowerCase(Locale.ROOT).contains("kg");
        boolean jin = (quote.contains("斤") && !kg) || "斤".equals(sourceUnit);
        if (jin && !kg) {
            BigDecimal jinAmount = jinAmount(quote);
            if (jinAmount == null) {
                jinAmount = sourceValue != null ? sourceValue : value;
            }
            if (jinAmount == null) {
                row.setValue(value);
                row.setUnit(null);
                return;
            }
            BigDecimal kgValue = jinAmount.divide(new BigDecimal("2"), 1, RoundingMode.HALF_UP);
            row.setValue(kgValue);
            row.setSourceValue(jinAmount);
            row.setSourceUnit("斤");
            row.setUnit("kg");
            return;
        }
        if (kg && value != null) {
            row.setValue(value);
            row.setUnit("kg");
            return;
        }
        row.setValue(value);
        row.setUnit(null);
    }

    private static BigDecimal jinAmount(String quote) {
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*斤").matcher(quote);
        if (!matcher.find()) {
            return null;
        }
        return new BigDecimal(matcher.group(1));
    }

    private static void applyWhen(ChatExtractRow row, String quote, String keyword, WhenContext when) {
        int at = when.anchor(quote, keyword);
        LocalDate date = at >= 0 ? when.dateAt(at) : null;
        LocalTime time = at >= 0 ? when.timeAt(at) : null;
        String scope = tailAfterVague(quote);
        if (date == null) {
            date = dateOf(scope, when.today);
        }
        if (time == null) {
            time = timeOf(scope);
        }
        row.setMeasuredAt(null);
        if (date != null && time != null) {
            row.setMeasuredAt(date + "T" + time.withSecond(0).withNano(0).toString().substring(0, 5));
        } else if (date != null) {
            row.setMeasuredAt(date.toString());
            block(row, "MISSING_TIME", "请补上时刻");
        } else if (time != null) {
            block(row, "MISSING_DATE", "请补上日期");
        } else {
            block(row, "MISSING_DATE", "请补上日期和时刻");
        }
    }

    private static String tailAfterVague(String quote) {
        if (quote == null || quote.isEmpty()) {
            return "";
        }
        int at = -1;
        for (String word : VAGUE_PERIODS) {
            int found = quote.lastIndexOf(word);
            if (found > at) {
                at = found;
            }
        }
        return at >= 0 ? quote.substring(at) : quote;
    }

    static LocalDate dateOf(String quote, LocalDate today) {
        if (quote.contains("前天")) {
            return today.minusDays(2);
        }
        if (quote.contains("昨天") || quote.contains("昨日")) {
            return today.minusDays(1);
        }
        if (quote.contains("今天") || quote.contains("今日") || quote.contains("今早") || quote.contains("今晨")) {
            return today;
        }
        Matcher ymd = DATE_YMD.matcher(quote);
        if (ymd.find()) {
            return LocalDate.of(
                    Integer.parseInt(ymd.group(1)), Integer.parseInt(ymd.group(2)), Integer.parseInt(ymd.group(3)));
        }
        Matcher md = DATE_MD.matcher(quote);
        if (md.find()) {
            return LocalDate.of(today.getYear(), Integer.parseInt(md.group(1)), Integer.parseInt(md.group(2)));
        }
        return null;
    }

    static LocalTime timeOf(String quote) {
        if (quote.contains("七八点") || quote.contains("八九点") || quote.contains("几点")) {
            return null;
        }
        Matcher clock = CLOCK.matcher(quote);
        if (clock.find()) {
            return at(clock.group(1), clock.group(2));
        }
        Matcher half = CLOCK_HALF.matcher(quote);
        if (half.find()) {
            return at(half.group(1), "30");
        }
        Matcher minute = CLOCK_MINUTE.matcher(quote);
        if (minute.find()) {
            return at(minute.group(1), minute.group(2));
        }
        Matcher hour = CLOCK_HOUR.matcher(quote);
        if (hour.find()) {
            return at(hour.group(1), "0");
        }
        return null;
    }

    private static LocalTime at(String hour, String minute) {
        int h = Integer.parseInt(hour);
        int m = Integer.parseInt(minute);
        if (h < 0 || h > 23 || m < 0 || m > 59) {
            return null;
        }
        return LocalTime.of(h, m);
    }

    private static void finishVital(ChatExtractRow row) {
        if (row.getStatus() != null) {
            return;
        }
        if (row.getMeasuredAt() == null || !row.getMeasuredAt().contains("T")) {
            if ("MISSING_DATE".equals(row.getStatus()) || "MISSING_TIME".equals(row.getStatus())) {
                return;
            }
        }
        ready(row);
    }

    private static boolean isHabit(ChatExtractLlmPayload.Item item, String quote) {
        if (!Boolean.TRUE.equals(item.getHabit())) {
            return false;
        }
        if (ONE_OFF.matcher(quote).find() && !HABIT.matcher(quote).find()) {
            return false;
        }
        return true;
    }

    private static String mealContext(String quote) {
        int fasting = quote.indexOf("空腹");
        int after = indexOfAny(quote, "餐后", "饭后");
        int random = quote.indexOf("随机");
        int first = firstIndex(fasting, after, random);
        if (first < 0) {
            return null;
        }
        if (first == fasting) {
            return "FASTING";
        }
        if (first == after) {
            return "POSTPRANDIAL";
        }
        return "RANDOM";
    }

    private static String bpContext(String quote) {
        if (quote.contains("医院") || quote.contains("诊室") || quote.contains("体检") || quote.contains("门诊")) {
            return "CLINIC";
        }
        return "HOME";
    }

    private static String canonicalOption(ChatExtractCatalog.ArchiveField field, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String token = raw.trim();
        for (ChatExtractOption option : field.options()) {
            if (option.getValue().equalsIgnoreCase(token) || option.getLabel().equals(token)) {
                return option.getValue();
            }
        }
        return ChatExtractCatalog.alias(field.code(), token);
    }

    private static String textValue(ChatExtractLlmPayload.Item item) {
        String fromValue = text(item.getValue());
        if (!fromValue.isBlank()) {
            return fromValue;
        }
        if (item.getUnit() != null && looksLikeText(item.getUnit())) {
            return item.getUnit();
        }
        return "";
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isTextual()) {
            try {
                return new BigDecimal(node.asText().trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isNumber()) {
            return node.decimalValue().stripTrailingZeros().toPlainString();
        }
        return node.asText("").trim();
    }

    private static boolean looksLikeText(String unit) {
        return unit.chars().anyMatch(ch -> ch > 127);
    }

    private static void markArchiveConflicts(List<ChatExtractRow> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ChatExtractRow row : rows) {
            if (!"ARCHIVE".equals(row.getTarget())) {
                continue;
            }
            counts.merge(row.getCode(), 1, Integer::sum);
        }
        for (ChatExtractRow row : rows) {
            if ("ARCHIVE".equals(row.getTarget()) && counts.getOrDefault(row.getCode(), 0) > 1) {
                block(row, "ARCHIVE_DUP", "同一字段有多行，请只留一行");
            }
        }
    }

    private static ChatExtractRow baseVital(String code, String label, Quote quote) {
        ChatExtractRow row = new ChatExtractRow();
        row.setTarget("VITAL");
        row.setCode(code);
        row.setLabel(label);
        row.setQuote(quote.text);
        row.setQuoteTruncated(quote.truncated);
        return row;
    }

    private static void ready(ChatExtractRow row) {
        row.setStatus("READY");
        row.setStatusText("可入库");
    }

    private static void block(ChatExtractRow row, String status, String text) {
        if (row.getStatus() != null && !"READY".equals(row.getStatus())) {
            return;
        }
        row.setStatus(status);
        row.setStatusText(text);
    }

    private static void addIgnored(ChatExtractCard card, String quote, String reason) {
        if (quote == null || quote.isBlank() || card.getIgnored().size() >= 30) {
            return;
        }
        String text = quote.trim();
        if (text.length() > MAX_QUOTE) {
            text = text.substring(0, MAX_QUOTE);
        }
        String why = reason == null || reason.isBlank() ? "NOT_IN_CATALOG" : reason.trim();
        card.getIgnored().add(new ChatExtractIgnored(text, why));
    }

    private static Quote clipQuote(String quote) {
        String text = quote == null ? "" : quote.trim().replace('\n', ' ');
        if (text.length() <= MAX_QUOTE) {
            return new Quote(text, false);
        }
        return new Quote(text.substring(0, MAX_QUOTE), true);
    }

    private static BigDecimal firstNumber(BigDecimal a, BigDecimal b) {
        return a != null ? a : b;
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

    private static int firstIndex(int... indexes) {
        int best = -1;
        for (int i : indexes) {
            if (i >= 0 && (best < 0 || i < best)) {
                best = i;
            }
        }
        return best;
    }

    /** 整段话里的日期和钟点。后面的指标沿用前面已经说清的今天、7点。上周、最近、前几天会把这份沿用打断。 */
    private static final class WhenContext {
        private final String source;
        private final LocalDate today;
        private final List<Hit<LocalDate>> dates = new ArrayList<>();
        private final List<Hit<LocalTime>> clocks = new ArrayList<>();
        private final List<Integer> vagueStarts = new ArrayList<>();

        private WhenContext(String source, LocalDate today) {
            this.source = source;
            this.today = today;
        }

        static WhenContext of(String source, LocalDate today) {
            WhenContext context = new WhenContext(source, today);
            context.scanDates();
            context.scanVaguePeriods();
            context.scanClocks();
            return context;
        }

        int anchor(String quote, String keyword) {
            if (quote == null || quote.isBlank() || source.isEmpty()) {
                return -1;
            }
            int[] span = locateIgnoringGaps(source, quote);
            if (span == null) {
                return -1;
            }
            if (keyword != null && !keyword.isBlank()) {
                int[] word = locateIgnoringGaps(source.substring(span[0], span[1] + 1), keyword);
                if (word != null) {
                    return span[0] + word[0];
                }
            }
            return span[1];
        }

        /** 原句里的换行、空格和原文对不上时，仍按同一句话定位。返回原文里的起止下标。 */
        private static int[] locateIgnoringGaps(String text, String needle) {
            String compactNeedle = stripGaps(needle);
            if (compactNeedle.isEmpty()) {
                return null;
            }
            StringBuilder compact = new StringBuilder();
            int[] toSource = new int[text.length()];
            int size = 0;
            for (int i = 0; i < text.length(); i++) {
                if (isGap(text.charAt(i))) {
                    continue;
                }
                toSource[size] = i;
                compact.append(text.charAt(i));
                size++;
            }
            int at = compact.indexOf(compactNeedle);
            if (at < 0) {
                return null;
            }
            return new int[] {toSource[at], toSource[at + compactNeedle.length() - 1]};
        }

        private static String stripGaps(String text) {
            StringBuilder out = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                if (!isGap(text.charAt(i))) {
                    out.append(text.charAt(i));
                }
            }
            return out.toString();
        }

        private static boolean isGap(char c) {
            return Character.isWhitespace(c) || c == '\u3000' || c == '\u00A0';
        }

        String around(int pos) {
            if (pos < 0 || source.isEmpty()) {
                return "";
            }
            int from = Math.max(0, pos - 16);
            int to = Math.min(source.length(), pos + 8);
            return source.substring(from, to);
        }

        LocalDate dateAt(int pos) {
            int dateStart = -1;
            LocalDate found = null;
            for (Hit<LocalDate> hit : dates) {
                if (hit.start <= pos) {
                    found = hit.value;
                    dateStart = hit.start;
                }
            }
            if (vagueBetween(dateStart, pos)) {
                return null;
            }
            return found;
        }

        LocalTime timeAt(int pos) {
            int floor = -1;
            for (Hit<LocalDate> hit : dates) {
                if (hit.start <= pos) {
                    floor = hit.start;
                }
            }
            for (int start : vagueStarts) {
                if (start <= pos && start > floor) {
                    floor = start;
                }
            }
            LocalTime found = null;
            for (Hit<LocalTime> hit : clocks) {
                if (hit.start <= pos && hit.start >= floor) {
                    found = hit.value;
                }
            }
            return found;
        }

        private boolean vagueBetween(int after, int pos) {
            for (int start : vagueStarts) {
                if (start > after && start <= pos) {
                    return true;
                }
            }
            return false;
        }

        private void scanVaguePeriods() {
            for (String word : VAGUE_PERIODS) {
                int from = 0;
                while (from < source.length()) {
                    int at = source.indexOf(word, from);
                    if (at < 0) {
                        break;
                    }
                    vagueStarts.add(at);
                    from = at + word.length();
                }
            }
        }

        private void scanDates() {
            String[] words = {"前天", "昨天", "昨日", "今天", "今日", "今早", "今晨"};
            boolean[] used = new boolean[Math.max(source.length(), 1)];
            for (String word : words) {
                int from = 0;
                while (from < source.length()) {
                    int at = source.indexOf(word, from);
                    if (at < 0) {
                        break;
                    }
                    if (!used[at]) {
                        LocalDate date = dateOf(word, today);
                        if (date != null) {
                            dates.add(new Hit<>(at, date));
                            for (int i = at; i < at + word.length() && i < used.length; i++) {
                                used[i] = true;
                            }
                        }
                    }
                    from = at + word.length();
                }
            }
            Matcher ymd = DATE_YMD.matcher(source);
            while (ymd.find()) {
                if (!used[ymd.start()]) {
                    dates.add(new Hit<>(ymd.start(), dateOf(ymd.group(), today)));
                }
            }
            Matcher md = DATE_MD.matcher(source);
            while (md.find()) {
                if (!used[md.start()]) {
                    dates.add(new Hit<>(md.start(), dateOf(md.group(), today)));
                }
            }
            dates.sort((a, b) -> Integer.compare(a.start, b.start));
        }

        private void scanClocks() {
            for (int i = 0; i < source.length(); ) {
                if (vagueClock(source, i)) {
                    i++;
                    continue;
                }
                ClockHit hit = clockAt(source, i);
                if (hit == null) {
                    i++;
                    continue;
                }
                clocks.add(new Hit<>(i, hit.time));
                i = hit.end;
            }
        }

        private static boolean vagueClock(String text, int at) {
            return text.startsWith("七八点", at) || text.startsWith("八九点", at) || text.startsWith("几点", at);
        }

        private static ClockHit clockAt(String text, int at) {
            Matcher half = CLOCK_HALF.matcher(text);
            if (half.find(at) && half.start() == at) {
                LocalTime time = at(half.group(1), "30");
                return time == null ? null : new ClockHit(half.end(), time);
            }
            Matcher minute = CLOCK_MINUTE.matcher(text);
            if (minute.find(at) && minute.start() == at) {
                LocalTime time = at(minute.group(1), minute.group(2));
                return time == null ? null : new ClockHit(minute.end(), time);
            }
            Matcher clock = CLOCK.matcher(text);
            if (clock.find(at) && clock.start() == at) {
                LocalTime time = at(clock.group(1), clock.group(2));
                return time == null ? null : new ClockHit(clock.end(), time);
            }
            Matcher hour = CLOCK_HOUR.matcher(text);
            if (hour.find(at) && hour.start() == at) {
                LocalTime time = at(hour.group(1), "0");
                return time == null ? null : new ClockHit(hour.end(), time);
            }
            return null;
        }
    }

    private record Hit<T>(int start, T value) {}

    private record ClockHit(int end, LocalTime time) {}

    private static String firstText(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback == null ? null : fallback.trim();
    }

    private record Quote(String text, boolean truncated) {}

    private static final class Built {
        private final ChatExtractRow row;
        private final String ignoredReason;

        private Built(ChatExtractRow row, String ignoredReason) {
            this.row = row;
            this.ignoredReason = ignoredReason;
        }

        static Built row(ChatExtractRow row) {
            return new Built(row, null);
        }

        static Built ignore(String reason) {
            return new Built(null, reason);
        }
    }
}
