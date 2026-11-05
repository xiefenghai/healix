package com.healix.agent.archive;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/** 从自然语言抽建档四要素；启发式优先，避免为简单口令再打一轮 LLM。 */
public final class CreateArchiveParser {

    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");
    private static final Pattern BIRTHDAY_ISO =
            Pattern.compile("(19\\d{2}|20\\d{2})[-/.](\\d{1,2})[-/.](\\d{1,2})");
    private static final Pattern BIRTHDAY_CN =
            Pattern.compile("(19\\d{2}|20\\d{2})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日?");
    /** 「1990年5月出生」无日：先记下月初，缺日由追问补全更稳妥——此处不默认 1 号 */
    private static final Pattern BIRTHDAY_CN_YM =
            Pattern.compile("(19\\d{2}|20\\d{2})\\s*年\\s*(\\d{1,2})\\s*月(?!\\s*\\d)");
    /** 「90年5月1日」「90年5月出生」 */
    private static final Pattern BIRTHDAY_CN_SHORT =
            Pattern.compile("(?<!\\d)(\\d{2})\\s*年\\s*(\\d{1,2})\\s*月(?:\\s*(\\d{1,2})\\s*日?)?");
    /**
     * 「1.姓名：李大牛」「姓名：待补充」等表单式粘贴（可多行或同一行连写）。
     * group1=标签，group2=值。
     */
    private static final Pattern LABELED_FIELD =
            Pattern.compile(
                    "(?:\\d+[\\.．、\\)]\\s*)?(姓名|患者|客户|性别|出生日期|出生|生日|手机号|手机|电话|联系电话)\\s*[:：]\\s*"
                            + "(.+?)(?=(?:\\s*\\d+[\\.．、\\)]\\s*)?(?:姓名|患者|客户|性别|出生日期|出生|生日|手机号|手机|电话|联系电话)\\s*[:：]|$)");
    private static final Pattern NAME_LABELED =
            Pattern.compile(
                    "(?:姓名|患者|客户|叫|建档)\\s*[:：]?\\s*([\\u4e00-\\u9fa5·]{2,8})",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_AFTER_VERB =
            Pattern.compile(
                    "(?:建档|新建|录入|登记)\\s*([\\u4e00-\\u9fa5·]{2,4})(?=\\s|$|[男女，,。/]|\\d)");
    /** 「给张三建个档」「为李敏建档」 */
    private static final Pattern NAME_FOR_PERSON =
            Pattern.compile("(?:给|为)\\s*([\\u4e00-\\u9fa5·]{2,4})\\s*(?:建|新建|登记)");
    private static final Pattern BARE_CN_NAME =
            Pattern.compile("(?<!\\d)([\\u4e00-\\u9fa5]{2,4})(?=\\s*[男女]|\\s+(?:男|女)|[，,]\\s*[男女])");
    /** 「赵雨桐 13655556677」这类缺性别锚点的粘贴 */
    private static final Pattern NAME_BEFORE_MOBILE =
            Pattern.compile("([\\u4e00-\\u9fa5·]{2,4})\\s*(?=1[3-9]\\d{9})");

    private CreateArchiveParser() {}

    public static CreateArchiveDraft parse(String message) {
        String text = message == null ? "" : message.trim();
        if (!StringUtils.hasText(text)) {
            return new CreateArchiveDraft(null, null, null, null, false);
        }
        LabeledFields labeled = extractLabeled(text);
        boolean noMobile = text.contains("暂无") && (text.contains("电话") || text.contains("手机"));
        String mobile = firstGroup(MOBILE, text);
        if (mobile == null && StringUtils.hasText(labeled.mobile) && !isPlaceholder(labeled.mobile)) {
            mobile = firstGroup(MOBILE, labeled.mobile);
        }
        String gender = parseGenderLabeled(labeled.gender);
        if (gender == null) {
            gender = parseGender(text);
        }
        LocalDate birthday = null;
        if (StringUtils.hasText(labeled.birthday) && !isPlaceholder(labeled.birthday)) {
            birthday = parseBirthday(labeled.birthday);
        }
        if (birthday == null) {
            birthday = parseBirthday(text);
        }
        String name = null;
        if (StringUtils.hasText(labeled.name) && !isPlaceholder(labeled.name) && !isNoiseName(labeled.name)) {
            name = labeled.name.trim();
        }
        if (name == null) {
            name = parseName(text);
        }
        return new CreateArchiveDraft(name, gender, birthday, mobile, noMobile);
    }

    /** 多轮补全：后一轮有值则覆盖前一轮。 */
    public static CreateArchiveDraft merge(CreateArchiveDraft base, CreateArchiveDraft overlay) {
        CreateArchiveDraft a = base == null ? new CreateArchiveDraft(null, null, null, null, false) : base;
        CreateArchiveDraft b =
                overlay == null ? new CreateArchiveDraft(null, null, null, null, false) : overlay;
        return new CreateArchiveDraft(
                b.hasName() ? b.name() : a.name(),
                b.hasGender() ? b.gender() : a.gender(),
                b.hasBirthday() ? b.birthday() : a.birthday(),
                b.hasMobile() ? b.mobile() : a.mobile(),
                b.noMobile() || a.noMobile());
    }

    static String parseGender(String text) {
        if (text.contains("男") && !text.contains("女")) {
            return "MALE";
        }
        if (text.contains("女") && !text.contains("男")) {
            return "FEMALE";
        }
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.contains("MALE") && !upper.contains("FEMALE")) {
            return "MALE";
        }
        if (upper.contains("FEMALE")) {
            return "FEMALE";
        }
        // 「男女」同现时看更靠近姓名的一侧；简单取先出现
        int male = text.indexOf('男');
        int female = text.indexOf('女');
        if (male >= 0 && female >= 0) {
            return male < female ? "MALE" : "FEMALE";
        }
        return null;
    }

