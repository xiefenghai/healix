package com.healix.core.reward.service;

import static com.healix.core.reward.service.RewardService.nz;

import com.healix.common.domain.EntityMeta;
import com.healix.core.careplan.dto.CarePlanTodayDto;
import com.healix.core.careplan.service.CarePlanCheckinService;
import com.healix.core.identity.mapper.StaffProfileMapper;
import com.healix.core.notify.catalog.NotifyEventType;
import com.healix.core.reward.catalog.RewardBadgeDef;
import com.healix.core.reward.catalog.RewardLevel;
import com.healix.core.reward.domain.RewardBadge;
import com.healix.core.reward.domain.RewardKudos;
import com.healix.core.reward.domain.RewardLedger;
import com.healix.core.reward.domain.RewardSummary;
import com.healix.core.patient.domain.PatientCareAssignment;
import com.healix.core.patient.domain.PatientOrgMembership;
import com.healix.core.patient.mapper.PatientCareAssignmentMapper;
import com.healix.core.patient.mapper.PatientOrgMembershipMapper;
import com.healix.core.reward.dto.RewardViews.Badge;
import com.healix.core.reward.dto.RewardViews.Challenge;
import com.healix.core.reward.dto.RewardViews.CockpitWeek;
import com.healix.core.reward.dto.RewardViews.Kudos;
import com.healix.core.reward.dto.RewardViews.KudosResult;
import com.healix.core.reward.dto.RewardViews.LedgerDay;
import com.healix.core.reward.dto.RewardViews.LedgerItem;
import com.healix.core.reward.dto.RewardViews.Level;
import com.healix.core.reward.dto.RewardViews.Peer;
import com.healix.core.reward.dto.RewardViews.StaffView;
import com.healix.core.reward.dto.RewardViews.Summary;
import com.healix.core.reward.dto.RewardActivityRow;
import com.healix.core.reward.mapper.RewardMapper;
import com.healix.core.reward.mapper.RewardSourceMapper;
import com.healix.core.reward.service.RewardService.RecentSync;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 激励读接口：C 端成就页、B 端患者详情、报告"本周成就"、患者列表火苗。 */
@Service
@RequiredArgsConstructor
public class RewardQueryService {

    private static final int RECENT_BADGES = 3;
    private static final int LEDGER_LIMIT = 300;
    private static final int STAFF_LEDGER_DAYS = 14;

    private final RewardService rewardService;
    private final RewardMapper rewardMapper;
    private final CarePlanCheckinService carePlanCheckinService;
    private final StaffProfileMapper staffProfileMapper;
    private final RewardNotifier notifier;
    private final RewardSourceMapper rewardSourceMapper;
    private final RewardChallengeService challengeService;
    private final PatientCareAssignmentMapper careAssignmentMapper;
    private final PatientOrgMembershipMapper membershipMapper;

    private static final int PEER_MIN_POOL = 10;
    private static final int PEER_JOIN_WINDOW_DAYS = 30;

    // ---------------------------------------------------------------- C 端

    public Summary summary(String tenantId, String peopleId) {
        if (!rewardService.tenantEnabled(tenantId)) {
            return Summary.disabled();
        }
        RewardSummary s = rewardService.ensureSummary(tenantId, peopleId);
        if (!rewardService.enabled(tenantId, s)) {
            return Summary.disabled();
        }
        return buildSummary(tenantId, peopleId, s);
    }

