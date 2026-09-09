package com.healix.core.adherence.service;

import com.healix.common.domain.EntityMeta;
import com.healix.core.adherence.domain.AdherenceDailySnapshot;
import com.healix.core.adherence.dto.AdherenceTrendPointDto;
import com.healix.core.adherence.mapper.AdherenceDailySnapshotMapper;
import com.healix.core.adherence.service.AdherenceQueryService.SnapshotAggregate;
import com.healix.core.care.domain.CareTeam;
import com.healix.core.care.mapper.CareTeamMapper;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.org.domain.Organization;
import com.healix.core.org.mapper.OrganizationMapper;
import com.healix.core.tenant.domain.Tenant;
import com.healix.core.tenant.mapper.TenantMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 依从性日快照的写入与趋势读取。
 *
 * <p>机构整体一条 + 每个健管组一条：看板筛选健管组时趋势线要跟着切换，
 * 而事后无法从机构汇总反推分组。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdherenceSnapshotService {

    /** 机构整体行的 care_team_id 占位：唯一键不允许 NULL 参与去重 */
    public static final String ORG_SCOPE = "";
    /** 趋势查询最长天数 */
    public static final int MAX_TREND_DAYS = 180;

    private final TenantMapper tenantMapper;
    private final OrganizationMapper organizationMapper;
    private final CareTeamMapper careTeamMapper;
    private final AdherenceQueryService adherenceQueryService;
    private final AdherenceDailySnapshotMapper snapshotMapper;

    public record SnapshotCounts(int orgs, int rows, int failed) {}

    /** 全量：启用租户下所有机构。 */
    public SnapshotCounts snapshotAll(LocalDate day) {
        LocalDate target = day != null ? day : LocalDate.now(JobCronSupport.ZONE).minusDays(1);
        int orgs = 0;
        int rows = 0;
        int failed = 0;
        for (Tenant tenant : tenantMapper.listByStatus("ACTIVE")) {
            for (Organization org : organizationMapper.listByTenant(tenant.getId())) {
                orgs++;
                SnapshotCounts c = snapshotOrg(tenant.getId(), org.getId(), target);
                rows += c.rows();
                failed += c.failed();
            }
        }
        return new SnapshotCounts(orgs, rows, failed);
    }

    /**
     * 单机构：写机构整体 + 各健管组共 1+N 行。
     *
     * <p>单组失败不阻断其余组，只累计 failed 交由 Job 日志暴露。
     */
    public SnapshotCounts snapshotOrg(String tenantId, String orgId, LocalDate day) {
        LocalDate target = day != null ? day : LocalDate.now(JobCronSupport.ZONE).minusDays(1);
        int rows = 0;
        int failed = 0;

        List<String> scopes = new ArrayList<>();
        scopes.add(ORG_SCOPE);
        for (CareTeam team : careTeamMapper.listByOrg(orgId, null)) {
            if (StringUtils.hasText(team.getId())) {
                scopes.add(team.getId());
            }
        }
        for (String scope : scopes) {
            try {
                writeOne(tenantId, orgId, scope, target);
                rows++;
            } catch (RuntimeException ex) {
                failed++;
                log.warn(
                        "adherence snapshot failed org={} scope={} day={}: {}",
                        orgId,
                        scope.isEmpty() ? "ORG" : scope,
                        target,
                        ex.getMessage());
            }
        }
        return new SnapshotCounts(1, rows, failed);
    }

    private void writeOne(String tenantId, String orgId, String careTeamId, LocalDate day) {
        SnapshotAggregate agg = adherenceQueryService.aggregateForSnapshot(
                tenantId, orgId, day, careTeamId.isEmpty() ? null : careTeamId);

        AdherenceDailySnapshot row = new AdherenceDailySnapshot();
        row.setTenantId(tenantId);
        row.setOrgId(orgId);
        row.setCareTeamId(careTeamId);
        row.setSnapshotDate(day);
        row.setUniverseCount(agg.universeCount());
        row.setFollowUpCount(agg.followUpCount());
        row.setPlanIncompleteCount(agg.planIncompleteCount());
        row.setMedIncompleteCount(agg.medIncompleteCount());
        row.setStreakGe3Count(agg.streakGe3Count());
        row.setPlanDueSum(agg.planDueSum());
        row.setPlanDoneSum(agg.planDoneSum());
        row.setMedDueDoseSum(agg.medDueDoseSum());
        row.setMedTakenDoseSum(agg.medTakenDoseSum());
        row.setPlanRate(rate(agg.planDoneSum(), agg.planDueSum()));
        row.setMedRate(rate(agg.medTakenDoseSum(), agg.medDueDoseSum()));
        EntityMeta.onCreate(row);
        snapshotMapper.upsert(row);
    }

    /**
     * 趋势：默认到昨天为止——今天还没过完，实时看板已经在展示当天。
     *
     * @param days 回溯天数，封顶 {@link #MAX_TREND_DAYS}
     */
    public List<AdherenceTrendPointDto> trend(String orgId, String careTeamId, int days, LocalDate endDate) {
        int safeDays = Math.min(MAX_TREND_DAYS, Math.max(1, days));
        LocalDate to = endDate != null ? endDate : LocalDate.now(JobCronSupport.ZONE).minusDays(1);
        LocalDate from = to.minusDays(safeDays - 1L);
        String scope = StringUtils.hasText(careTeamId) ? careTeamId : ORG_SCOPE;

        List<AdherenceTrendPointDto> out = new ArrayList<>();
        for (AdherenceDailySnapshot row : snapshotMapper.listRange(orgId, scope, from, to)) {
            AdherenceTrendPointDto p = new AdherenceTrendPointDto();
            p.setDate(row.getSnapshotDate());
            p.setUniverseCount(row.getUniverseCount());
            p.setFollowUpCount(row.getFollowUpCount());
            p.setPlanIncompleteCount(row.getPlanIncompleteCount());
            p.setMedIncompleteCount(row.getMedIncompleteCount());
            p.setStreakGe3Count(row.getStreakGe3Count());
            p.setPlanRate(row.getPlanRate() == null ? null : row.getPlanRate().doubleValue());
            p.setMedRate(row.getMedRate() == null ? null : row.getMedRate().doubleValue());
            out.add(p);
        }
        return out;
    }

    private static BigDecimal rate(int numerator, int denominator) {
        if (denominator <= 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }
}
