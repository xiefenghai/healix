package com.healix.core.mood.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.domain.CarePlanTask;
import com.healix.core.careplan.dto.CarePlanTodayDto;
import com.healix.core.careplan.dto.CarePlanTodayTaskDto;
import com.healix.core.careplan.mapper.CarePlanTaskMapper;
import com.healix.core.careplan.service.CarePlanCheckinService;
import com.healix.core.mood.domain.MoodLog;
import com.healix.core.mood.dto.MoodDayDto;
import com.healix.core.mood.mapper.MoodLogMapper;
import com.healix.core.mood.support.MoodLogRules;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class MoodLogService {

    private final MoodLogMapper moodLogMapper;
    private final CarePlanTaskMapper taskMapper;
    private final CarePlanCheckinService checkinService;
    private final PlatformTransactionManager transactionManager;

    public MoodDayDto day(String tenantId, String peopleId, LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now();
        MoodLog row = moodLogMapper.findByPeopleDate(tenantId, peopleId, d);
        CarePlanTask task = findMoodTask(tenantId, peopleId);
        MoodDayDto out = toDay(d, row, task);
        fillTrend(out, tenantId, peopleId, d);
        if (task != null) {
            CarePlanTodayDto today = checkinService.getPatientToday(tenantId, peopleId, d);
            for (CarePlanTodayTaskDto t : today.getTasks()) {
                if (task.getId().equals(t.getId())) {
                    out.setCheckinStatus(t.getCheckinStatus());
                    break;
                }
            }
        }
        return out;
    }

    @Transactional
    public MoodDayDto upsert(
            String tenantId, String peopleId, LocalDate date, Integer score, List<String> tags, String note) {
        return upsert(tenantId, peopleId, date, score, tags, note, null);
    }

    /** staffId 非空为 B 端代录；每天一条，患者与健管师互不覆盖。 */
    @Transactional
    public MoodDayDto upsert(
            String tenantId,
            String peopleId,
            LocalDate date,
            Integer score,
            List<String> tags,
            String note,
            String staffId) {
        LocalDate d = date != null ? date : LocalDate.now();
        assertEditableDate(d);
        if (score == null || score < MoodLogRules.MIN_SCORE || score > MoodLogRules.MAX_SCORE) {
            throw new BusinessException(400, "心情评分需为 1–5");
        }
        String tagsJson = tags == null || tags.isEmpty() ? null : JsonUtils.toJson(tags);
        MoodLog existing = moodLogMapper.findByPeopleDate(tenantId, peopleId, d);
        if (existing != null) {
            assertWritable(existing, staffId);
        }
        if (existing == null) {
            MoodLog row = new MoodLog();
            row.setTenantId(tenantId);
            row.setPeopleId(peopleId);
            row.setBizDate(d);
            row.setScore(score);
            row.setTagsJson(tagsJson);
            row.setNote(trimNote(note));
            if (StringUtils.hasText(staffId)) {
                row.setRecordedByStaffId(staffId);
            } else {
                row.setRecordedByPeopleId(peopleId);
            }
            EntityMeta.onCreate(row);
            moodLogMapper.insert(row);
        } else {
            existing.setScore(score);
            existing.setTagsJson(tagsJson);
            existing.setNote(trimNote(note));
            EntityMeta.onUpdate(existing);
            moodLogMapper.update(existing);
        }
        syncCheckin(tenantId, peopleId, d, true);
        return day(tenantId, peopleId, d);
    }

    @Transactional
    public MoodDayDto delete(String tenantId, String peopleId, LocalDate date, String staffId) {
        LocalDate d = date != null ? date : LocalDate.now();
        MoodLog existing = moodLogMapper.findByPeopleDate(tenantId, peopleId, d);
        if (existing == null) {
            throw new BusinessException(404, "记录不存在");
        }
        assertWritable(existing, staffId);
        assertEditableDate(d);
        moodLogMapper.softDelete(existing.getId());
        syncCheckin(tenantId, peopleId, d, false);
        return day(tenantId, peopleId, d);
    }

    /** 患者只能改自己记的，健管师只能改代录的。 */
    private static void assertWritable(MoodLog row, String staffId) {
        boolean byStaff = StringUtils.hasText(row.getRecordedByStaffId());
        if (!StringUtils.hasText(staffId) && byStaff) {
            throw new BusinessException(400, "当天心情已由健管师代录，不可修改");
        }
        if (StringUtils.hasText(staffId) && !byStaff) {
            throw new BusinessException(400, "患者当天已记录心情，不可修改");
        }
    }

    private static void assertEditableDate(LocalDate d) {
        if (d.isAfter(LocalDate.now())) {
            throw new BusinessException(400, "不能记录未来日期");
        }
        if (d.isBefore(LocalDate.now().minusDays(7))) {
            throw new BusinessException(400, "只能补记近 7 天");
        }
    }

    public CarePlanTask findMoodTask(String tenantId, String peopleId) {
        List<CarePlanTask> tasks = taskMapper.listActiveByPeople(tenantId, peopleId);
        if (tasks == null) {
            return null;
        }
        for (CarePlanTask t : tasks) {
            if (t.getEnabled() != null && t.getEnabled() == 0) {
                continue;
            }
            if (MoodLogRules.looksLikeMoodTask(t.getCategory(), t.getTitle(), t.getTaskCode())) {
                return t;
            }
        }
        return null;
    }

    private void syncCheckin(String tenantId, String peopleId, LocalDate date, boolean done) {
        Runnable sync = () -> {
            try {
                requiresNew().executeWithoutResult(st -> {
                    CarePlanTask task = findMoodTask(tenantId, peopleId);
                    if (task == null) {
                        return;
                    }
                    checkinService.syncSystemCheckin(
                            tenantId, peopleId, MoodLogRules.TASK_CODE, date, done, peopleId);
                    if (!MoodLogRules.TASK_CODE.equals(task.getTaskCode())) {
                        checkinService.syncSystemCheckin(
                                tenantId, peopleId, task.getTaskCode(), date, done, peopleId);
                    }
                });
            } catch (RuntimeException e) {
                log.warn("sync MOOD checkin failed people={} date={}: {}", peopleId, date, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sync.run();
                }
            });
        } else {
            sync.run();
        }
    }

    private void fillTrend(MoodDayDto out, String tenantId, String peopleId, LocalDate date) {
        LocalDate from = date.minusDays(13);
        List<MoodLog> rows = moodLogMapper.listByPeopleRange(tenantId, peopleId, from, date);
        int sum = 0;
        int n = 0;
        List<MoodDayDto.TrendPoint> trend = new ArrayList<>();
        for (MoodLog r : rows) {
            MoodDayDto.TrendPoint p = new MoodDayDto.TrendPoint();
            p.setDate(r.getBizDate());
            p.setScore(r.getScore());
            p.setLabel(r.getScore() == null ? null : MoodLogRules.labelOf(r.getScore()));
            trend.add(p);
            if (r.getScore() != null) {
                sum += r.getScore();
                n++;
            }
        }
        out.setTrend(trend);
        out.setAvgScore14d(n == 0 ? null : Math.round(sum * 10.0 / n) / 10.0);
    }

    private static MoodDayDto toDay(LocalDate d, MoodLog row, CarePlanTask task) {
        MoodDayDto out = new MoodDayDto();
        out.setDate(d);
        out.setTaskPresent(task != null);
        out.setLogged(row != null);
        if (row != null) {
            out.setScore(row.getScore());
            out.setLabel(row.getScore() == null ? null : MoodLogRules.labelOf(row.getScore()));
            out.setTags(parseTags(row.getTagsJson()));
            out.setNote(row.getNote());
            out.setRecordedByStaff(StringUtils.hasText(row.getRecordedByStaffId()));
        }
        return out;
    }

    private static List<String> parseTags(String json) {
        List<String> out = new ArrayList<>();
        if (!StringUtils.hasText(json)) {
            return out;
        }
        try {
            JsonNode n = JsonUtils.readTree(json);
            if (n != null && n.isArray()) {
                n.forEach(x -> {
                    if (x != null && x.isTextual() && StringUtils.hasText(x.asText())) {
                        out.add(x.asText().trim());
                    }
                });
            }
        } catch (RuntimeException ignored) {
            // ignore
        }
        return out;
    }

    private static String trimNote(String note) {
        if (!StringUtils.hasText(note)) {
            return null;
        }
        String t = note.trim();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    private TransactionTemplate requiresNew() {
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }
}