    private Summary buildSummary(String tenantId, String peopleId, RewardSummary s) {
        RecentSync recent = rewardService.syncRecent(tenantId, peopleId, s);
        LocalDate today = RewardService.today();
        int lifetime = nz(rewardMapper.sumPoints(peopleId, null, null));
        int week = nz(rewardMapper.sumPoints(peopleId, today.with(DayOfWeek.MONDAY), today));
        int todayPts = nz(rewardMapper.sumPoints(peopleId, today, today));

        CarePlanTodayDto plan = carePlanCheckinService.getPatientToday(tenantId, peopleId, today);
        CarePlanTodayDto yPlan = carePlanCheckinService.getPatientToday(tenantId, peopleId, today.minusDays(1));

        List<Badge> recent3 = earnedBadges(peopleId).stream().limit(RECENT_BADGES).toList();
        Challenge challenge = challengeService.current(tenantId, peopleId);
        Peer peer = peer(tenantId, peopleId, s);
        return new Summary(
                true,
                lifetime,
                week,
                todayPts,
                level(s, lifetime),
                rewardService.provisionalStreak(s, recent.activeDays()),
                Math.max(nz(s.getBestStreak()), rewardService.provisionalStreak(s, recent.activeDays())),
                nz(s.getFreezeCards()),
                recent.today().active(),
                recent.today().entries().size(),
                plan.getTotalTasks(),
                plan.getDoneTasks(),
                yPlan.getPendingTasks(),
                recent.yesterday().active(),
                recent3,
                challenge,
                peer);
    }

    private static Level level(RewardSummary s, int lifetime) {
        RewardLevel byPoints = RewardLevel.ofPoints(lifetime);
        RewardLevel lv = RewardLevel.of(Math.max(nz(s.getLevelMax()), byPoints.level()));
        RewardLevel next = lv.next();
        double progress = 1;
        if (next != null) {
            int span = next.threshold() - lv.threshold();
            progress = span <= 0 ? 1 : Math.max(0, Math.min(1, (lifetime - lv.threshold()) / (double) span));
        }
        return new Level(
                lv.level(), lv.title(), next == null ? null : next.title(),
                next == null ? null : next.threshold(), lifetime, progress);
    }

    /** 徽章墙：行为徽章全量（未得置灰）+ 已获得的结果荣誉徽章。 */
    public List<Badge> badges(String tenantId, String peopleId) {
        Map<String, RewardBadge> earned = new HashMap<>();
        List<Badge> outcome = new ArrayList<>();
        for (RewardBadge b : rewardMapper.listBadges(peopleId)) {
            earned.put(b.getBadgeCode(), b);
            if (RewardBadgeDef.KIND_OUTCOME.equals(b.getKind())) {
                outcome.add(new Badge(b.getBadgeCode(), b.getBadgeName(), "🏆", b.getKind(),
                        "管理报告中的阶段成果", true, b.getEarnedDate()));
            }
        }
        List<Badge> out = new ArrayList<>();
        for (RewardBadgeDef d : RewardBadgeDef.values()) {
            RewardBadge b = earned.get(d.name());
            if (d == RewardBadgeDef.PIONEER && b == null) {
                continue;
            }
            out.add(new Badge(d.name(), d.title(), d.icon(), RewardBadgeDef.KIND_BEHAVIOR, d.condition(),
                    b != null, b == null ? null : b.getEarnedDate()));
        }
        out.addAll(outcome);
        return out;
    }

    private List<Badge> earnedBadges(String peopleId) {
        List<Badge> out = new ArrayList<>();
        for (RewardBadge b : rewardMapper.listBadges(peopleId)) {
            String icon = RewardBadgeDef.find(b.getBadgeCode()).map(RewardBadgeDef::icon).orElse("🏆");
            out.add(new Badge(b.getBadgeCode(), b.getBadgeName(), icon, b.getKind(), null, true, b.getEarnedDate()));
        }
        return out;
    }

    public List<LedgerDay> ledger(String peopleId, int days) {
        LocalDate today = RewardService.today();
        LocalDate from = today.minusDays(Math.max(1, Math.min(days, 180)) - 1L);
        Map<LocalDate, List<LedgerItem>> byDay = new LinkedHashMap<>();
        for (RewardLedger l : rewardMapper.listLedger(peopleId, from, today, LEDGER_LIMIT)) {
            byDay.computeIfAbsent(l.getBizDate(), k -> new ArrayList<>())
                    .add(new LedgerItem(l.getBizType(), l.getTitle(), nz(l.getPoints()), l.getGmtCreated()));
        }
        List<LedgerDay> out = new ArrayList<>();
        byDay.forEach((d, items) ->
                out.add(new LedgerDay(d, items.stream().mapToInt(LedgerItem::points).sum(), items)));
        return out;
    }

