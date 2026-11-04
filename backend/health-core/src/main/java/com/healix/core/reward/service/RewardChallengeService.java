package com.healix.core.reward.service;

import static com.healix.core.reward.service.RewardService.nz;

import com.healix.common.domain.EntityMeta;
import com.healix.core.reward.catalog.RewardChallengeKind;
import com.healix.core.reward.domain.RewardChallenge;
import com.healix.core.reward.domain.RewardDay;
import com.healix.core.reward.domain.RewardSummary;
import com.healix.core.reward.dto.RewardViews.Challenge;
import com.healix.core.reward.mapper.RewardChallengeMapper;
import com.healix.core.reward.mapper.RewardMapper;
import com.healix.core.reward.mapper.RewardSourceMapper;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 周挑战：周一自动生成 1 个（按上周最弱项），读时刷新进度，下周一结算发豆。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RewardChallengeService {

    private final RewardService rewardService;
    private final RewardChallengeMapper challengeMapper;
    private final RewardMapper rewardMapper;
    private final RewardSourceMapper sourceMapper;

    public static LocalDate weekStart(LocalDate day) {
        return day.with(DayOfWeek.MONDAY);
    }

    public static LocalDate weekEnd(LocalDate day) {
        return weekStart(day).plusDays(6);
    }

    /** 读接口：刷新本周进度并返回视图。 */
    public Challenge current(String tenantId, String peopleId) {
        if (!rewardService.tenantEnabled(tenantId)) {
            return null;
        }
        RewardSummary s = rewardMapper.findSummary(peopleId);
        if (s == null || nz(s.getRewardDisabled()) != 0) {
            return null;
        }
        LocalDate today = RewardService.today();
        LocalDate start = weekStart(today);
        RewardChallenge row = challengeMapper.findActive(peopleId, start);
        if (row == null) {
            return null;
        }
        refreshProgress(tenantId, row);
        return toView(row);
    }

    public void refreshProgress(String tenantId, RewardChallenge row) {
        if (row == null || !"ACTIVE".equals(row.getStatus())) {
            return;
        }
        int progress = progressOf(tenantId, row.getPeopleId(), row.getKind(), row.getPeriodStart(), row.getPeriodEnd());
        String status = progress >= nz(row.getTarget()) ? "DONE" : "ACTIVE";
        if (progress != nz(row.getProgress()) || !status.equals(row.getStatus())) {
            row.setProgress(progress);
            row.setStatus(status);
            EntityMeta.onUpdate(row);
            challengeMapper.updateProgress(row);
        }
    }

    private int progressOf(String tenantId, String peopleId, String kind, LocalDate from, LocalDate to) {
        LocalDate end = to.isAfter(RewardService.today()) ? RewardService.today() : to;
        return switch (kind) {
            case "FULL_DAYS" -> (int) rewardMapper.listDays(peopleId, from, end).stream()
                    .filter(d -> nz(d.getFullDay()) == 1)
                    .count();
            case "VITAL_COUNT" -> sourceMapper.countSelfMeasurementsBetween(tenantId, peopleId, from, end);
            case "PHOTO_MEAL" -> sourceMapper.countPhotoMealsBetween(tenantId, peopleId, from, end);
            default -> 0;
        };
    }

    /**
     * 为一人生成本周挑战（已有则跳过）。目标 = max(下限, ceil(上周实际 × 1.2))。
     *
     * @return 是否新建
     */
    @Transactional
    public boolean ensureWeekChallenge(String tenantId, String peopleId, LocalDate anyDayInWeek) {
        if (!rewardService.tenantEnabled(tenantId)) {
            return false;
        }
        RewardSummary s = rewardService.ensureSummary(tenantId, peopleId);
        if (nz(s.getRewardDisabled()) != 0) {
            return false;
        }
        LocalDate start = weekStart(anyDayInWeek);
        LocalDate end = weekEnd(anyDayInWeek);
        if (challengeMapper.findActive(peopleId, start) != null) {
            return false;
        }
        LocalDate prevStart = start.minusDays(7);
        LocalDate prevEnd = start.minusDays(1);
        Pick pick = pickKind(tenantId, peopleId, prevStart, prevEnd);
        RewardChallenge row = new RewardChallenge();
        row.setTenantId(tenantId);
        row.setPeopleId(peopleId);
        row.setPeriodStart(start);
        row.setPeriodEnd(end);
        row.setKind(pick.kind().name());
        row.setTitle(pick.kind().displayTitle(pick.target()));
        row.setTarget(pick.target());
        row.setProgress(0);
        row.setStatus("ACTIVE");
        row.setSource("AUTO");
        row.setPointsAwarded(0);
        EntityMeta.onCreate(row);
        try {
            challengeMapper.insert(row);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private record Pick(RewardChallengeKind kind, int target) {}

    private Pick pickKind(String tenantId, String peopleId, LocalDate from, LocalDate to) {
        List<RewardDay> days = rewardMapper.listDays(peopleId, from, to);
        int full = (int) days.stream().filter(d -> nz(d.getFullDay()) == 1).count();
        int vitals = sourceMapper.countSelfMeasurementsBetween(tenantId, peopleId, from, to);
        int photos = sourceMapper.countPhotoMealsBetween(tenantId, peopleId, from, to);

        // 完成率越低越优先；目标跳一跳够得着
        double fullRate = full / 7.0;
        double vitalRate = vitals / 14.0; // 假设一周约 14 次为满分参照
        double photoRate = photos / 7.0;
        RewardChallengeKind kind = RewardChallengeKind.FULL_DAYS;
        double worst = fullRate;
        if (vitalRate <= worst) {
            kind = RewardChallengeKind.VITAL_COUNT;
            worst = vitalRate;
        }
        if (photoRate <= worst) {
            kind = RewardChallengeKind.PHOTO_MEAL;
        }
        int target =
                switch (kind) {
                    case FULL_DAYS -> clamp(ceil(full * 1.2), 4, 7);
                    case VITAL_COUNT -> Math.max(5, ceil(vitals * 1.2));
                    case PHOTO_MEAL -> Math.max(3, ceil(photos * 1.2));
                };
        return new Pick(kind, target);
    }

    private static int ceil(double v) {
        return (int) Math.ceil(v);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 结算上一周：达成发 +50，未达成标 FAILED。 */
    @Transactional
    public boolean settleWeek(String tenantId, String peopleId, LocalDate weekStart) {
        RewardChallenge row = challengeMapper.findActive(peopleId, weekStart);
        if (row == null || nz(row.getPointsAwarded()) > 0) {
            return false;
        }
        if ("ACTIVE".equals(row.getStatus()) || "DONE".equals(row.getStatus())) {
            refreshProgress(tenantId, row);
            row = challengeMapper.findActive(peopleId, weekStart);
        }
        if (row == null) {
            return false;
        }
        int progress = progressOf(tenantId, peopleId, row.getKind(), row.getPeriodStart(), row.getPeriodEnd());
        boolean done = progress >= nz(row.getTarget());
        row.setProgress(progress);
        row.setStatus(done ? "DONE" : "FAILED");
        if (done) {
            row.setPointsAwarded(RewardChallengeKind.POINTS);
            rewardService.awardChallengePoints(
                    tenantId, peopleId, row.getPeriodEnd(), row.getId(), "完成周挑战 · " + row.getTitle());
        }
        EntityMeta.onUpdate(row);
        challengeMapper.updateSettled(row);
        return done;
    }

    public static Challenge toView(RewardChallenge row) {
        if (row == null) {
            return null;
        }
        int target = nz(row.getTarget());
        int progress = nz(row.getProgress());
        return new Challenge(
                row.getId(),
                row.getKind(),
                row.getTitle(),
                target,
                progress,
                row.getStatus(),
                row.getPeriodStart(),
                row.getPeriodEnd(),
                target <= 0 ? 1.0 : Math.min(1.0, progress / (double) target),
                nz(row.getPointsAwarded()));
    }
}
