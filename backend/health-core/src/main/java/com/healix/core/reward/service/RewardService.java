package com.healix.core.reward.service;

import com.healix.common.domain.EntityMeta;
import com.healix.core.careplan.dto.CarePlanTodayDto;
import com.healix.core.careplan.service.CarePlanCheckinService;
import com.healix.core.govern.enums.FeatureFlagKeyEnum;
import com.healix.core.govern.service.FeatureFlagService;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.reward.catalog.RewardBadgeDef;
import com.healix.core.reward.catalog.RewardBizType;
import com.healix.core.reward.catalog.RewardLevel;
import com.healix.core.reward.domain.RewardBadge;
import com.healix.core.reward.domain.RewardDay;
import com.healix.core.reward.domain.RewardLedger;
import com.healix.core.reward.domain.RewardSummary;
import com.healix.core.reward.mapper.RewardMapper;
import com.healix.core.reward.mapper.RewardSourceMapper;
import com.healix.core.reward.service.RewardRules.DayResult;
import com.healix.core.reward.service.RewardRules.Entry;
import com.healix.core.reward.service.RewardRules.StreakStep;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 激励核心：读时同步流水、上线初始化、D-2 终结算、徽章与等级。
 *
 * <p>流水由源表推导：{@link #syncDay} 计算当日应得并与已记净额做差，差额以新 seq 追加（正为补记、负为冲正），
 * 唯一键 (people_id, biz_type, biz_ref, seq) 保证并发重复写入只成功一次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RewardService {

    /** 任务延迟时最多补结算的天数。 */
    static final int MAX_CATCH_UP_DAYS = 30;
    private static final int INIT_LOOKBACK_DAYS = 365;
    private static final int[] STREAK_BADGES = {7, 21, 60, 100};

    private final RewardMapper rewardMapper;
    private final RewardSourceMapper sourceMapper;
    private final FeatureFlagService featureFlagService;
    private final CarePlanCheckinService carePlanCheckinService;
    private final RewardNotifier notifier;

    public static LocalDate today() {
        return LocalDate.now(JobCronSupport.ZONE);
    }

    public boolean tenantEnabled(String tenantId) {
        return featureFlagService.enabled(tenantId, FeatureFlagKeyEnum.PATIENT_REWARDS);
    }

    public boolean enabled(String tenantId, RewardSummary summary) {
        return tenantEnabled(tenantId) && (summary == null || summary.getRewardDisabled() == null
                || summary.getRewardDisabled() == 0);
    }

    // ---------------------------------------------------------------- 同步

    /** 按源表重算 {@code day} 的应得流水并补差；返回当日计分结果。 */
    public DayResult syncDay(String tenantId, String peopleId, LocalDate day) {
        DayResult result = RewardRules.evaluate(day, sourceMapper.listDayEvents(tenantId, peopleId, day));

        Map<String, Entry> expected = new HashMap<>();
        for (Entry e : result.entries()) {
            expected.put(e.bizType() + "|" + e.bizRef(), e);
        }
        Map<String, RewardLedger> recorded = new HashMap<>();
        for (RewardLedger l : rewardMapper.listLedgerNet(peopleId, day, RewardBizType.names(RewardBizType.SYNCED))) {
            recorded.put(l.getBizType() + "|" + l.getBizRef(), l);
        }
        Set<String> keys = new HashSet<>(expected.keySet());
        keys.addAll(recorded.keySet());
        for (String key : keys) {
            Entry want = expected.get(key);
            RewardLedger have = recorded.get(key);
            int net = have == null || have.getPoints() == null ? 0 : have.getPoints();
            int diff = (want == null ? 0 : want.points()) - net;
            if (diff == 0) {
                continue;
            }
            String[] parts = key.split("\\|", 2);
            String title = want != null ? want.title() : "撤销 · " + have.getTitle();
            int seq = have == null || have.getSeq() == null ? 0 : have.getSeq() + 1;
            writeLedger(tenantId, peopleId, day, parts[0], parts[1], seq, diff, title);
        }
        return result;
    }

    /** 周挑战结算发豆（不计入每日 80 上限）。 */
    public void awardChallengePoints(String tenantId, String peopleId, LocalDate day, String challengeId, String title) {
        writeLedger(
                tenantId,
                peopleId,
                day,
                RewardBizType.CHALLENGE.name(),
                challengeId,
                0,
                com.healix.core.reward.catalog.RewardChallengeKind.POINTS,
                title);
        RewardSummary s = findSummary(peopleId);
        if (s != null) {
            refreshLevel(s);
        }
    }

    public RewardSummary findSummary(String peopleId) {
        return rewardMapper.findSummary(peopleId);
    }

    private void writeLedger(
            String tenantId, String peopleId, LocalDate day, String type, String ref, int seq, int points, String title) {
        RewardLedger row = new RewardLedger();
        row.setTenantId(tenantId);
        row.setPeopleId(peopleId);
        row.setBizDate(day);
        row.setBizType(type);
        row.setBizRef(ref);
        row.setSeq(seq);
        row.setPoints(points);
        row.setTitle(title.length() > 64 ? title.substring(0, 64) : title);
        EntityMeta.onCreate(row);
        try {
            rewardMapper.insertLedger(row);
        } catch (DuplicateKeyException ignored) {
            // 并发同步已写入同一 seq
        }
    }

    // ---------------------------------------------------------------- 汇总与初始化

    /** 取汇总；首次访问按上线前真实连续天数初始化火苗并发开拓者徽章（G17，不补发历史豆）。 */
    public RewardSummary ensureSummary(String tenantId, String peopleId) {
        RewardSummary existing = rewardMapper.findSummary(peopleId);
        if (existing != null) {
            return existing;
        }
        LocalDate today = today();
        LocalDate end = today.minusDays(2);
        List<LocalDate> days = sourceMapper.listActiveDays(
                tenantId, peopleId, end.minusDays(INIT_LOOKBACK_DAYS - 1L), today);
        int streak = RewardRules.trailingStreak(days, end);

        RewardSummary row = new RewardSummary();
        row.setTenantId(tenantId);
        row.setPeopleId(peopleId);
        row.setLevelMax(1);
        row.setStreakDays(streak);
        row.setBestStreak(streak);
        row.setFreezeCards(Math.min(streak / 7, RewardRules.MAX_FREEZE_CARDS));
        row.setLastSettledDate(end);
        row.setRewardDisabled(0);
        row.setPeerOptOut(0);
        EntityMeta.onCreate(row);
        try {
            rewardMapper.insertSummary(row);
        } catch (DuplicateKeyException ex) {
            return rewardMapper.findSummary(peopleId);
        }

        if (!days.isEmpty()) {
            grantBadge(tenantId, peopleId, RewardBadgeDef.PIONEER, today, true);
        }
        for (int n : STREAK_BADGES) {
            if (streak >= n) {
                grantBadge(tenantId, peopleId, RewardBadgeDef.valueOf("STREAK_" + n), today, false);
            }
        }
        if (sourceMapper.countSelfMeasurements(tenantId, peopleId) >= 50) {
            grantBadge(tenantId, peopleId, RewardBadgeDef.VITAL_50, today, false);
        }
        if (sourceMapper.countPhotoMeals(tenantId, peopleId) >= 30) {
            grantBadge(tenantId, peopleId, RewardBadgeDef.PHOTO_30, today, false);
        }
        refreshLevel(row);
        return rewardMapper.findSummary(peopleId);
    }

    /** @param activeDays 未结算日中的打卡日（供展示火苗推演） */
    public record RecentSync(DayResult today, DayResult yesterday, Set<LocalDate> activeDays) {}

    /** 同步今天与昨天的流水。 */
    public RecentSync syncRecent(String tenantId, String peopleId, RewardSummary summary) {
        LocalDate today = today();
        DayResult y = syncDay(tenantId, peopleId, today.minusDays(1));
        DayResult t = syncDay(tenantId, peopleId, today);
        LocalDate from = summary.getLastSettledDate() == null
                ? today.minusDays(1)
                : summary.getLastSettledDate().plusDays(1);
        Set<LocalDate> active = new HashSet<>();
        for (RewardLedger l : rewardMapper.listActiveLedgerDates(List.of(peopleId), from)) {
            active.add(l.getBizDate());
        }
        return new RecentSync(t, y, active);
    }

    public int provisionalStreak(RewardSummary s, Set<LocalDate> activeDays) {
        return RewardRules.provisionalStreak(
                nz(s.getStreakDays()), nz(s.getFreezeCards()), s.getLastSettledDate(), activeDays, today());
    }

    // ---------------------------------------------------------------- 终结算

    public record SettleOutcome(int settledDays, List<String> newBadges, int freezeUsed, boolean levelUp) {
        static SettleOutcome none() {
            return new SettleOutcome(0, List.of(), 0, false);
        }
    }

    /** 终结算到 {@code until}（含）；已结算过的日期跳过。患者关闭激励时只推进结算日、火苗冻结。 */
    @Transactional
    public SettleOutcome settleUntil(String tenantId, String peopleId, LocalDate until, boolean tenantOn) {
        RewardSummary s = tenantOn ? ensureSummary(tenantId, peopleId) : rewardMapper.findSummary(peopleId);
        if (s == null) {
            return SettleOutcome.none();
        }
        LocalDate from = s.getLastSettledDate() == null ? until : s.getLastSettledDate().plusDays(1);
        if (from.isAfter(until)) {
            return SettleOutcome.none();
        }
        if (from.isBefore(until.minusDays(MAX_CATCH_UP_DAYS - 1L))) {
            from = until.minusDays(MAX_CATCH_UP_DAYS - 1L);
        }
        boolean on = tenantOn && nz(s.getRewardDisabled()) == 0;
        if (!on) {
            s.setLastSettledDate(until);
            EntityMeta.onUpdate(s);
            rewardMapper.updateSummary(s);
            return SettleOutcome.none();
        }

        int levelBefore = nz(s.getLevelMax());
        List<String> badges = new ArrayList<>();
        int freezeUsed = 0;
        int days = 0;
        for (LocalDate d = from; !d.isAfter(until); d = d.plusDays(1)) {
            freezeUsed += settleDay(s, d, badges) ? 1 : 0;
            days++;
        }
        s.setLastSettledDate(until);
        refreshLevel(s);
        EntityMeta.onUpdate(s);
        rewardMapper.updateSummary(s);
        return new SettleOutcome(days, badges, freezeUsed, nz(s.getLevelMax()) > levelBefore);
    }

    /** @return 是否消耗了保护卡 */
    private boolean settleDay(RewardSummary s, LocalDate d, List<String> badges) {
        String tenantId = s.getTenantId();
        String peopleId = s.getPeopleId();
        DayResult day = syncDay(tenantId, peopleId, d);

        CarePlanTodayDto plan = carePlanCheckinService.getPatientToday(tenantId, peopleId, d);
        int due = plan.getTotalTasks();
        // 补打计连续但不计全勤：只认当天结束前录入的打卡
        int done = due > 0
                ? sourceMapper.countPlanDoneNotStaff(tenantId, peopleId, d, d.plusDays(1).atStartOfDay())
                : 0;
        boolean fullDay = due > 0 && done >= due;

        StreakStep step = RewardRules.step(nz(s.getStreakDays()), nz(s.getFreezeCards()), day.active());
        s.setStreakDays(step.streak());
        s.setFreezeCards(step.freezeCards());
        s.setBestStreak(Math.max(nz(s.getBestStreak()), step.streak()));

        RewardDay row = new RewardDay();
        row.setTenantId(tenantId);
        row.setPeopleId(peopleId);
        row.setBizDate(d);
        row.setActive(day.active() ? 1 : 0);
        row.setFullDay(fullDay ? 1 : 0);
        row.setDueCount(due);
        row.setDoneCount(Math.min(done, due));
        row.setFreezeUsed(step.freezeUsed() ? 1 : 0);
        row.setStreakAfter(step.streak());
        EntityMeta.onCreate(row);
        rewardMapper.upsertDay(row);

        if (fullDay) {
            writeLedger(tenantId, peopleId, d, RewardBizType.FULL_DAY.name(), d.toString(), 0,
                    RewardRules.POINTS_FULL_DAY, "全勤日 · 方案任务全部完成");
            award(s, RewardBadgeDef.FIRST_FULL_DAY, d, badges);
            List<RewardDay> week = rewardMapper.listDays(peopleId, d.minusDays(6), d);
            if (week.size() == 7 && week.stream().allMatch(w -> nz(w.getFullDay()) == 1)) {
                award(s, RewardBadgeDef.FIRST_FULL_WEEK, d, badges);
            }
        }
        for (int n : STREAK_BADGES) {
            if (step.streak() >= n) {
                award(s, RewardBadgeDef.valueOf("STREAK_" + n), d, badges);
            }
        }
        if (sourceMapper.countSelfMeasurements(tenantId, peopleId) >= 50) {
            award(s, RewardBadgeDef.VITAL_50, d, badges);
        }
        if (sourceMapper.countPhotoMeals(tenantId, peopleId) >= 30) {
            award(s, RewardBadgeDef.PHOTO_30, d, badges);
        }
        return step.freezeUsed();
    }

    private void award(RewardSummary s, RewardBadgeDef def, LocalDate day, List<String> sink) {
        if (grantBadge(s.getTenantId(), s.getPeopleId(), def, day, true)) {
            sink.add(def.title());
        }
    }

    /** @return 是否新获得 */
    public boolean grantBadge(String tenantId, String peopleId, RewardBadgeDef def, LocalDate day, boolean withPoints) {
        RewardBadge b = new RewardBadge();
        b.setTenantId(tenantId);
        b.setPeopleId(peopleId);
        b.setBadgeCode(def.name());
        b.setBadgeName(def.title());
        b.setKind(RewardBadgeDef.KIND_BEHAVIOR);
        b.setEarnedDate(day);
        EntityMeta.onCreate(b);
        if (rewardMapper.insertBadge(b) <= 0) {
            return false;
        }
        if (withPoints) {
            writeLedger(tenantId, peopleId, day, RewardBizType.BADGE.name(), def.name(), 0,
                    RewardBadgeDef.POINTS, "获得徽章 · " + def.title());
        }
        return true;
    }

    private void refreshLevel(RewardSummary s) {
        Integer lifetime = rewardMapper.sumPoints(s.getPeopleId(), null, null);
        int lv = RewardLevel.ofPoints(lifetime == null ? 0 : lifetime).level();
        if (lv > nz(s.getLevelMax())) {
            s.setLevelMax(lv);
            EntityMeta.onUpdate(s);
            rewardMapper.updateSummary(s);
        }
    }

    // ---------------------------------------------------------------- 结果荣誉徽章

    /**
     * 报告发布时把"结果"类里程碑转为荣誉徽章（不给豆）：某指标本期 ≥5 次测量全在参考范围内、体重较期初下降 ≥1kg。
     *
     * @return 新获得的徽章名
     */
    public List<String> awardOutcomeBadges(
            String tenantId, String peopleId, String reportId, LocalDate day, Map<String, Object> content) {
        List<String> out = new ArrayList<>();
        if (content == null || !tenantEnabled(tenantId)) {
            return out;
        }
        RewardSummary s = ensureSummary(tenantId, peopleId);
        if (!enabled(tenantId, s)) {
            return out;
        }
        Object metrics = content.get("metrics");
        if (!(metrics instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String family = str(m.get("family"));
            String label = str(m.get("label"));
            if (!StringUtils.hasText(family)) {
                continue;
            }
            if ("WEIGHT".equals(family)) {
                Object stats = m.get("stats");
                Double delta = stats instanceof Map<?, ?> st ? dbl(st.get("delta")) : null;
                if (delta != null && delta <= -1) {
                    grantOutcome(tenantId, peopleId, "OUTCOME_WEIGHT_DOWN", "体重下降", reportId, day, out);
                }
                continue;
            }
            if (intv(m.get("count")) >= 5 && intv(m.get("abnormalCount")) == 0) {
                String name = (StringUtils.hasText(label) ? label : "指标") + "全在范围";
                grantOutcome(tenantId, peopleId, RewardBadgeDef.OUTCOME_PREFIX + "INRANGE_" + family, name,
                        reportId, day, out);
            }
        }
        return out;
    }

    private void grantOutcome(
            String tenantId, String peopleId, String code, String name, String reportId, LocalDate day,
            List<String> sink) {
        RewardBadge b = new RewardBadge();
        b.setTenantId(tenantId);
        b.setPeopleId(peopleId);
        b.setBadgeCode(code.length() > 64 ? code.substring(0, 64) : code);
        b.setBadgeName(name.length() > 64 ? name.substring(0, 64) : name);
        b.setKind(RewardBadgeDef.KIND_OUTCOME);
        b.setEarnedDate(day);
        b.setSourceRef(reportId);
        EntityMeta.onCreate(b);
        if (rewardMapper.insertBadge(b) > 0) {
            sink.add(name);
        }
    }

    // ---------------------------------------------------------------- 开关

    public void setPatientEnabled(String tenantId, String peopleId, boolean enabled) {
        ensureSummary(tenantId, peopleId);
        rewardMapper.updateDisabled(peopleId, enabled ? 0 : 1);
    }

    // ---------------------------------------------------------------- 推送

    /** 终结算后的合并推送（徽章 / 保护卡 / 升级），每人每天最多 1 条。 */
    public void pushSettleOutcome(String tenantId, String peopleId, SettleOutcome o) {
        if (o.newBadges().isEmpty() && o.freezeUsed() == 0 && !o.levelUp()) {
            return;
        }
        RewardSummary s = rewardMapper.findSummary(peopleId);
        LocalDate today = today();
        if (s == null || today.equals(s.getLastPushDate())) {
            return;
        }
        List<String> parts = new ArrayList<>();
        if (!o.newBadges().isEmpty()) {
            parts.add("获得新徽章「" + String.join("」「", o.newBadges()) + "」");
        }
        if (o.levelUp()) {
            parts.add("升级为 " + RewardLevel.of(nz(s.getLevelMax())).title());
        }
        if (o.freezeUsed() > 0) {
            parts.add("保护卡帮你守住了 " + nz(s.getStreakDays()) + " 天火苗");
        }
        if (notifier.push(tenantId, peopleId, "settle:" + today, "我的成就", String.join("，", parts))) {
            rewardMapper.updateLastPushDate(peopleId, today);
        }
    }

    /** 20:00 后：火苗 ≥3 且今日 0 次行为且今日未推送过 → 提醒。 */
    public boolean remindEvening(String tenantId, String peopleId) {
        RewardSummary s = rewardMapper.findSummary(peopleId);
        if (s == null || !enabled(tenantId, s)) {
            return false;
        }
        LocalDate today = today();
        if (today.equals(s.getLastPushDate())) {
            return false;
        }
        RecentSync recent = syncRecent(tenantId, peopleId, s);
        if (recent.today().active() || !recent.today().entries().isEmpty()) {
            return false;
        }
        int streak = provisionalStreak(s, recent.activeDays());
        if (streak < 3) {
            return false;
        }
        String body = "火苗已连续 " + streak + " 天，今天还没打卡，完成任意一项就能续上 🔥";
        if (notifier.push(tenantId, peopleId, "evening:" + today, "别让火苗熄灭", body)) {
            rewardMapper.updateLastPushDate(peopleId, today);
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- util

    static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static int intv(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? 0 : Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Double dbl(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return v == null ? null : Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