    // ---------------------------------------------------------------- B 端

    public StaffView staffView(String tenantId, String peopleId, String staffId) {
        boolean tenantOn = rewardService.tenantEnabled(tenantId);
        if (!tenantOn) {
            return new StaffView(false, false, Summary.disabled(), List.of(), List.of(), List.of(), false, 0, null);
        }
        RewardSummary s = rewardService.ensureSummary(tenantId, peopleId);
        boolean patientOn = nz(s.getRewardDisabled()) == 0;
        Summary summary = patientOn ? buildSummary(tenantId, peopleId, s) : Summary.disabled();
        List<Kudos> kudos = new ArrayList<>();
        boolean sentToday = false;
        LocalDate today = RewardService.today();
        for (RewardKudos k : rewardMapper.listKudos(peopleId, 10)) {
            kudos.add(new Kudos(k.getStaffId(), staffName(k.getStaffId()), k.getMessage(), k.getGmtCreated()));
            if (k.getStaffId().equals(staffId) && today.equals(k.getKudosDate())) {
                sentToday = true;
            }
        }
        return new StaffView(
                true,
                patientOn,
                summary,
                badges(tenantId, peopleId),
                ledger(peopleId, STAFF_LEDGER_DAYS),
                kudos,
                sentToday,
                rewardSourceMapper.countBackfills(tenantId, peopleId, today.minusDays(6)),
                patientOn ? challengeService.current(tenantId, peopleId) : null);
    }

    // ---------------------------------------------------------------- 同期百分位

    public Peer peer(String tenantId, String peopleId) {
        if (!rewardService.tenantEnabled(tenantId)) {
            return new Peer(false, null, 0, false, "本周打卡天数");
        }
        RewardSummary s = rewardService.ensureSummary(tenantId, peopleId);
        return peer(tenantId, peopleId, s);
    }

    private Peer peer(String tenantId, String peopleId, RewardSummary s) {
        boolean optOut = nz(s.getPeerOptOut()) != 0;
        if (optOut || nz(s.getRewardDisabled()) != 0) {
            return new Peer(false, null, 0, optOut, "本周打卡天数");
        }
        String orgId = primaryOrgId(tenantId, peopleId);
        if (!StringUtils.hasText(orgId)) {
            return new Peer(false, null, 0, false, "本周打卡天数");
        }
        LocalDate joined = joinDate(orgId, peopleId);
        if (joined == null) {
            return new Peer(false, null, 0, false, "本周打卡天数");
        }
        List<String> peers = rewardSourceMapper.listPeerPeopleIds(
                tenantId,
                orgId,
                joined.minusDays(PEER_JOIN_WINDOW_DAYS),
                joined.plusDays(PEER_JOIN_WINDOW_DAYS),
                peopleId);
        int pool = peers.size() + 1;
        if (pool < PEER_MIN_POOL) {
            return new Peer(false, null, pool, false, "本周打卡天数");
        }
        LocalDate today = RewardService.today();
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        int mine = rewardMapper.countActiveDates(peopleId, weekStart, today);
        int beat = 0;
        for (String pid : peers) {
            if (rewardMapper.countActiveDates(pid, weekStart, today) < mine) {
                beat++;
            }
        }
        int percentile = (int) Math.round(100.0 * beat / peers.size());
        return new Peer(true, percentile, pool, false, "本周打卡天数");
    }

