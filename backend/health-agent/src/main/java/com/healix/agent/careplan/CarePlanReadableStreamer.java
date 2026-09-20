package com.healix.agent.careplan;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从方案 JSON 流中提取可读内容，按「运动 → 饮食 → 执行 → 总结」分节增量推送。
 * 格式保证前缀稳定：未闭合字段不加换行、不追加后续项，避免逐字换行错乱。
 */
final class CarePlanReadableStreamer {

    private static final Pattern OBJECT_KEY = Pattern.compile("\"(exercise|diet|execution)\"\\s*:\\s*\\{");

    private final Consumer<com.healix.agent.stream.AgentStreamEvent> sink;
    private final StringBuilder raw = new StringBuilder();

    private final Section exercise = new Section("运动方案\n");
    private final Section diet = new Section("饮食方案\n");
    private final Section execution = new Section("执行计划\n");
    private final Section summary = new Section("方案总结\n");
    private final Section goal = new Section("阶段目标\n");

    CarePlanReadableStreamer(Consumer<com.healix.agent.stream.AgentStreamEvent> sink) {
        this.sink = sink;
    }

    void onToken(String token) {
        if (token == null || token.isEmpty() || sink == null) {
            return;
        }
        raw.append(token);
        String s = raw.toString();

        ObjectSlice ex = findObject(s, "exercise");
        if (ex != null) {
            pump(exercise, formatExercise(s, ex));
        }
        ObjectSlice di = findObject(s, "diet");
        if (di != null) {
            pump(diet, formatDiet(s, di));
        }
        ObjectSlice exec = findObject(s, "execution");
        if (exec != null) {
            pump(execution, formatExecution(s, exec));
        }

        PartialString summaryVal = partialJsonString(s, "summary", 0, s.length());
        if (summaryVal != null && !isBlank(summaryVal.value)) {
            pump(summary, summaryVal.value);
        }
        PartialString goalVal = partialJsonString(s, "goalSummary", 0, s.length());
        if (goalVal != null && !isBlank(goalVal.value)) {
            pump(goal, goalVal.value);
        }
    }

    /** 仅在 body 以已推送内容为前缀时发增量，杜绝错位换行。 */
    private void pump(Section section, String body) {
        if (body == null || body.isEmpty() || sink == null) {
            return;
        }
        if (!body.startsWith(section.body)) {
            return;
        }
        if (body.length() == section.body.length()) {
            return;
        }
        if (!section.headerSent) {
            String prefix = anyHeaderSent() ? "\n\n" : "";
            CarePlanStreamSink.token(sink, prefix + section.header);
            section.headerSent = true;
        }
        CarePlanStreamSink.token(sink, body.substring(section.body.length()));
        section.body = body;
    }

    private boolean anyHeaderSent() {
        return exercise.headerSent
                || diet.headerSent
                || execution.headerSent
                || summary.headerSent
                || goal.headerSent;
    }

    private static String formatExercise(String s, ObjectSlice slice) {
        StringBuilder out = new StringBuilder();
        PartialString g = partialJsonString(s, "goal", slice.start, slice.end);
        if (g == null || isBlank(g.value)) {
            return out.toString();
        }
        out.append("目标：").append(g.value);
        if (!g.closed) {
            return out.toString();
        }
        out.append('\n');
        if (!slice.closed) {
            // 对象未闭合前不追加会变化的统计行，保持前缀稳定
            return out.toString();
        }
        int weekDays = countKey(s, "\"day\"", slice.start, slice.end);
        if (weekDays > 0) {
            out.append("周计划：").append(weekDays).append(" 天安排\n");
        }
        appendBullets(out, "禁忌", partialStringArray(s, "contraindications", slice.start, slice.end));
        appendBullets(out, "注意", partialStringArray(s, "precautions", slice.start, slice.end));
        PartialString review = partialJsonString(s, "reviewHint", slice.start, slice.end);
        if (review != null && !isBlank(review.value) && review.closed) {
            out.append("复评：").append(review.value).append('\n');
        }
        return out.toString();
    }

