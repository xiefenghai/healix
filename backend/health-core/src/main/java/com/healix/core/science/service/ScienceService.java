package com.healix.core.science.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.core.careplan.dto.CarePlanTodayDto;
import com.healix.core.careplan.service.CarePlanCheckinService;
import com.healix.core.govern.enums.FeatureFlagKeyEnum;
import com.healix.core.govern.service.FeatureFlagService;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.reward.mapper.RewardSourceMapper;
import com.healix.core.science.catalog.ScienceCatalog;
import com.healix.core.science.catalog.ScienceCatalog.Clip;
import com.healix.core.science.domain.ScienceDaily;
import com.healix.core.science.domain.ScienceWatch;
import com.healix.core.science.dto.ScienceViews.ClipView;
import com.healix.core.science.dto.ScienceViews.Overview;
import com.healix.core.science.dto.ScienceViews.Today;
import com.healix.core.science.mapper.ScienceMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 今日科普：完成一项待办后按片库顺序看一条，标题先给出，点播放才记入看过。 */
@Service
@RequiredArgsConstructor
public class ScienceService {

    private static final String HOLD_PENDING = "完成今天任意 1 项待办后，可以看今日科普。";
    private static final String HOLD_REST = "今天没有待办，明日再来看科普。";

    private final ScienceMapper scienceMapper;
    private final CarePlanCheckinService carePlanCheckinService;
    private final RewardSourceMapper rewardSourceMapper;
    private final FeatureFlagService featureFlagService;

    public static LocalDate today() {
        return LocalDate.now(JobCronSupport.ZONE);
    }

    public boolean enabled(String tenantId) {
        return featureFlagService.enabled(tenantId, FeatureFlagKeyEnum.SCIENCE_DAILY);
    }

    public Today today(String tenantId, String peopleId) {
        if (!enabled(tenantId)) {
            return new Today(false, false, false, false, false, null, null, null);
        }
        LocalDate day = today();
        ScienceDaily existing = retireUnknownClip(scienceMapper.findDaily(peopleId, day), peopleId);
        Map<String, ScienceWatch> watched = watched(peopleId);
        if (existing != null) {
            boolean seenBefore = watched.containsKey(existing.getClipCode()) && !watchedToday(existing);
            return new Today(
                    true,
                    true,
                    true,
                    watchedToday(existing),
                    seenBefore,
                    null,
                    ScienceCatalog.DISCLAIMER,
                    toClip(existing.getClipCode(), watched));
        }
        Eligibility elig = eligibility(tenantId, peopleId, day);
        if (!elig.ok()) {
            return new Today(true, false, false, false, false, elig.holdText(), ScienceCatalog.DISCLAIMER, null);
        }
        return new Today(true, true, false, false, false, null, ScienceCatalog.DISCLAIMER, null);
    }

    @Transactional
    public Today open(String tenantId, String peopleId) {
        if (!enabled(tenantId)) {
            throw new BusinessException(404, "今日科普未开启");
        }
        LocalDate day = today();
        ScienceDaily existing = retireUnknownClip(scienceMapper.findDaily(peopleId, day), peopleId);
        if (existing != null) {
            return today(tenantId, peopleId);
        }
        Eligibility elig = eligibility(tenantId, peopleId, day);
        if (!elig.ok()) {
            throw new BusinessException(400, elig.holdText());
        }
        Map<String, ScienceWatch> watched = watched(peopleId);
        Clip clip = ScienceCatalog.nextUnwatched(watched.keySet());
        if (clip == null) {
            clip = ScienceCatalog.replay(day);
        }
        ScienceDaily row = new ScienceDaily();
        row.setTenantId(tenantId);
        row.setPeopleId(peopleId);
        row.setBizDate(day);
        row.setClipCode(clip.code());
        row.setWatched(0);
        EntityMeta.onCreate(row);
        try {
            scienceMapper.insertDaily(row);
        } catch (DuplicateKeyException ex) {
            return today(tenantId, peopleId);
        }
        return today(tenantId, peopleId);
    }