    public void setPeerOptOut(String tenantId, String peopleId, boolean optOut) {
        rewardService.ensureSummary(tenantId, peopleId);
        rewardMapper.updatePeerOptOut(peopleId, optOut ? 1 : 0);
    }

    private String primaryOrgId(String tenantId, String peopleId) {
        PatientCareAssignment a = careAssignmentMapper.find(tenantId, peopleId);
        if (a != null && StringUtils.hasText(a.getPrimaryOrgId())) {
            return a.getPrimaryOrgId();
        }
        List<PatientOrgMembership> ms = membershipMapper.listActiveByPeople(peopleId);
        return ms.isEmpty() ? null : ms.get(0).getOrgId();
    }

    private LocalDate joinDate(String orgId, String peopleId) {
        PatientOrgMembership m = membershipMapper.findByOrgAndPeople(orgId, peopleId);
        if (m == null) {
            return null;
        }
        if (m.getJoinedAt() != null) {
            return m.getJoinedAt().toLocalDate();
        }
        return m.getGmtCreated() == null ? null : m.getGmtCreated().toLocalDate();
    }

    // ---------------------------------------------------------------- 驾驶舱

    public CockpitWeek cockpitWeek(String tenantId, String orgId) {
        if (!rewardService.tenantEnabled(tenantId)) {
            return new CockpitWeek(0, 0, 0);
        }
        LocalDate today = RewardService.today();
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        return new CockpitWeek(
                nz(rewardMapper.sumOrgWeekPoints(tenantId, orgId, weekStart, today)),
                rewardMapper.countOrgFullDays(tenantId, orgId, weekStart, today),
                rewardMapper.countOrgActiveToday(tenantId, orgId, today));
    }

    public KudosResult kudos(String tenantId, String orgId, String peopleId, String staffId, String message) {
        RewardSummary s = rewardService.ensureSummary(tenantId, peopleId);
        if (!rewardService.enabled(tenantId, s)) {
            return new KudosResult(false, "该患者未开启激励");
        }
        String msg = StringUtils.hasText(message) ? message.trim() : null;
        if (msg != null && msg.length() > 100) {
            msg = msg.substring(0, 100);
        }
        LocalDate today = RewardService.today();
        RewardKudos row = new RewardKudos();
        row.setTenantId(tenantId);
        row.setOrgId(orgId);
        row.setPeopleId(peopleId);
        row.setStaffId(staffId);
        row.setKudosDate(today);
        row.setMessage(msg);
        EntityMeta.onCreate(row);
        rewardMapper.upsertKudos(row);

        String who = staffName(staffId);
        String body = (StringUtils.hasText(who) ? who : "你的健管师") + " 为你点了赞 👍"
                + (msg != null ? "：" + msg : "，继续保持！");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("staffId", staffId);
        payload.put("day", today.toString());
        boolean sent = notifier.publish(NotifyEventType.STAFF_KUDOS, tenantId, orgId, peopleId,
                peopleId + ":" + staffId + ":" + today, "收到一个赞", body, payload);
        return new KudosResult(sent, sent ? "已送出鼓励" : "已记录；患者尚未绑定 C 端账号，未能推送");
    }

    private String staffName(String staffId) {
        if (!StringUtils.hasText(staffId)) {
            return null;
        }
        var profile = staffProfileMapper.findById(staffId);
        return profile == null || !StringUtils.hasText(profile.getDisplayName()) ? null : profile.getDisplayName();
    }

    // ---------------------------------------------------------------- 报告 / 列表