    private static String formatDiet(String s, ObjectSlice slice) {
        StringBuilder out = new StringBuilder();
        PartialString calorie = partialJsonString(s, "calorieHint", slice.start, slice.end);
        if (calorie != null && !isBlank(calorie.value)) {
            out.append("热量建议：").append(calorie.value);
            if (!calorie.closed) {
                return out.toString();
            }
            out.append('\n');
        }
        if (!appendBullets(out, "原则", partialStringArray(s, "principles", slice.start, slice.end))) {
            return out.toString();
        }
        if (!appendBullets(out, "推荐", partialLabelArray(s, "recommended", slice.start, slice.end))) {
            return out.toString();
        }
        if (!appendBullets(out, "限制", partialLabelArray(s, "limited", slice.start, slice.end))) {
            return out.toString();
        }
        if (!appendBullets(out, "过敏规避", partialLabelArray(s, "allergensAvoid", slice.start, slice.end))) {
            return out.toString();
        }
        if (slice.closed) {
            String breakfast = nestedSampleMeal(s, slice, "breakfast");
            String lunch = nestedSampleMeal(s, slice, "lunch");
            String dinner = nestedSampleMeal(s, slice, "dinner");
            if (!isBlank(breakfast) || !isBlank(lunch) || !isBlank(dinner)) {
                out.append("示例日：\n");
                if (!isBlank(breakfast)) {
                    out.append("· 早餐：").append(breakfast).append('\n');
                }
                if (!isBlank(lunch)) {
                    out.append("· 午餐：").append(lunch).append('\n');
                }
                if (!isBlank(dinner)) {
                    out.append("· 晚餐：").append(dinner).append('\n');
                }
            }
            PartialString notes = partialJsonString(s, "notes", slice.start, slice.end);
            if (notes != null && !isBlank(notes.value) && notes.closed) {
                out.append("补充：").append(notes.value).append('\n');
            }
        }
        return out.toString();
    }

