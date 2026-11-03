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
    private static final Pattern NAME_LABELED =
            Pattern.compile(
                    "(?:姓名|患者|客户|叫|建档)\\s*[:：]?\\s*([\\u4e00-\\u9fa5·]{2,8})",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_AFTER_VERB =
            Pattern.compile(
                    "(?:建档|新建|录入|登记)\\s*([\\u4e00-\\u9fa5·]{2,4})(?=\\s|$|[男女，,。/]|\\d)");
    private static final Pattern BARE_CN_NAME =
            Pattern.compile("(?<!\\d)([\\u4e00-\\u9fa5]{2,4})(?=\\s*[男女]|\\s+(?:男|女))");
    /** 「赵雨桐 13655556677」这类缺性别锚点的粘贴 */
    private static final Pattern NAME_BEFORE_MOBILE =
            Pattern.compile("([\\u4e00-\\u9fa5·]{2,4})\\s*(?=1[3-9]\\d{9})");

    private CreateArchiveParser() {}

    public static CreateArchiveDraft parse(String message) {
        String text = message == null ? "" : message.trim();
        if (!StringUtils.hasText(text)) {
            return new CreateArchiveDraft(null, null, null, null, false);
        }
        boolean noMobile = text.contains("暂无") && (text.contains("电话") || text.contains("手机"));
        String mobile = firstGroup(MOBILE, text);
        String gender = parseGender(text);
        LocalDate birthday = parseBirthday(text);
        String name = parseName(text);
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

    static LocalDate parseBirthday(String text) {
        Matcher cn = BIRTHDAY_CN.matcher(text);
        if (cn.find()) {
            return tryDate(cn.group(1), cn.group(2), cn.group(3));
        }
        Matcher iso = BIRTHDAY_ISO.matcher(text);
        if (iso.find()) {
            return tryDate(iso.group(1), iso.group(2), iso.group(3));
        }
        return null;
    }

    static String parseName(String text) {
        String labeled = firstGroup(NAME_LABELED, text);
        if (labeled != null && !isNoiseName(labeled)) {
            return labeled;
        }
        String afterVerb = firstGroup(NAME_AFTER_VERB, text);
        if (afterVerb != null && !isNoiseName(afterVerb)) {
            return afterVerb;
        }
        String bare = firstGroup(BARE_CN_NAME, text);
        if (bare != null && !isNoiseName(bare)) {
            return bare;
        }
        String beforeMobile = firstGroup(NAME_BEFORE_MOBILE, text);
        if (beforeMobile != null && !isNoiseName(beforeMobile)) {
            return beforeMobile;
        }
        return null;
    }

    private static boolean isNoiseName(String name) {
        return switch (name) {
            case "患者", "客户", "建档", "新建", "录入", "登记", "姓名", "本人", "手机", "电话", "出生", "日期" ->
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
}
