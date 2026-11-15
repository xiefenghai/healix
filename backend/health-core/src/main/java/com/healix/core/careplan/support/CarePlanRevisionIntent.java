package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 修订意见硬意图：模型偶发「软化」时，服务端按明确忌口/取消类指令落实。
 */
public final class CarePlanRevisionIntent {

    public static final String OMIT_DEFAULT_CHECKINS = "omitDefaultCheckins";
    /** 执行任务上限（如「保留2-3项」→ 3）；normalizer 不得回填超此数。 */
    public static final String TASK_CAP = "taskCap";

    private static final Pattern TASK_RANGE = Pattern.compile(
            "(?:保留|只要|只留|精简到?|压缩到?|控制在|不超过|最多)?\\s*(\\d+)\\s*[-~～到至]\\s*(\\d+)\\s*(?:项|条|个)?");
    private static final Pattern TASK_SINGLE = Pattern.compile(
            "(?:保留|只要|只留|精简到|压缩到|控制在|不超过|最多)\\s*(\\d+)\\s*(?:项|条|个)");

    private CarePlanRevisionIntent() {}

    /** 在 LLM 结果上落实「不要打卡 / 不要运动计划 / 任务条数上限」等硬意图。 */
    public static void apply(String instruction, ObjectNode exercise, ObjectNode diet, ObjectNode execution) {
        if (!StringUtils.hasText(instruction)) {
            return;
        }
        String s = instruction.trim().toLowerCase(Locale.ROOT);
        if (wantsNoCheckins(s) && execution != null) {
            clearCheckins(execution);
        }
        if (wantsNoExercisePlan(s) && exercise != null) {
            clearExercisePlan(exercise);
        }
        Integer cap = parseTaskCap(instruction);
        if (cap != null && execution != null) {
            execution.put(TASK_CAP, cap);
            // 条数已压到很少时，禁止再补默认睡眠/心情，否则又会超限
            if (cap <= 3) {
                execution.put(OMIT_DEFAULT_CHECKINS, true);
            }
            trimTasksToCap(execution, cap);
        }
        // diet 忌口交给模型 + 过敏守卫；此处不硬删菜名以免误伤
    }

    /**
     * 草稿已取消打卡时，后续无关修订勿丢掉 omit 标记（否则 normalizer 会补回睡眠/心情）。
     * 仅当意见明确「恢复打卡」时清除标记。
     */
    public static void preserveOmitDefaultCheckins(
            JsonNode currentExecution, ObjectNode revisedExecution, String instruction) {
        if (revisedExecution == null) {
            return;
        }
        if (wantsRestoreCheckins(instruction)) {
            revisedExecution.remove(OMIT_DEFAULT_CHECKINS);
            return;
        }
        if (omitDefaultCheckins(currentExecution) || omitDefaultCheckins(revisedExecution)) {
            revisedExecution.put(OMIT_DEFAULT_CHECKINS, true);
        }
        // 任务上限同样跨轮保留，除非本次意见明确取消上限或改了新上限
        Integer freshCap = parseTaskCap(instruction);
        if (freshCap != null) {
            revisedExecution.put(TASK_CAP, freshCap);
            return;
        }
        if (wantsClearTaskCap(instruction)) {
            revisedExecution.remove(TASK_CAP);
            return;
        }
        int prev = taskCap(currentExecution);
        if (prev > 0) {
            revisedExecution.put(TASK_CAP, prev);
        }
    }

    public static boolean wantsRestoreCheckins(String instruction) {
        if (!StringUtils.hasText(instruction)) {
            return false;
        }
        String s = instruction.toLowerCase(Locale.ROOT);
        return containsAny(s, "恢复打卡", "重新开启打卡", "加回打卡", "需要打卡任务");
    }

    public static boolean wantsNoCheckins(String instruction) {
        if (!StringUtils.hasText(instruction)) {
            return false;
        }
        String s = instruction.toLowerCase(Locale.ROOT);
        return containsAny(
                s,
                "不想打卡",
                "不要打卡",
                "取消打卡",
                "无打卡",
                "不打卡",
                "去掉打卡",
                "删除打卡",
                "取消执行计划",
                "不要执行计划",
                "不想要执行计划",
                "不要执行任务",
                "清空打卡",
                "关闭打卡");
    }

    public static boolean wantsNoExercisePlan(String instruction) {
        if (!StringUtils.hasText(instruction)) {
            return false;
        }
        String s = instruction.toLowerCase(Locale.ROOT);
        return containsAny(
                s,
                "不想要有运动计划",
                "不想有运动计划",
                "不要运动计划",
                "取消运动计划",
                "不要安排运动",
                "无运动计划",
                "取消全部运动",
                "不要有运动计划",
                "去掉运动计划",
                "删除运动计划",
                "暂停运动计划");
    }

    public static boolean omitDefaultCheckins(JsonNode execution) {
        return execution != null && execution.path(OMIT_DEFAULT_CHECKINS).asBoolean(false);
    }

