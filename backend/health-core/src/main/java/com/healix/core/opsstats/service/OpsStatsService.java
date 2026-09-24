package com.healix.core.opsstats.service;

import com.healix.common.context.RequestContext;
import com.healix.common.context.RequestContextHolder;
import com.healix.common.exception.BusinessException;
import com.healix.common.exception.UnauthorizedException;
import com.healix.core.care.mapper.CareTeamMapper;
import com.healix.core.identity.domain.StaffProfile;
import com.healix.core.identity.enums.StaffRoleEnum;
import com.healix.core.identity.mapper.StaffProfileMapper;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.opsstats.dto.OpsStatsByStaffResponseDto;
import com.healix.core.opsstats.dto.OpsStatsDayAggRow;
import com.healix.core.opsstats.dto.OpsStatsFollowupSummaryDto;
import com.healix.core.opsstats.dto.OpsStatsSelfCompareDto;
import com.healix.core.opsstats.dto.OpsStatsSeriesPointDto;
import com.healix.core.opsstats.dto.OpsStatsStaffAggRow;
import com.healix.core.opsstats.dto.OpsStatsStaffRowDto;
import com.healix.core.opsstats.dto.OpsStatsSummaryDto;
import com.healix.core.opsstats.dto.OpsStatsTaskAggRow;
import com.healix.core.opsstats.dto.OpsStatsTypeSliceDto;
import com.healix.core.opsstats.mapper.OpsStatsMapper;
import com.healix.core.worktask.catalog.WorkspaceTaskType;
import com.healix.core.workspace.service.OrgWorkspaceService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OpsStatsService {

    private static final int MAX_RANGE_DAYS = 90;
    private static final int RANK_LIMIT = 50;
    private static final String FOOTNOTE =
            "及时率按关单日、仅含有截止时间的办结；超期未结为此刻存量；取消/过期不计入办结；本组筛按办理人所属健管组。本页不作薪酬核算。";

    private final OpsStatsMapper opsStatsMapper;
    private final OrgWorkspaceService orgWorkspaceService;
    private final CareTeamMapper careTeamMapper;
    private final StaffProfileMapper staffProfileMapper;

    public OpsStatsSummaryDto summary(
            String orgId, LocalDate from, LocalDate to, String careTeamId, String staffId) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        Range range = resolveRange(from, to);
        String viewerStaffId = requireStaffId();
        boolean canRank = canViewRanking(orgId, viewerStaffId);
        String scopedStaffId = scopeStaffId(canRank, viewerStaffId, staffId);
        String team = blankToNull(careTeamId);

        LocalDateTime fromDt = range.from.atStartOfDay();
        LocalDateTime toDt = range.to.plusDays(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);

        OpsStatsTaskAggRow agg =
                opsStatsMapper.aggregateTasks(orgId, fromDt, toDt, now, team, scopedStaffId);
        if (agg == null) {
            agg = new OpsStatsTaskAggRow();
        }

        OpsStatsSummaryDto dto = new OpsStatsSummaryDto();
        dto.setFrom(range.from);
        dto.setTo(range.to);
        dto.setCareTeamId(team);
        dto.setStaffId(scopedStaffId);
        dto.setCanViewRanking(canRank);
        dto.setDoneCount(agg.getDoneCount());
        dto.setDoneWithDueCount(agg.getDoneWithDueCount());
        dto.setOnTimeCount(agg.getOnTimeCount());
        dto.setOnTimeRate(rate(agg.getOnTimeCount(), agg.getDoneWithDueCount()));
        dto.setDoneNoDueCount(agg.getDoneNoDueCount());
        dto.setLateDoneCount(agg.getLateDoneCount());
        dto.setOpenCount(agg.getOpenCount());
        dto.setOverdueOpenCount(agg.getOverdueOpenCount());
        dto.setCancelledOrExpiredCount(agg.getCancelledOrExpiredCount());
        dto.setFollowupDoneCount(
                opsStatsMapper.countFollowupDone(orgId, fromDt, toDt, team, scopedStaffId));
        dto.setReportPublishedCount(
                opsStatsMapper.countReportPublished(orgId, fromDt, toDt, team, scopedStaffId));
        dto.setSeries(toSeries(opsStatsMapper.seriesByDay(orgId, fromDt, toDt, team, scopedStaffId)));
        dto.setFootnote(FOOTNOTE);
        return dto;
    }

    public List<OpsStatsSeriesPointDto> series(
            String orgId, LocalDate from, LocalDate to, String careTeamId, String staffId) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        Range range = resolveRange(from, to);
        boolean canRank = canViewRanking(orgId, requireStaffId());
        String scopedStaffId = scopeStaffId(canRank, requireStaffId(), staffId);
        LocalDateTime fromDt = range.from.atStartOfDay();
        LocalDateTime toDt = range.to.plusDays(1).atStartOfDay();
        return toSeries(opsStatsMapper.seriesByDay(
                orgId, fromDt, toDt, blankToNull(careTeamId), scopedStaffId));
    }

    public List<OpsStatsTypeSliceDto> byType(
            String orgId, LocalDate from, LocalDate to, String careTeamId, String staffId) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        Range range = resolveRange(from, to);
        boolean canRank = canViewRanking(orgId, requireStaffId());
        String scopedStaffId = scopeStaffId(canRank, requireStaffId(), staffId);
        LocalDateTime fromDt = range.from.atStartOfDay();
        LocalDateTime toDt = range.to.plusDays(1).atStartOfDay();
        List<OpsStatsTypeSliceDto> rows =
                opsStatsMapper.doneByType(orgId, fromDt, toDt, blankToNull(careTeamId), scopedStaffId);
        for (OpsStatsTypeSliceDto row : rows) {
            row.setTaskTypeLabel(typeLabel(row.getTaskType()));
        }
        return rows;
    }

    public OpsStatsByStaffResponseDto byStaff(
            String orgId, LocalDate from, LocalDate to, String careTeamId) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        Range range = resolveRange(from, to);
        String viewerStaffId = requireStaffId();
        boolean canRank = canViewRanking(orgId, viewerStaffId);
        String team = blankToNull(careTeamId);
        LocalDateTime fromDt = range.from.atStartOfDay();
        LocalDateTime toDt = range.to.plusDays(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);

        OpsStatsByStaffResponseDto resp = new OpsStatsByStaffResponseDto();
        resp.setCanViewRanking(canRank);

        if (canRank) {
            resp.setRanking(buildRanking(orgId, fromDt, toDt, now, team));
        } else {
            resp.setSelfCompare(buildSelfCompare(orgId, viewerStaffId, fromDt, toDt, now, team));
        }
        return resp;
    }

    public OpsStatsFollowupSummaryDto followups(
            String orgId, LocalDate from, LocalDate to, String careTeamId, String staffId) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        Range range = resolveRange(from, to);
        boolean canRank = canViewRanking(orgId, requireStaffId());
        String scopedStaffId = scopeStaffId(canRank, requireStaffId(), staffId);
        LocalDateTime fromDt = range.from.atStartOfDay();
        LocalDateTime toDt = range.to.plusDays(1).atStartOfDay();
        String team = blankToNull(careTeamId);
        OpsStatsFollowupSummaryDto dto = new OpsStatsFollowupSummaryDto();
        dto.setDoneCount(opsStatsMapper.countFollowupDone(orgId, fromDt, toDt, team, scopedStaffId));
        dto.setPatientRequestCount(
                opsStatsMapper.countPatientRequestFollowup(orgId, fromDt, toDt, team, scopedStaffId));
        return dto;
    }

    private List<OpsStatsStaffRowDto> buildRanking(
            String orgId, LocalDateTime from, LocalDateTime to, LocalDateTime now, String careTeamId) {
        Map<String, OpsStatsStaffRowDto> byId = new LinkedHashMap<>();
        for (OpsStatsStaffAggRow row : opsStatsMapper.doneByStaff(orgId, from, to, careTeamId, RANK_LIMIT)) {
            OpsStatsStaffRowDto dto = ensureStaff(byId, row.getStaffId(), row.getStaffName());
            dto.setDoneCount(row.getDoneCount());
            dto.setDoneWithDueCount(row.getDoneWithDueCount());
            dto.setOnTimeCount(row.getOnTimeCount());
            dto.setOnTimeRate(rate(row.getOnTimeCount(), row.getDoneWithDueCount()));
        }
        for (OpsStatsStaffAggRow row : opsStatsMapper.overdueByStaff(orgId, now, careTeamId)) {
            OpsStatsStaffRowDto dto = ensureStaff(byId, row.getStaffId(), row.getStaffName());
            dto.setOverdueOpenCount(row.getOverdueOpenCount());
        }
        for (OpsStatsStaffAggRow row : opsStatsMapper.followupDoneByStaff(orgId, from, to, careTeamId)) {
            OpsStatsStaffRowDto dto = ensureStaff(byId, row.getStaffId(), row.getStaffName());
            dto.setFollowupDoneCount(row.getFollowupDoneCount());
        }

        long unassigned = opsStatsMapper.overdueUnassigned(orgId, now, careTeamId);
        List<OpsStatsStaffRowDto> list = new ArrayList<>(byId.values());
        list.sort((a, b) -> Long.compare(b.getDoneCount(), a.getDoneCount()));
        if (list.size() > RANK_LIMIT) {
            list = new ArrayList<>(list.subList(0, RANK_LIMIT));
        }
        if (unassigned > 0 && careTeamId == null) {
            OpsStatsStaffRowDto ua = new OpsStatsStaffRowDto();
            ua.setStaffId(null);
            ua.setStaffName("未分配");
            ua.setOverdueOpenCount(unassigned);
            list.add(ua);
        }
        return list;
    }

    private OpsStatsSelfCompareDto buildSelfCompare(
            String orgId,
            String staffId,
            LocalDateTime from,
            LocalDateTime to,
            LocalDateTime now,
            String filterTeamId) {
        OpsStatsTaskAggRow selfAgg = opsStatsMapper.aggregateTasks(orgId, from, to, now, filterTeamId, staffId);
        if (selfAgg == null) {
            selfAgg = new OpsStatsTaskAggRow();
        }
        OpsStatsStaffRowDto self = new OpsStatsStaffRowDto();
        self.setStaffId(staffId);
        StaffProfile profile = staffProfileMapper.findById(staffId);
        self.setStaffName(profile == null ? staffId : profile.getDisplayName());
        self.setDoneCount(selfAgg.getDoneCount());
        self.setDoneWithDueCount(selfAgg.getDoneWithDueCount());
        self.setOnTimeCount(selfAgg.getOnTimeCount());
        self.setOnTimeRate(rate(selfAgg.getOnTimeCount(), selfAgg.getDoneWithDueCount()));
        self.setOverdueOpenCount(selfAgg.getOverdueOpenCount());
        self.setFollowupDoneCount(
                opsStatsMapper.countFollowupDone(orgId, from, to, filterTeamId, staffId));

        List<String> peerIds;
        String avgScope;
        String avgScopeLabel;
        if (filterTeamId != null) {
            peerIds = opsStatsMapper.listStaffIdsInCareTeam(filterTeamId);
            avgScope = "CARE_TEAM";
            avgScopeLabel = "本组均值";
        } else {
            List<String> myTeams = opsStatsMapper.listCareTeamIdsForStaff(orgId, staffId);
            if (myTeams != null && !myTeams.isEmpty()) {
                Set<String> set = new HashSet<>();
                for (String tid : myTeams) {
                    set.addAll(opsStatsMapper.listStaffIdsInCareTeam(tid));
                }
                peerIds = new ArrayList<>(set);
                avgScope = "CARE_TEAM";
                avgScopeLabel = "健管组均值";
            } else {
                peerIds = List.of();
                avgScope = "ORG";
                avgScopeLabel = "机构均值（未入健管组）";
            }
        }

        OpsStatsStaffRowDto avg = averagePeers(orgId, peerIds, from, to, now, filterTeamId);

        OpsStatsSelfCompareDto cmp = new OpsStatsSelfCompareDto();
        cmp.setSelf(self);
        cmp.setGroupAvg(avg);
        cmp.setAvgScope(avgScope);
        cmp.setAvgScopeLabel(avgScopeLabel);
        return cmp;
    }

    private OpsStatsStaffRowDto averagePeers(
            String orgId,
            List<String> peerIds,
            LocalDateTime from,
            LocalDateTime to,
            LocalDateTime now,
            String careTeamId) {
        OpsStatsStaffRowDto avg = new OpsStatsStaffRowDto();
        avg.setStaffId(null);
        avg.setStaffName("均值");
        if (peerIds == null || peerIds.isEmpty()) {
            // 无组：用机构有办结的人做 pooled 均值
            List<OpsStatsStaffAggRow> doneRows =
                    opsStatsMapper.doneByStaff(orgId, from, to, careTeamId, RANK_LIMIT);
            long done = 0;
            long withDue = 0;
            long onTime = 0;
            int n = 0;
            for (OpsStatsStaffAggRow r : doneRows) {
                if (r.getDoneCount() <= 0) {
                    continue;
                }
                done += r.getDoneCount();
                withDue += r.getDoneWithDueCount();
                onTime += r.getOnTimeCount();
                n++;
            }
            if (n > 0) {
                avg.setDoneCount(Math.round((double) done / n));
                avg.setDoneWithDueCount(Math.round((double) withDue / n));
                avg.setOnTimeCount(Math.round((double) onTime / n));
                avg.setOnTimeRate(rate(onTime, withDue));
            }
            return avg;
        }

        Set<String> peers = new HashSet<>(peerIds);
        long done = 0;
        long withDue = 0;
        long onTime = 0;
        long overdue = 0;
        long followup = 0;
        int active = 0;
        Map<String, OpsStatsStaffAggRow> overdueMap = new HashMap<>();
        for (OpsStatsStaffAggRow r : opsStatsMapper.overdueByStaff(orgId, now, careTeamId)) {
            overdueMap.put(r.getStaffId(), r);
        }
        Map<String, OpsStatsStaffAggRow> fuMap = new HashMap<>();
        for (OpsStatsStaffAggRow r : opsStatsMapper.followupDoneByStaff(orgId, from, to, careTeamId)) {
            fuMap.put(r.getStaffId(), r);
        }
        for (OpsStatsStaffAggRow r : opsStatsMapper.doneByStaff(orgId, from, to, careTeamId, 500)) {
            if (!peers.contains(r.getStaffId())) {
                continue;
            }
            if (r.getDoneCount() <= 0) {
                continue;
            }
            done += r.getDoneCount();
            withDue += r.getDoneWithDueCount();
            onTime += r.getOnTimeCount();
            OpsStatsStaffAggRow o = overdueMap.get(r.getStaffId());
            if (o != null) {
                overdue += o.getOverdueOpenCount();
            }
            OpsStatsStaffAggRow f = fuMap.get(r.getStaffId());
            if (f != null) {
                followup += f.getFollowupDoneCount();
            }
            active++;
        }
        if (active == 0) {
            return avg;
        }
        avg.setDoneCount(Math.round((double) done / active));
        avg.setDoneWithDueCount(Math.round((double) withDue / active));
        avg.setOnTimeCount(Math.round((double) onTime / active));
        avg.setOnTimeRate(rate(onTime, withDue));
        avg.setOverdueOpenCount(Math.round((double) overdue / active));
        avg.setFollowupDoneCount(Math.round((double) followup / active));
        return avg;
    }

    private OpsStatsStaffRowDto ensureStaff(Map<String, OpsStatsStaffRowDto> byId, String staffId, String name) {
        String key = staffId == null ? "" : staffId;
        OpsStatsStaffRowDto dto = byId.get(key);
        if (dto == null) {
            dto = new OpsStatsStaffRowDto();
            dto.setStaffId(staffId);
            dto.setStaffName(StringUtils.hasText(name) ? name : (staffId == null ? "未分配" : staffId));
            byId.put(key, dto);
        } else if (StringUtils.hasText(name) && Objects.equals(dto.getStaffName(), staffId)) {
            dto.setStaffName(name);
        }
        return dto;
    }

    private List<OpsStatsSeriesPointDto> toSeries(List<OpsStatsDayAggRow> rows) {
        List<OpsStatsSeriesPointDto> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (OpsStatsDayAggRow row : rows) {
            OpsStatsSeriesPointDto p = new OpsStatsSeriesPointDto();
            p.setDate(row.getStatsDay() == null ? null : row.getStatsDay().toString());
            p.setDoneCount(row.getDoneCount());
            p.setDoneWithDueCount(row.getDoneWithDueCount());
            p.setOnTimeCount(row.getOnTimeCount());
            p.setOnTimeRate(rate(row.getOnTimeCount(), row.getDoneWithDueCount()));
            out.add(p);
        }
        return out;
    }

    private boolean canViewRanking(String orgId, String staffId) {
        Set<String> roles = currentRoles();
        if (roles.contains(StaffRoleEnum.TENANT_ADMIN.name())) {
            return true;
        }
        List<?> teams = careTeamMapper.listByPrimaryCareManager(orgId, staffId);
        return teams != null && !teams.isEmpty();
    }

    private String scopeStaffId(boolean canRank, String viewerStaffId, String requestedStaffId) {
        String req = blankToNull(requestedStaffId);
        if (canRank) {
            return req;
        }
        if (req != null && !req.equals(viewerStaffId)) {
            throw new BusinessException(403, "仅可查看本人统计");
        }
        return viewerStaffId;
    }

    private Range resolveRange(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(JobCronSupport.ZONE);
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (end.isBefore(start)) {
            throw new BusinessException(400, "结束日期不能早于开始日期");
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new BusinessException(400, "查询区间最长 " + MAX_RANGE_DAYS + " 天");
        }
        return new Range(start, end);
    }

    private static Double rate(long num, long den) {
        if (den <= 0) {
            return null;
        }
        return Math.round(num * 1000.0 / den) / 10.0;
    }

    private static String typeLabel(String type) {
        if (!StringUtils.hasText(type)) {
            return type;
        }
        try {
            return WorkspaceTaskType.require(type).label();
        } catch (Exception e) {
            return type;
        }
    }

    private static String blankToNull(String v) {
        return StringUtils.hasText(v) ? v.trim() : null;
    }

    private static String requireStaffId() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || ctx.getStaffId() == null) {
            throw new UnauthorizedException("缺少员工上下文");
        }
        return ctx.getStaffId();
    }

    private static Set<String> currentRoles() {
        RequestContext ctx = RequestContextHolder.get();
        return ctx == null || ctx.getRoles() == null ? Set.of() : ctx.getRoles();
    }

    private record Range(LocalDate from, LocalDate to) {}
}
