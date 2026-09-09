package com.healix.core.job.handler;

import com.healix.core.job.catalog.JobCatalog;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.org.domain.Organization;
import com.healix.core.org.mapper.OrganizationMapper;
import com.healix.core.report.dto.HealthReportViewDto;
import com.healix.core.report.service.HealthReportService;
import com.healix.core.report.support.HealthReportPeriodSupport;
import com.healix.core.report.support.HealthReportPeriodSupport.PeriodWindow;
import com.healix.core.tenant.domain.Tenant;
import com.healix.core.tenant.mapper.TenantMapper;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class HealthReportGenerateJobHandler implements JobHandler {

    private static final int DEFAULT_BATCH = 200;

    private final TenantMapper tenantMapper;
    private final OrganizationMapper organizationMapper;
    private final HealthReportService healthReportService;

    @Override
    public String jobCode() {
        return JobCatalog.HEALTH_REPORT_GENERATE;
    }

    @Override
    public JobResult execute(JobContext ctx) {
        boolean enableWeek = paramBool(ctx, "enableWeek", true);
        boolean enableMonth = paramBool(ctx, "enableMonth", true);
        boolean enableQuarter = paramBool(ctx, "enableQuarter", true);
        int batchSize = paramInt(ctx, "batchSize", DEFAULT_BATCH);
        LocalDate today = LocalDate.now(ctx.clock());
        PeriodWindow monthWindow = HealthReportPeriodSupport.calendarMonthJustEnded(today);
        PeriodWindow quarterWindow = HealthReportPeriodSupport.calendarQuarterJustEnded(today);
        boolean runMonth = enableMonth && monthWindow != null;
        boolean runQuarter = enableQuarter && quarterWindow != null;

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("today", today.toString());
        detail.put("timezone", JobCronSupport.ZONE.getId());
        detail.put("enableWeek", enableWeek);
        detail.put("enableMonth", enableMonth);
        detail.put("enableQuarter", enableQuarter);
        detail.put("monthWindow", monthWindow == null ? null : monthWindow.start() + "~" + monthWindow.end());
        detail.put("quarterWindow", quarterWindow == null ? null : quarterWindow.start() + "~" + quarterWindow.end());

        if (!enableWeek && !runMonth && !runQuarter) {
            detail.put("skipped", "no_trigger");
            return JobResult.of("今日无周报入组错开任务且非月/季触发日，跳过", detail);
        }

        int orgs = 0;
        int weekGen = 0;
        int monthGen = 0;
        int quarterGen = 0;
        int skipped = 0;
        for (Tenant tenant : tenantMapper.listByStatus("ACTIVE")) {
            if (ctx.timedOut()) {
                throw new IllegalStateException("任务超时");
            }
            for (Organization org : organizationMapper.listByTenant(tenant.getId())) {
                if (ctx.timedOut()) {
                    throw new IllegalStateException("任务超时");
                }
                orgs++;
                List<String> peopleIds =
                        healthReportService.listPeopleIdsByPrimaryOrg(tenant.getId(), org.getId(), batchSize);
                for (String peopleId : peopleIds) {
                    if (ctx.timedOut()) {
                        throw new IllegalStateException("任务超时");
                    }
                    String tenantId = tenant.getId();
                    String orgId = org.getId();
                    if (enableWeek) {
                        int r = generateOne(
                                () -> healthReportService.tryGenerateEnrollWeekFromJob(tenantId, orgId, peopleId),
                                tenantId,
                                orgId,
                                peopleId,
                                "WEEK");
                        if (r > 0) {
                            weekGen++;
                        } else {
                            skipped++;
                        }
                    }
                    if (runMonth) {
                        int r = generateOne(
                                () -> healthReportService.tryGenerateMonthFromJob(tenantId, orgId, peopleId),
                                tenantId,
                                orgId,
                                peopleId,
                                "MONTH");
                        if (r > 0) {
                            monthGen++;
                        } else {
                            skipped++;
                        }
                    }
                    if (runQuarter) {
                        int r = generateOne(
                                () -> healthReportService.tryGenerateQuarterFromJob(tenantId, orgId, peopleId),
                                tenantId,
                                orgId,
                                peopleId,
                                "QUARTER");
                        if (r > 0) {
                            quarterGen++;
                        } else {
                            skipped++;
                        }
                    }
                }
            }
        }
        detail.put("orgs", orgs);
        detail.put("weekGenerated", weekGen);
        detail.put("monthGenerated", monthGen);
        detail.put("quarterGenerated", quarterGen);
        detail.put("skipped", skipped);
        int total = weekGen + monthGen + quarterGen;
        return JobResult.of(
                "生成报告草稿 " + total + " 份（周" + weekGen + "/月" + monthGen + "/季" + quarterGen + "，跳过 " + skipped + "）",
                detail);
    }

    /** @return 1 生成成功，0 跳过/失败 */
    private int generateOne(
            Supplier<HealthReportViewDto> supplier,
            String tenantId,
            String orgId,
            String peopleId,
            String type) {
        try {
            HealthReportViewDto dto = supplier.get();
            return dto == null ? 0 : 1;
        } catch (Exception ex) {
            log.warn(
                    "HEALTH_REPORT_GENERATE {} failed tenant={} org={} people={}: {}",
                    type,
                    tenantId,
                    orgId,
                    peopleId,
                    ex.getMessage());
            return 0;
        }
    }

    private static boolean paramBool(JobContext ctx, String key, boolean def) {
        if (ctx.params() != null && ctx.params().has(key) && !ctx.params().get(key).isNull()) {
            return ctx.params().get(key).asBoolean(def);
        }
        return def;
    }

    private static int paramInt(JobContext ctx, String key, int def) {
        if (ctx.params() != null && ctx.params().has(key)) {
            int n = ctx.params().path(key).asInt(def);
            return n > 0 ? Math.min(n, 5000) : def;
        }
        return def;
    }
}