    /** @return 正整数上限；未指定则 0 */
    public static int taskCap(JsonNode execution) {
        if (execution == null || !execution.has(TASK_CAP)) {
            return 0;
        }
        int n = execution.path(TASK_CAP).asInt(0);
        return n > 0 ? n : 0;
    }

    /**
     * 从修订意见解析执行任务条数上限。「保留2-3项」取上限 3。
     */
    public static Integer parseTaskCap(String instruction) {
        if (!StringUtils.hasText(instruction)) {
            return null;
        }
        String s = instruction.trim();
        // 必须带执行计划/打卡/任务语境，避免误伤「保留2-3个鸡蛋」类饮食意见
        if (!mentionsExecutionTasks(s)) {
            return null;
        }
        Matcher range = TASK_RANGE.matcher(s);
        if (range.find()) {
            int a = Integer.parseInt(range.group(1));
            int b = Integer.parseInt(range.group(2));
            int cap = Math.max(a, b);
            return clampTaskCap(cap);
        }
        Matcher single = TASK_SINGLE.matcher(s);
        if (single.find()) {
            return clampTaskCap(Integer.parseInt(single.group(1)));
        }
        return null;
    }

    public static boolean wantsClearTaskCap(String instruction) {
        if (!StringUtils.hasText(instruction)) {
            return false;
        }
        String s = instruction.toLowerCase(Locale.ROOT);
        return containsAny(s, "取消任务上限", "不限制任务数", "可以多加执行任务", "恢复完整执行计划");
    }

    /** 按优先级裁到 cap；睡眠/心情优先丢掉，运动/饮食/饮水优先保留。 */
    public static void trimTasksToCap(ObjectNode execution, int cap) {
        if (execution == null || cap <= 0) {
            return;
        }
        JsonNode tasksNode = execution.get("tasks");
        if (!(tasksNode instanceof ArrayNode tasks) || tasks.size() <= cap) {
            return;
        }
        List<JsonNode> ranked = new ArrayList<>();
        tasks.forEach(ranked::add);
        ranked.sort(Comparator.comparingInt(CarePlanRevisionIntent::taskKeepPriority));
        ArrayNode next = execution.putArray("tasks");
        for (int i = 0; i < cap && i < ranked.size(); i++) {
            next.add(ranked.get(i).deepCopy());
        }
    }

    /** 越小越优先保留 */
    static int taskKeepPriority(JsonNode task) {
        if (task == null || task.isNull()) {
            return 90;
        }
        String cat = task.path("category").asText("").trim().toUpperCase(Locale.ROOT);
        String code = task.path("code").asText("").trim().toUpperCase(Locale.ROOT);
        String title = task.path("title").asText("").trim();
        if ("EXERCISE".equals(cat) || code.contains("EXERCISE") || title.contains("步行") || title.contains("运动")) {
            return 10;
        }
        if ("DIET".equals(cat) || code.contains("DIET") || title.contains("饮食") || title.contains("三餐")) {
            return 20;
        }
        if ("WATER".equals(cat) || code.contains("WATER") || title.contains("饮水")) {
            return 30;
        }
        if ("MEDICATION".equals(cat) || code.contains("MED")) {
            return 40;
        }
        if ("OTHER".equals(cat) || code.contains("WEIGH") || title.contains("称重")) {
            return 50;
        }
        if ("SLEEP".equals(cat) || CarePlanExecutionTaskNormalizer.SLEEP_LOG_CODE.equals(code)) {
            return 70;
        }
        if ("MOOD".equals(cat) || CarePlanExecutionTaskNormalizer.MOOD_LOG_CODE.equals(code)) {
            return 80;
        }
        return 60;
    }

    private static boolean mentionsExecutionTasks(String instruction) {
        String s = instruction.toLowerCase(Locale.ROOT);
        return containsAny(
                s,
                "执行计划",
                "执行任务",
                "打卡",
                "任务太多",
                "任务过多",
                "tasks",
                "清单太多",
                "条目太多",
                "项即可",
                "条即可");
    }

    private static Integer clampTaskCap(int cap) {
        if (cap < 1) {
            return 1;
        }
        if (cap > 12) {
            return 12;
        }
        return cap;
    }

    static void clearCheckins(ObjectNode execution) {
        execution.put(OMIT_DEFAULT_CHECKINS, true);
        execution.putArray("tasks");
        if (!StringUtils.hasText(execution.path("rationale").asText(null))) {
            execution.put("rationale", "按健管师意见暂不安排打卡任务");
        }
    }

    static void clearExercisePlan(ObjectNode exercise) {
        ArrayNode week = exercise.putArray("weeklyPlan");
        // 保留空周结构便于前端表格渲染，不含具体运动项
        String[] days = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};
        for (String day : days) {
            ObjectNode row = week.addObject();
            row.put("day", day);
            row.putArray("items");
        }
        String goal = exercise.path("goal").asText("").trim();
        if (!StringUtils.hasText(goal)
                || (!goal.contains("暂不") && !goal.contains("不安排") && !goal.contains("取消"))) {
            exercise.put("goal", "暂不安排正式运动计划，以日常活动与安全防护为主");
        }
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
