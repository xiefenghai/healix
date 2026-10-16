package com.healix.core.job.catalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class JobCatalog {

    public static final String MEDICATION_EXPIRE = "MEDICATION_EXPIRE";
    public static final String WORKSPACE_TASK_SCAN = "WORKSPACE_TASK_SCAN";
    public static final String HEALTH_REPORT_GENERATE = "HEALTH_REPORT_GENERATE";
    public static final String NOTIFY_REMINDER_SCAN = "NOTIFY_REMINDER_SCAN";
    public static final String FOLLOWUP_SCHEDULE_SCAN = "FOLLOWUP_SCHEDULE_SCAN";
    public static final String ADHERENCE_SNAPSHOT = "ADHERENCE_SNAPSHOT";
    public static final String REWARD_SETTLE = "REWARD_SETTLE";
    public static final String REWARD_EVENING_REMIND = "REWARD_EVENING_REMIND";
    public static final String REWARD_WEEKLY = "REWARD_WEEKLY";
    public static final String REWARD_FLAME_NUDGE = "REWARD_FLAME_NUDGE";
    public static final String MEDICATION_REMIND = "MEDICATION_REMIND";
    public static final String ASSESSMENT_RECOMPUTE = "ASSESSMENT_RECOMPUTE";
    public static final String ASSESSMENT_DAILY_SWEEP = "ASSESSMENT_DAILY_SWEEP";

    private static final List<JobCatalogSpec> SPECS = List.of(
            new JobCatalogSpec(
                    MEDICATION_EXPIRE,
                    "用药过期",
                    "将启用租户中已过结束日的在用药批量改为已停用",
                    "0 5 0 * * *",
                    "{\"batchSize\":500}"),
            new JobCatalogSpec(
                    WORKSPACE_TASK_SCAN,
                    "工作台任务扫描",
                    "扫描未入组、未出方案、连续未打卡与居家指标异常并开单",
                    "0 15 0 * * *",
                    "{\"batchSize\":200}"),
            new JobCatalogSpec(
                    HEALTH_REPORT_GENERATE,
                    "管理报告生成",
                    "按入组日错开生成周报；自然月/季末生成月报与三月报草稿及审阅任务",
                    "0 30 0 * * *",
                    "{\"enableWeek\":true,\"enableMonth\":true,\"enableQuarter\":true,\"batchSize\":200}"),
            new JobCatalogSpec(
                    NOTIFY_REMINDER_SCAN,
                    "健康待办日提醒",
                    "扫描当日方案打卡或用药未完成的患者，向已绑定 C 账号发送站内信摘要",
                    "0 0 9,20 * * *",
                    "{\"batchSize\":200}"),
            new JobCatalogSpec(
                    FOLLOWUP_SCHEDULE_SCAN,
                    "定期随访排期",
                    "按距上次定期随访（或入组）的间隔，自动生成待办定期随访与工作台任务",
                    "0 45 0 * * *",
                    "{\"batchSize\":200}"),
            new JobCatalogSpec(
                    ADHERENCE_SNAPSHOT,
                    "依从性日快照",
                    "固化前一天的机构与健管组依从性汇总，供看板趋势查询",
                    "0 50 1 * * *",
                    "{\"batchSize\":200}"),
            new JobCatalogSpec(
                    REWARD_SETTLE,
                    "激励终结算",
                    "对账前天的健康豆流水，结算火苗、保护卡、全勤日、徽章与等级，并推送成就",
                    "0 10 0 * * *",
                    "{}"),
            new JobCatalogSpec(
                    REWARD_EVENING_REMIND,
                    "火苗晚间提醒",
                    "火苗 ≥3 天且当天尚无任何打卡的患者，推送一次续火提醒",
                    "0 5 20 * * *",
                    "{}"),
            new JobCatalogSpec(
                    REWARD_WEEKLY,
                    "周挑战结算与生成",
                    "每周一结算上周周挑战并发放本周挑战；其余日仅补建本周挑战",
                    "0 20 0 * * *",
                    "{}"),
            new JobCatalogSpec(
                    REWARD_FLAME_NUDGE,
                    "火苗中断跟进",
                    "火苗中断 ≥3 天的患者，开或更新工作台打卡跟进任务（与方案未完成催办共用 PLAN_NUDGE，不重复派单）",
                    "0 25 0 * * *",
                    "{}"),
            new JobCatalogSpec(
                    MEDICATION_REMIND,
                    "服药到点提醒",
                    "每 5 分钟扫描到达提醒时间且该时段仍有药未记录的患者，推送服药提醒（到点 1 次 + 跟进 1 次）",
                    "0 */5 * * * *",
                    "{}"),
            new JobCatalogSpec(
                    ASSESSMENT_RECOMPUTE,
                    "疾病评估自动重算",
                    "每 2 分钟重算输入有变更（指标、检验、档案、用药、问卷）的患者；并处理评估问卷 48 小时提醒与 7 天过期",
                    "0 */2 * * * *",
                    "{\"batchSize\":200}"),
            new JobCatalogSpec(
                    ASSESSMENT_DAILY_SWEEP,
                    "疾病评估每日全量",
                    "凌晨对在管患者全量重算一次，处理年龄变化、数据窗口过期等不随录入变化的情况",
                    "0 40 2 * * *",
                    "{\"batchSize\":500}"));

    private final Map<String, JobCatalogSpec> byCode =
            SPECS.stream().collect(Collectors.toUnmodifiableMap(JobCatalogSpec::jobCode, Function.identity()));

    public List<JobCatalogSpec> all() {
        return SPECS;
    }

    public Optional<JobCatalogSpec> find(String jobCode) {
        return Optional.ofNullable(byCode.get(jobCode));
    }

    public boolean contains(String jobCode) {
        return byCode.containsKey(jobCode);
    }
}