    private static String formatExecution(String s, ObjectSlice slice) {
        StringBuilder out = new StringBuilder();
        Integer days = partialInt(s, "horizonDays", slice.start, slice.end);
        if (days != null) {
            out.append("周期：").append(days).append(" 天\n");
        }
        List<String> titles = partialTaskTitles(s, slice.start, slice.end);
        if (!titles.isEmpty()) {
            out.append("打卡任务：\n");
            for (int i = 0; i < titles.size(); i++) {
                out.append(i + 1).append(". ").append(titles.get(i)).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * @return false 表示数组尚未闭合，调用方应停止追加后续字段以保持前缀稳定
     */
    private static boolean appendBullets(StringBuilder out, String label, PartialArray arr) {
        if (arr == null || arr.items.isEmpty()) {
            return arr == null || arr.closed;
        }
        out.append(label).append("：\n");
        for (String item : arr.items) {
            if (!isBlank(item)) {
                out.append("· ").append(item).append('\n');
            }
        }
        return arr.closed;
    }

    private static String nestedSampleMeal(String s, ObjectSlice diet, String meal) {
        int sample = indexOfKey(s, "sampleDay", diet.start, diet.end);
        if (sample < 0) {
            return null;
        }
        PartialString v = partialJsonString(s, meal, sample, diet.end);
        return v == null || !v.closed ? null : v.value;
    }

    private static int countKey(String s, String needle, int from, int to) {
        int count = 0;
        int idx = from;
        while (idx < to) {
            int hit = s.indexOf(needle, idx);
            if (hit < 0 || hit >= to) {
                break;
            }
            count++;
            idx = hit + needle.length();
        }
        return count;
    }

    private static List<String> partialTaskTitles(String s, int from, int to) {
        List<String> titles = new ArrayList<>();
        int tasks = indexOfKey(s, "tasks", from, to);
        if (tasks < 0) {
            return titles;
        }
        int arr = s.indexOf('[', tasks);
        if (arr < 0 || arr >= to) {
            return titles;
        }
        int i = arr + 1;
        while (i < to) {
            int obj = s.indexOf('{', i);
            if (obj < 0 || obj >= to) {
                break;
            }
            int end = matchingBrace(s, obj, to);
            if (end < 0) {
                // 当前任务对象未闭合：不输出未完成 title，保持前缀稳定
                break;
            }
            PartialString title = partialJsonString(s, "title", obj, end + 1);
            if (title != null && title.closed && !isBlank(title.value)) {
                titles.add(title.value);
            }
            i = end + 1;
        }
        return titles;
    }

    private static PartialArray partialLabelArray(String s, String field, int from, int to) {
        List<String> labels = new ArrayList<>();
        int key = indexOfKey(s, field, from, to);
        if (key < 0) {
            return new PartialArray(labels, true);
        }
        int arr = s.indexOf('[', key);
        if (arr < 0 || arr >= to) {
            return new PartialArray(labels, false);
        }
        int i = arr + 1;
        boolean closed = false;
        while (i < to) {
            while (i < to && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i >= to) {
                break;
            }
            char c = s.charAt(i);
            if (c == ']') {
                closed = true;
                break;
            }
            if (c == '{') {
                int end = matchingBrace(s, i, to);
                if (end < 0) {
                    break;
                }
                PartialString label = partialJsonString(s, "label", i, end + 1);
                if (label == null || !label.closed || isBlank(label.value)) {
                    label = partialJsonString(s, "code", i, end + 1);
                }
                if (label != null && label.closed && !isBlank(label.value)) {
                    labels.add(label.value);
                }
                i = end + 1;
                continue;
            }
            if (c == '"') {
                StringRead str = readString(s, i, to);
                if (str == null || !str.closed) {
                    break;
                }
                labels.add(str.value);
                i = str.end;
                continue;
            }
            i++;
        }
        return new PartialArray(labels, closed);
    }

    private static PartialArray partialStringArray(String s, String field, int from, int to) {
        List<String> items = new ArrayList<>();
        int key = indexOfKey(s, field, from, to);
        if (key < 0) {
            return new PartialArray(items, true);
        }
        int arr = s.indexOf('[', key);
        if (arr < 0 || arr >= to) {
            return new PartialArray(items, false);
        }
        int i = arr + 1;
        boolean closed = false;
        while (i < to) {
            while (i < to && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i >= to) {
                break;
            }
            char c = s.charAt(i);
            if (c == ']') {
                closed = true;
                break;
            }
            if (c == '"') {
                StringRead str = readString(s, i, to);
                if (str == null || !str.closed) {
                    break;
                }
                items.add(str.value);
                i = str.end;
                continue;
            }
            i++;
        }
        return new PartialArray(items, closed);
    }

    private static Integer partialInt(String s, String field, int from, int to) {
        int key = indexOfKey(s, field, from, to);
        if (key < 0) {
            return null;
        }
        int colon = s.indexOf(':', key);
        if (colon < 0 || colon >= to) {
            return null;
        }
        int i = colon + 1;
        while (i < to && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < to && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '-')) {
            i++;
        }
        if (start == i) {
            return null;
        }
        // 数字可能尚未写完（后面紧跟其它数字），仅在遇到分隔符时采纳
        if (i < to) {
            char next = s.charAt(i);
            if (Character.isDigit(next)) {
                return null;
            }
        } else {
            // 流到末尾且无分隔，可能仍在输出数字
            return null;
        }
        try {
            return Integer.parseInt(s.substring(start, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ObjectSlice findObject(String s, String field) {
        Matcher m = OBJECT_KEY.matcher(s);
        while (m.find()) {
            if (!field.equals(m.group(1))) {
                continue;
            }
            int brace = s.indexOf('{', m.start());
            if (brace < 0) {
                return null;
            }
            int end = matchingBrace(s, brace, s.length());
            if (end < 0) {
                return new ObjectSlice(brace, s.length(), false);
            }
            return new ObjectSlice(brace, end + 1, true);
        }
        int key = indexOfKey(s, field, 0, s.length());
        if (key < 0) {
            return null;
        }
        int brace = s.indexOf('{', key);
        if (brace < 0) {
            return null;
        }
        int end = matchingBrace(s, brace, s.length());
        if (end < 0) {
            return new ObjectSlice(brace, s.length(), false);
        }
        return new ObjectSlice(brace, end + 1, true);
    }

    private static int indexOfKey(String s, String field, int from, int to) {
        String needle = "\"" + field + "\"";
        int hit = s.indexOf(needle, from);
        if (hit < 0 || hit >= to) {
            return -1;
        }
        return hit;
    }

    private static int matchingBrace(String s, int openIdx, int to) {
        int depth = 0;
        boolean inStr = false;
        boolean escape = false;
        for (int i = openIdx; i < to && i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    static PartialString partialJsonString(String s, String field, int from, int to) {
        int key = indexOfKey(s, field, from, to);
        if (key < 0) {
            return null;
        }
        int colon = s.indexOf(':', key + field.length() + 2);
        if (colon < 0 || colon >= to) {
            return null;
        }
        int i = colon + 1;
        while (i < to && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        if (i >= to || s.charAt(i) != '"') {
            return null;
        }
        StringRead read = readString(s, i, to);
        if (read == null) {
            return null;
        }
        return new PartialString(read.value, read.closed);
    }

    private static StringRead readString(String s, int quoteIdx, int to) {
        if (quoteIdx >= to || s.charAt(quoteIdx) != '"') {
            return null;
        }
        int i = quoteIdx + 1;
        StringBuilder out = new StringBuilder();
        while (i < to && i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && i + 1 < to) {
                char n = s.charAt(i + 1);
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'u' -> {
                        if (i + 5 < s.length() && i + 5 < to) {
                            try {
                                out.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                                i += 6;
                                continue;
                            } catch (NumberFormatException ignored) {
                                out.append('u');
                            }
                        } else {
                            return new StringRead(out.toString(), i, false);
                        }
                    }
                    default -> out.append(n);
                }
                i += 2;
                continue;
            }
            if (c == '"') {
                return new StringRead(out.toString(), i + 1, true);
            }
            out.append(c);
            i++;
        }
        return new StringRead(out.toString(), i, false);
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    private static final class Section {
        final String header;
        boolean headerSent;
        String body = "";

        Section(String header) {
            this.header = header;
        }
    }

    private record ObjectSlice(int start, int end, boolean closed) {}

    private record StringRead(String value, int end, boolean closed) {}

    record PartialString(String value, boolean closed) {}

    private record PartialArray(List<String> items, boolean closed) {}
}