    /** 报告"本周成就"快照；租户或患者关闭激励时返回 null（不展示）。 */
    public Map<String, Object> reportSection(String tenantId, String peopleId, LocalDate start, LocalDate end) {
        if (!rewardService.tenantEnabled(tenantId)) {
            return null;
        }
        RewardSummary s = rewardService.ensureSummary(tenantId, peopleId);
        if (!rewardService.enabled(tenantId, s)) {
            return null;
        }
        rewardService.syncDay(tenantId, peopleId, end.minusDays(1));
        rewardService.syncDay(tenantId, peopleId, end);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("points", nz(rewardMapper.sumPoints(peopleId, start, end)));
        out.put("activeDays", rewardMapper.countActiveDates(peopleId, start, end));
        out.put("fullDays", rewardMapper.countPositiveRefs(peopleId, "FULL_DAY", start, end));
        Set<LocalDate> active = new HashSet<>();
        LocalDate from = s.getLastSettledDate() == null ? end : s.getLastSettledDate().plusDays(1);
        rewardMapper.listActiveLedgerDates(List.of(peopleId), from).forEach(l -> active.add(l.getBizDate()));
        out.put("streak", rewardService.provisionalStreak(s, active));
        int lifetime = nz(rewardMapper.sumPoints(peopleId, null, null));
        out.put("levelTitle", level(s, lifetime).title());
        List<Map<String, Object>> badges = new ArrayList<>();
        for (Badge b : earnedBadges(peopleId)) {
            if (b.earnedDate() != null && !b.earnedDate().isBefore(start) && !b.earnedDate().isAfter(end)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", b.name());
                m.put("icon", b.icon());
                m.put("kind", b.kind());
                badges.add(m);
            }
        }
        out.put("newBadges", badges);
        return out;
    }

    /** 患者列表火苗（不触发同步，基于已记流水推演）；未开启的患者不返回。 */
    /**
     * 患者列表打卡概况。
     *
     * @param streak 火苗天数；未初始化或已关闭激励为 null，0 即已熄灭
     * @param todayActions 今日打卡动作数
     * @param inactiveDays 距最近一次打卡的天数；窗口内无打卡为 null
     */
    public record PatientBrief(Integer streak, int todayActions, Integer inactiveDays) {}

    public Map<String, PatientBrief> patientBriefs(String tenantId, Collection<String> peopleIds) {
        Map<String, PatientBrief> out = new HashMap<>();
        if (peopleIds == null || peopleIds.isEmpty() || !rewardService.tenantEnabled(tenantId)) {
            return out;
        }
        List<RewardSummary> rows = rewardMapper.listSummaries(peopleIds);
        if (rows.isEmpty()) {
            return out;
        }
        List<String> ids = rows.stream().map(RewardSummary::getPeopleId).toList();
        LocalDate today = RewardService.today();
        LocalDate from = today.minusDays(RewardService.MAX_CATCH_UP_DAYS);
        Map<String, Set<LocalDate>> active = new HashMap<>();
        for (RewardLedger l : rewardMapper.listActiveLedgerDates(ids, from)) {
            active.computeIfAbsent(l.getPeopleId(), k -> new HashSet<>()).add(l.getBizDate());
        }
        Map<String, Integer> todayActions = new HashMap<>();
        Map<String, LocalDate> lastActive = new HashMap<>();
        for (RewardActivityRow r : rewardSourceMapper.listRecentActivity(tenantId, ids, from)) {
            if (nz(r.getActions()) <= 0) {
                continue;
            }
            active.computeIfAbsent(r.getPeopleId(), k -> new HashSet<>()).add(r.getDay());
            lastActive.merge(r.getPeopleId(), r.getDay(), (a, b) -> a.isAfter(b) ? a : b);
            if (today.equals(r.getDay())) {
                todayActions.put(r.getPeopleId(), r.getActions());
            }
        }
        for (RewardSummary s : rows) {
            if (nz(s.getRewardDisabled()) != 0) {
                continue;
            }
            String pid = s.getPeopleId();
            LocalDate last = lastActive.get(pid);
            out.put(pid, new PatientBrief(
                    rewardService.provisionalStreak(s, active.getOrDefault(pid, Set.of())),
                    todayActions.getOrDefault(pid, 0),
                    last == null ? null : (int) ChronoUnit.DAYS.between(last, today)));
        }
        return out;
    }
}
