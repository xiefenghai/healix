package com.healix.core.careplan.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.careplan.domain.CarePlan;
import com.healix.core.careplan.domain.CarePlanTask;
import com.healix.core.careplan.domain.CarePlanTaskCheckin;
import com.healix.core.careplan.domain.CarePlanVersion;
import com.healix.core.careplan.dto.CarePlanCheckinViewDto;
import com.healix.core.careplan.dto.CarePlanDailyCheckinSummaryDto;
import com.healix.core.careplan.dto.CarePlanTodayDto;
import com.healix.core.careplan.dto.CarePlanTodayTaskDto;
import com.healix.core.careplan.enums.CarePlanCheckinStatusEnum;
import com.healix.core.careplan.enums.CarePlanStatusEnum;
import com.healix.core.careplan.mapper.CarePlanMapper;
import com.healix.core.careplan.mapper.CarePlanTaskCheckinMapper;
import com.healix.core.careplan.mapper.CarePlanTaskMapper;
import com.healix.core.careplan.mapper.CarePlanVersionMapper;
import com.healix.core.careplan.support.CarePlanDueSupport;
import com.healix.core.careplan.support.CarePlanSchemaVersions;
import com.healix.core.worktask.service.WorkspaceTaskGenerator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class CarePlanCheckinService {

    private final CarePlanMapper carePlanMapper;
    private final CarePlanVersionMapper versionMapper;
    private final CarePlanTaskMapper taskMapper;
    private final CarePlanTaskCheckinMapper checkinMapper;
    private final ArchiveAccessService archiveAccessService;
    private final ObjectProvider<WorkspaceTaskGenerator> workspaceTaskGenerator;

    public CarePlanTodayDto getPatientToday(String tenantId, String peopleId, LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now();
        CarePlanTodayDto out = new CarePlanTodayDto();
        out.setDate(day);
        CarePlan plan = carePlanMapper.findByTenantAndPeople(tenantId, peopleId);
        if (plan == null
                || !CarePlanStatusEnum.ACTIVE.name().equals(plan.getStatus())
                || !StringUtils.hasText(plan.getCurrentVersionId())) {
            out.setTasks(List.of());
            return out;
        }
        CarePlanVersion version = versionMapper.findById(plan.getCurrentVersionId());
        if (version == null) {
            out.setTasks(List.of());
            return out;
        }

        LocalDate planStart =
                version.getPublishedAt() != null ? version.getPublishedAt().toLocalDate() : day;
        int horizonDays = CarePlanDueSupport.resolveHorizonDays(version.getExecutionJson());
        // 查询日不在执行窗口（发布前 / 已过周期）→ 当日无执行中方案
        if (!CarePlanDueSupport.isWithinHorizon(day, planStart, horizonDays)) {
            out.setTasks(List.of());
            return out;
        }

        out.setPlanId(plan.getId());
        out.setPlanTitle(plan.getTitle());
        out.setGoalSummary(plan.getGoalSummary());
        out.setVersionNo(version.getVersionNo());
        out.setSchemaVersion(
                version.getSchemaVersion() != null ? version.getSchemaVersion() : CarePlanSchemaVersions.CURRENT);
        out.setVersionLabel(CarePlanSchemaVersions.label(out.getSchemaVersion()));

        List<CarePlanTask> tasks = taskMapper.listActiveByPeople(tenantId, peopleId);
        List<CarePlanTaskCheckin> checkins = checkinMapper.listByPeopleDate(tenantId, peopleId, day);
        Map<String, CarePlanTaskCheckin> checkinByTaskSlot = indexCheckins(checkins);

        List<CarePlanTodayTaskDto> taskDtos = new ArrayList<>();
        int done = 0;
        int skipped = 0;
        int pending = 0;
        for (CarePlanTask task : tasks) {
            if (!CarePlanDueSupport.isDueOnDate(task, day, planStart, horizonDays)) {
                continue;
            }
            String slot = CarePlanDueSupport.resolveTimeSlot(task.getTimeSlot());
            CarePlanTaskCheckin checkin = checkinByTaskSlot.get(task.getId() + "|" + slot);
            CarePlanTodayTaskDto dto = toTodayTask(task, checkin);
            taskDtos.add(dto);
            if (checkin == null) {
                pending++;
            } else if (CarePlanCheckinStatusEnum.DONE.name().equals(checkin.getStatus())) {
                done++;
            } else if (CarePlanCheckinStatusEnum.SKIPPED.name().equals(checkin.getStatus())) {
                skipped++;
            } else {
                pending++;
            }
        }
        out.setTasks(taskDtos);
        out.setTotalTasks(taskDtos.size());
        out.setDoneTasks(done);
        out.setSkippedTasks(skipped);
        out.setPendingTasks(pending);
        return out;
    }

    @Transactional
    public CarePlanCheckinViewDto upsertByPatient(
            String tenantId, String peopleId, String taskId, CheckinCommand cmd) {
        CarePlanTask task = requireActiveTask(tenantId, peopleId, taskId);
        LocalDate day = cmd.checkinDate() != null ? cmd.checkinDate() : LocalDate.now();
        String slot = CarePlanDueSupport.resolveTimeSlot(
                StringUtils.hasText(cmd.timeSlot()) ? cmd.timeSlot() : task.getTimeSlot());
        String status = resolveStatus(cmd.status());

        CarePlanTaskCheckin existing = checkinMapper.findByTaskDateSlot(taskId, day, slot);
        CarePlanCheckinViewDto view;
        if (existing == null) {
            CarePlanTaskCheckin row = new CarePlanTaskCheckin();
            row.setTenantId(tenantId);
            row.setPeopleId(peopleId);
            row.setPlanId(task.getPlanId());
            row.setPlanVersionId(task.getPlanVersionId());
            row.setTaskId(taskId);
            row.setCheckinDate(day);
            row.setTimeSlot(slot);
            row.setStatus(status);
            row.setNote(trimNote(cmd.note()));
            row.setRecordedByPeopleId(peopleId);
            EntityMeta.onCreate(row);
            checkinMapper.insert(row);
            view = toView(row, task);
        } else {
            existing.setStatus(status);
            existing.setNote(trimNote(cmd.note()));
            existing.setRecordedByPeopleId(peopleId);
            existing.setRecordedByStaffId(null);
            EntityMeta.onUpdate(existing);
            checkinMapper.update(existing);
            view = toView(existing, task);
        }
        maybeClosePlanNudgeAfterCheckin(tenantId, peopleId, day, status);
        return view;
    }

    /** 当日应打任务已全部完成/跳过时，关闭过期的打卡跟进单。 */
    private void maybeClosePlanNudgeAfterCheckin(
            String tenantId, String peopleId, LocalDate day, String status) {
        if (!CarePlanCheckinStatusEnum.DONE.name().equals(status)
                && !CarePlanCheckinStatusEnum.SKIPPED.name().equals(status)) {
            return;
        }
        CarePlanTodayDto today = getPatientToday(tenantId, peopleId, day);
        if (today.getTotalTasks() <= 0 || today.getPendingTasks() > 0) {
            return;
        }
        WorkspaceTaskGenerator gen = workspaceTaskGenerator.getIfAvailable();
        if (gen == null) {
            return;
        }
        try {
            gen.onPlanDayCompletedByCheckin(tenantId, peopleId, day);
        } catch (Exception ex) {
            log.warn(
                    "auto-close PLAN_NUDGE after checkin failed tenant={} people={} day={}: {}",
                    tenantId,
                    peopleId,
                    day,
                    ex.getMessage());
        }
    }

    public CarePlanDailyCheckinSummaryDto getDailySummary(
            String tenantId, String orgId, String peopleId, LocalDate date) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        LocalDate day = date != null ? date : LocalDate.now();
        CarePlanTodayDto today = getPatientToday(tenantId, peopleId, day);
        CarePlanDailyCheckinSummaryDto summary = new CarePlanDailyCheckinSummaryDto();
        summary.setDate(day);
        summary.setTotalTasks(today.getTotalTasks());
        summary.setDoneTasks(today.getDoneTasks());
        summary.setSkippedTasks(today.getSkippedTasks());
        summary.setPendingTasks(today.getPendingTasks());
        summary.setTasks(today.getTasks());
        int missed = 0;
        List<CarePlanCheckinViewDto> views = new ArrayList<>();
        Map<String, CarePlanTask> taskMap = new HashMap<>();
        for (CarePlanTask t : taskMapper.listActiveByPeople(tenantId, peopleId)) {
            taskMap.put(t.getId(), t);
        }
        for (CarePlanTaskCheckin row : checkinMapper.listByPeopleDate(tenantId, peopleId, day)) {
            CarePlanTask task = taskMap.get(row.getTaskId());
            if (task == null) {
                continue;
            }
            views.add(toView(row, task));
            if (CarePlanCheckinStatusEnum.MISSED.name().equals(row.getStatus())) {
                missed++;
            }
        }
        summary.setMissedTasks(missed);
        summary.setCheckins(views);
        return summary;
    }

    public List<CarePlanCheckinViewDto> listCheckins(
            String tenantId, String orgId, String peopleId, LocalDate from, LocalDate to) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        List<CarePlanTaskCheckin> rows = checkinMapper.listByPeopleRange(tenantId, peopleId, from, to);
        Map<String, CarePlanTask> taskMap = new HashMap<>();
        for (CarePlanTask t : taskMapper.listActiveByPeople(tenantId, peopleId)) {
            taskMap.put(t.getId(), t);
        }
        List<CarePlanCheckinViewDto> out = new ArrayList<>();
        for (CarePlanTaskCheckin row : rows) {
            CarePlanTask task = taskMap.get(row.getTaskId());
            if (task != null) {
                out.add(toView(row, task));
            }
        }
        return out;
    }

    /** B 端：某历史/当前版本下的打卡记录（按该版本任务解析标题）。 */
    public List<CarePlanCheckinViewDto> listCheckinsByVersionForStaff(
            String tenantId, String orgId, String peopleId, String versionId, LocalDate from, LocalDate to) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return listCheckinsByVersion(tenantId, peopleId, versionId, from, to);
    }

    /** C 端：某历史/当前版本下的打卡记录。 */
    public List<CarePlanCheckinViewDto> listCheckinsByVersionForPatient(
            String tenantId, String peopleId, String versionId, LocalDate from, LocalDate to) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        return listCheckinsByVersion(tenantId, peopleId, versionId, from, to);
    }

    private List<CarePlanCheckinViewDto> listCheckinsByVersion(
            String tenantId, String peopleId, String versionId, LocalDate from, LocalDate to) {
        CarePlan plan = carePlanMapper.findByTenantAndPeople(tenantId, peopleId);
        if (plan == null) {
            throw new BusinessException("暂无管理方案");
        }
        CarePlanVersion version = versionMapper.findById(versionId);
        if (version == null || !plan.getId().equals(version.getPlanId())) {
            throw new BusinessException(404, "方案版本不存在");
        }
        Map<String, CarePlanTask> taskMap = new HashMap<>();
        for (CarePlanTask t : taskMapper.listByVersionId(versionId)) {
            taskMap.put(t.getId(), t);
        }
        List<CarePlanTaskCheckin> rows =
                checkinMapper.listByPeopleVersionRange(tenantId, peopleId, versionId, from, to);
        List<CarePlanCheckinViewDto> out = new ArrayList<>();
        for (CarePlanTaskCheckin row : rows) {
            CarePlanTask task = taskMap.get(row.getTaskId());
            if (task != null) {
                out.add(toView(row, task));
            }
        }
        return out;
    }

    private CarePlanTask requireActiveTask(String tenantId, String peopleId, String taskId) {
        CarePlanTask task = taskMapper.listActiveByPeople(tenantId, peopleId).stream()
                .filter(t -> taskId.equals(t.getId()))
                .findFirst()
                .orElse(null);
        if (task == null) {
            throw new BusinessException(404, "任务不存在或未在生效方案中");
        }
        return task;
    }

    private static Map<String, CarePlanTaskCheckin> indexCheckins(List<CarePlanTaskCheckin> checkins) {
        Map<String, CarePlanTaskCheckin> map = new HashMap<>();
        for (CarePlanTaskCheckin c : checkins) {
            map.put(c.getTaskId() + "|" + c.getTimeSlot(), c);
        }
        return map;
    }

    private static String resolveStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return CarePlanCheckinStatusEnum.DONE.name();
        }
        try {
            return CarePlanCheckinStatusEnum.valueOf(status.trim().toUpperCase()).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("无效的打卡状态");
        }
    }

    private static String trimNote(String note) {
        if (!StringUtils.hasText(note)) {
            return null;
        }
        String trimmed = note.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }

    private static CarePlanTodayTaskDto toTodayTask(CarePlanTask task, CarePlanTaskCheckin checkin) {
        CarePlanTodayTaskDto dto = new CarePlanTodayTaskDto();
        dto.setId(task.getId());
        dto.setTaskCode(task.getTaskCode());
        dto.setTitle(task.getTitle());
        dto.setCategory(task.getCategory());
        dto.setFrequency(task.getFrequency());
        dto.setTimeSlot(task.getTimeSlot());
        if (checkin != null) {
            dto.setCheckinStatus(checkin.getStatus());
            dto.setCheckinId(checkin.getId());
            dto.setNote(checkin.getNote());
        }
        return dto;
    }

    private static CarePlanCheckinViewDto toView(CarePlanTaskCheckin row, CarePlanTask task) {
        CarePlanCheckinViewDto dto = new CarePlanCheckinViewDto();
        dto.setId(row.getId());
        dto.setCheckinDate(row.getCheckinDate());
        dto.setPlanVersionId(row.getPlanVersionId());
        dto.setTaskId(row.getTaskId());
        dto.setTaskTitle(task.getTitle());
        dto.setTaskCategory(task.getCategory());
        dto.setTimeSlot(row.getTimeSlot());
        dto.setStatus(row.getStatus());
        dto.setNote(row.getNote());
        dto.setRecordedByPeopleId(row.getRecordedByPeopleId());
        dto.setRecordedByStaffId(row.getRecordedByStaffId());
        dto.setGmtCreated(row.getGmtCreated());
        return dto;
    }

    public record CheckinCommand(LocalDate checkinDate, String timeSlot, String status, String note) {}
}
