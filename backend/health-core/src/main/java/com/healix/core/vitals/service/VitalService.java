package com.healix.core.vitals.service;

import com.healix.core.assessment.service.AssessmentDirtyMarker;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.common.util.SnowflakeId;
import com.healix.core.careplan.service.CarePlanMetricCheckinSync;
import com.healix.core.observation.enums.HealthDataSourceEnum;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.vitals.domain.VitalRecord;
import com.healix.core.vitals.enums.MetricTypeEnum;
import com.healix.core.vitals.enums.VitalSourceEnum;
import com.healix.core.vitals.mapper.VitalRecordMapper;
import com.healix.core.vitals.support.VitalValueRules;
import com.healix.core.worktask.service.WorkspaceTaskGenerator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class VitalService {

    private final VitalRecordMapper vitalRecordMapper;
    private final PeopleProfileMapper peopleProfileMapper;
    private final AssessmentDirtyMarker assessmentDirtyMarker;
    private final ObjectProvider<WorkspaceTaskGenerator> workspaceTaskGenerator;
    private final ObjectProvider<CarePlanMetricCheckinSync> metricCheckinSync;

    /** 患者自助录入。新列 org_id/group_id/note/recorded_by_staff_id 可空，与 B 端增强表结构兼容。 */
    @Transactional
    public VitalRecord record(
            String peopleId,
            String metricType,
            BigDecimal value,
            String unit,
            LocalDateTime recordedAt,
            String source) {
        return record(peopleId, metricType, value, unit, recordedAt, source, null, null);
    }

    /** 患者自助录入；可选餐次 / 血压情境写入 extra_json。 */
    @Transactional
    public VitalRecord record(
            String peopleId,
            String metricType,
            BigDecimal value,
            String unit,
            LocalDateTime recordedAt,
            String source,
            String mealContext,
            String bpContext) {
        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        if (profile == null) {
            throw new BusinessException("患者不存在");
        }
        if (profile.getTenantId() == null) {
            throw new BusinessException("患者未归属租户");
        }
        VitalValueRules.assertPlausible(metricType, value);
        VitalRecord record = new VitalRecord();
        record.setTenantId(profile.getTenantId());
        record.setPeopleId(peopleId);
        record.setMetricType(metricType);
        record.setValue(value);
        record.setUnit(unit);
        record.setSource(source);
        record.setExtraJson(buildExtraJson(mealContext, bpContext));
        EntityMeta.onCreate(record);
        record.setRecordedAt(recordedAt != null ? recordedAt : record.getGmtCreated());
        vitalRecordMapper.insert(record);
        assessmentDirtyMarker.mark(profile.getTenantId(), peopleId);
        WorkspaceTaskGenerator gen = workspaceTaskGenerator.getIfAvailable();
        if (gen != null) {
            gen.onVitalsWritten(profile.getTenantId(), record.getOrgId(), peopleId, List.of(record));
        }
        CarePlanMetricCheckinSync sync = metricCheckinSync.getIfAvailable();
        if (sync != null) {
            sync.onMetricsChanged(
                    profile.getTenantId(),
                    peopleId,
                    List.of(CarePlanMetricCheckinSync.MetricDay.of(record)),
                    HealthDataSourceEnum.isPatientOwned(source) ? peopleId : null);
        }
        return record;
    }

    /**
     * 一次写入多条患者自测。血压成对共用 groupId，异常待办只评估这一次。
     *
     * @param syncPlanCheckin 为 false 时不把监测任务打成完成（管家确认记指标）
     */
    @Transactional
    public List<VitalRecord> recordPatientReadings(
            String peopleId,
            List<PatientReading> readings,
            LocalDateTime recordedAt,
            boolean syncPlanCheckin) {
        if (readings == null || readings.isEmpty()) {
            throw new BusinessException("没有可记的指标");
        }
        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        if (profile == null) {
            throw new BusinessException("患者不存在");
        }
        if (profile.getTenantId() == null) {
            throw new BusinessException("患者未归属租户");
        }
        String groupId = SnowflakeId.nextBizId();
        List<VitalRecord> written = new ArrayList<>();
        for (PatientReading reading : readings) {
            VitalValueRules.assertPlausible(reading.metricType(), reading.value());
            VitalRecord row = new VitalRecord();
            row.setTenantId(profile.getTenantId());
            row.setPeopleId(peopleId);
            row.setMetricType(reading.metricType());
            row.setValue(reading.value());
            row.setUnit(reading.unit());
            row.setSource(reading.source());
            row.setGroupId(groupId);
            row.setExtraJson(buildExtraJson(reading.mealContext(), reading.bpContext()));
            EntityMeta.onCreate(row);
            row.setRecordedAt(recordedAt != null ? recordedAt : row.getGmtCreated());
            vitalRecordMapper.insert(row);
            written.add(row);
        }
        assessmentDirtyMarker.mark(profile.getTenantId(), peopleId);
        WorkspaceTaskGenerator gen = workspaceTaskGenerator.getIfAvailable();
        if (gen != null) {
            gen.onVitalsWritten(profile.getTenantId(), null, peopleId, written);
        }
        if (syncPlanCheckin) {
            CarePlanMetricCheckinSync sync = metricCheckinSync.getIfAvailable();
            if (sync != null) {
                sync.onMetricsChanged(
                        profile.getTenantId(),
                        peopleId,
                        written.stream().map(CarePlanMetricCheckinSync.MetricDay::of).toList(),
                        peopleId);
            }
        }
        return written;
    }

    /**
     * 健管师从对话核对卡确认的一批指标。每条自带测量时间；血压成对由调用方传入同一个 groupId。
     */
    @Transactional
    public List<VitalRecord> recordStaffExtract(String tenantId, String orgId, String peopleId, String staffId, List<StaffExtractReading> readings) {
        if (readings == null || readings.isEmpty()) {
            return List.of();
        }
        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        if (profile == null || profile.getTenantId() == null || !tenantId.equals(profile.getTenantId())) {
            throw new BusinessException("患者不存在");
        }
        List<VitalRecord> written = new ArrayList<>();
        for (StaffExtractReading reading : readings) {
            VitalValueRules.assertPlausible(reading.metricType(), reading.value());
            VitalRecord row = new VitalRecord();
            row.setTenantId(tenantId);
            row.setPeopleId(peopleId);
            row.setOrgId(orgId);
            row.setMetricType(reading.metricType());
            row.setValue(reading.value());
            row.setUnit(reading.unit());
            row.setSource(VitalSourceEnum.STAFF.name());
            row.setGroupId(reading.groupId());
            row.setNote(reading.note());
            row.setRecordedByStaffId(staffId);
            row.setExtraJson(buildExtraJson(reading.mealContext(), reading.bpContext()));
            EntityMeta.onCreate(row);
            if (reading.recordedAt() == null) {
                throw new BusinessException("请补上测量时间");
            }
            row.setRecordedAt(reading.recordedAt());
            vitalRecordMapper.insert(row);
            written.add(row);
        }
        assessmentDirtyMarker.mark(tenantId, peopleId);
        WorkspaceTaskGenerator gen = workspaceTaskGenerator.getIfAvailable();
        if (gen != null) {
            gen.onVitalsWritten(tenantId, orgId, peopleId, written);
        }
        CarePlanMetricCheckinSync sync = metricCheckinSync.getIfAvailable();
        if (sync != null) {
            sync.onMetricsChanged(
                    tenantId,
                    peopleId,
                    written.stream().map(CarePlanMetricCheckinSync.MetricDay::of).toList(),
                    null);
        }
        return written;
    }

    public boolean existsAtMinute(String tenantId, String peopleId, String metricType, LocalDateTime recordedAt) {
        if (recordedAt == null) {
            return false;
        }
        LocalDateTime from = recordedAt.withSecond(0).withNano(0);
        return vitalRecordMapper.countAtMinute(tenantId, peopleId, metricType, from, from.plusMinutes(1)) > 0;
    }

    /** 健管师核对后写入的一条指标。 */
    public record StaffExtractReading(
            String metricType,
            BigDecimal value,
            String unit,
            LocalDateTime recordedAt,
            String groupId,
            String note,
            String mealContext,
            String bpContext) {}

    /** 患者一次测量中的一条指标。 */
    public record PatientReading(
            String metricType, BigDecimal value, String unit, String source, String mealContext, String bpContext) {}

    private String buildExtraJson(String mealContext, String bpContext) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (StringUtils.hasText(mealContext)) {
            extra.put("mealContext", mealContext.trim().toUpperCase());
        }
        if (StringUtils.hasText(bpContext)) {
            extra.put("bpContext", bpContext.trim().toUpperCase());
        }
        return extra.isEmpty() ? null : JsonUtils.toJson(extra);
    }

    public List<VitalRecord> list(
            String tenantId, String peopleId, String metricType, LocalDateTime from, LocalDateTime to) {
        return vitalRecordMapper.listByRange(tenantId, peopleId, metricType, from, to);
    }

    public Optional<Double> averageGlucoseLastDays(String tenantId, String peopleId, int days) {
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(days);
        return Optional.ofNullable(vitalRecordMapper.averageValue(
                tenantId, peopleId, MetricTypeEnum.BLOOD_GLUCOSE.name(), from, to));
    }

    public Optional<BigDecimal> latestStepsToday(String tenantId, String peopleId) {
        LocalDateTime from = LocalDate.now().atStartOfDay();
        LocalDateTime to = LocalDateTime.now();
        List<VitalRecord> list =
                vitalRecordMapper.listByRange(tenantId, peopleId, MetricTypeEnum.STEPS.name(), from, to);
        return list.stream().findFirst().map(VitalRecord::getValue);
    }
}
