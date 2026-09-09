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
                    "{\"batchSize\":200}"));

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