    private static String parseGenderLabeled(String raw) {
        if (!StringUtils.hasText(raw) || isPlaceholder(raw)) {
            return null;
        }
        String v = raw.trim();
        if (v.contains("男") && !v.contains("女")) {
            return "MALE";
        }
        if (v.contains("女") && !v.contains("男")) {
            return "FEMALE";
        }
        String upper = v.toUpperCase(Locale.ROOT);
        if ("MALE".equals(upper)) {
            return "MALE";
        }
        if ("FEMALE".equals(upper)) {
            return "FEMALE";
        }
        return null;
    }

    static LocalDate parseBirthday(String text) {
        Matcher cn = BIRTHDAY_CN.matcher(text);
        if (cn.find()) {
            return tryDate(cn.group(1), cn.group(2), cn.group(3));
        }
        Matcher iso = BIRTHDAY_ISO.matcher(text);
        if (iso.find()) {
            return tryDate(iso.group(1), iso.group(2), iso.group(3));
        }
        Matcher shortCn = BIRTHDAY_CN_SHORT.matcher(text);
        if (shortCn.find()) {
            String y2 = shortCn.group(1);
            int y = Integer.parseInt(y2);
            // 00–29 → 2000+；30–99 → 1900+（健管常见出生年）
            int fullYear = y <= 29 ? 2000 + y : 1900 + y;
            String day = shortCn.group(3);
            if (StringUtils.hasText(day)) {
                return tryDate(String.valueOf(fullYear), shortCn.group(2), day);
            }
            // 仅年月：不猜日期，留给追问
            return null;
        }
        // 有「1990年5月」无日也不猜
        if (BIRTHDAY_CN_YM.matcher(text).find()) {
            return null;
        }
        return null;
    }

    static String parseName(String text) {
        String labeled = firstGroup(NAME_LABELED, text);
        if (labeled != null && !isNoiseName(labeled) && !isPlaceholder(labeled)) {
            return labeled;
        }
        String forPerson = firstGroup(NAME_FOR_PERSON, text);
        if (forPerson != null && !isNoiseName(forPerson) && !isPlaceholder(forPerson)) {
            return forPerson;
        }
        String afterVerb = firstGroup(NAME_AFTER_VERB, text);
        if (afterVerb != null && !isNoiseName(afterVerb) && !isPlaceholder(afterVerb)) {
            return afterVerb;
        }
        String bare = firstGroup(BARE_CN_NAME, text);
        if (bare != null && !isNoiseName(bare) && !isPlaceholder(bare)) {
            return bare;
        }
        String beforeMobile = firstGroup(NAME_BEFORE_MOBILE, text);
        if (beforeMobile != null && !isNoiseName(beforeMobile) && !isPlaceholder(beforeMobile)) {
            return beforeMobile;
        }
        return null;
    }

    private static LabeledFields extractLabeled(String text) {
        LabeledFields out = new LabeledFields();
        Matcher m = LABELED_FIELD.matcher(text);
        while (m.find()) {
            String label = m.group(1);
            String value = m.group(2) == null ? "" : m.group(2).trim();
            // 去掉行内尾注如「如李大牛」
            int tip = value.indexOf('如');
            if (tip > 0 && (value.charAt(tip - 1) == '（' || value.charAt(tip - 1) == '(')) {
                value = value.substring(0, tip - 1).trim();
            }
            switch (label) {
                case "姓名", "患者", "客户" -> {
                    if (!StringUtils.hasText(out.name)) {
                        out.name = value;
                    }
                }
                case "性别" -> {
                    if (!StringUtils.hasText(out.gender)) {
                        out.gender = value;
                    }
                }
                case "出生日期", "出生", "生日" -> {
                    if (!StringUtils.hasText(out.birthday)) {
                        out.birthday = value;
                    }
                }
                case "手机号", "手机", "电话", "联系电话" -> {
                    if (!StringUtils.hasText(out.mobile)) {
                        out.mobile = value;
                    }
                }
                default -> {
                    /* ignore */
                }
            }
        }
        return out;
    }

    private static boolean isPlaceholder(String value) {
        if (!StringUtils.hasText(value)) {
            return true;
        }
        String v = value.trim();
        return switch (v) {
            case "待补充",
                    "未补充",
                    "未填写",
                    "请填写",
                    "暂无",
                    "未知",
                    "空",
                    "-",
                    "—",
                    "－－",
                    "无",
                    "暂缺" ->
                    true;
            default -> false;
        };
    }

    private static boolean isNoiseName(String name) {
        return switch (name) {
            case "患者",
                    "客户",
                    "建档",
                    "新建",
                    "录入",
                    "登记",
                    "姓名",
                    "本人",
                    "手机",
                    "电话",
                    "出生",
                    "日期",
                    "待补充",
                    "未填写" ->
                    true;
            default -> false;
        };
    }

    private static LocalDate tryDate(String y, String m, String d) {
        try {
            String raw = String.format("%s-%02d-%02d", y, Integer.parseInt(m), Integer.parseInt(d));
            return LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException | NumberFormatException e) {
            return null;
        }
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static final class LabeledFields {
        String name;
        String gender;
        String birthday;
        String mobile;
    }
}