    @Transactional
    public Today watch(String tenantId, String peopleId) {
        if (!enabled(tenantId)) {
            throw new BusinessException(404, "今日科普未开启");
        }
        ScienceDaily existing = retireUnknownClip(scienceMapper.findDaily(peopleId, today()), peopleId);
        if (existing == null) {
            throw new BusinessException(400, "还没有今日科普");
        }
        if (!watchedToday(existing)) {
            scienceMapper.markWatched(existing.getId(), 1, LocalDateTime.now(JobCronSupport.ZONE));
        }
        return today(tenantId, peopleId);
    }

    public Overview overview(String tenantId, String peopleId) {
        if (!enabled(tenantId)) {
            return new Overview(
                    false, 0, ScienceCatalog.total(), ScienceCatalog.DISCLAIMER, false, false, null, null, List.of());
        }
        Map<String, ScienceWatch> watched = watched(peopleId);
        List<ClipView> clips = new ArrayList<>();
        for (Clip clip : ScienceCatalog.CLIPS) {
            clips.add(toClip(clip.code(), watched));
        }
        int watchedCount = (int) clips.stream().filter(ClipView::watched).count();
        Today t = today(tenantId, peopleId);
        return new Overview(
                true,
                watchedCount,
                ScienceCatalog.total(),
                ScienceCatalog.DISCLAIMER,
                t.eligible() && !t.opened(),
                t.replay(),
                t.holdText(),
                t.opened() ? t.clip() : null,
                clips);
    }

    private record Eligibility(boolean ok, String holdText) {}

    private Eligibility eligibility(String tenantId, String peopleId, LocalDate day) {
        CarePlanTodayDto plan = carePlanCheckinService.getPatientToday(tenantId, peopleId, day);
        int due = plan.getTotalTasks();
        if (due <= 0) {
            return new Eligibility(false, HOLD_REST);
        }
        int done = rewardSourceMapper.countPlanDoneNotStaff(
                tenantId, peopleId, day, day.plusDays(1).atStartOfDay());
        if (done >= 1) {
            return new Eligibility(true, null);
        }
        return new Eligibility(false, HOLD_PENDING);
    }

    /** 片已下架时，把当天这条改成片库里的下一条，避免还指向已删除的视频。 */
    private ScienceDaily retireUnknownClip(ScienceDaily existing, String peopleId) {
        if (existing == null || ScienceCatalog.find(existing.getClipCode()).isPresent()) {
            return existing;
        }
        Map<String, ScienceWatch> watched = watched(peopleId);
        Clip clip = ScienceCatalog.nextUnwatched(watched.keySet());
        if (clip == null) {
            clip = ScienceCatalog.replay(existing.getBizDate());
        }
        scienceMapper.replaceClip(existing.getId(), clip.code(), 0, LocalDateTime.now(JobCronSupport.ZONE));
        existing.setClipCode(clip.code());
        existing.setWatched(0);
        return existing;
    }

    private Map<String, ScienceWatch> watched(String peopleId) {
        Map<String, ScienceWatch> out = new LinkedHashMap<>();
        for (ScienceWatch row : scienceMapper.listWatched(peopleId)) {
            if (ScienceCatalog.find(row.getClipCode()).isPresent()) {
                out.putIfAbsent(row.getClipCode(), row);
            }
        }
        return out;
    }

    private static boolean watchedToday(ScienceDaily row) {
        return row.getWatched() != null && row.getWatched() == 1;
    }

    private static ClipView toClip(String code, Map<String, ScienceWatch> watched) {
        ScienceWatch row = watched.get(code);
        String date = row != null && row.getFirstBizDate() != null ? row.getFirstBizDate().toString() : null;
        return new ClipView(code, ScienceCatalog.title(code), row != null, date);
    }
}
