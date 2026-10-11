package com.healix.core.vitals.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.vitals.domain.VitalRecord;
import com.healix.core.vitals.enums.MetricTypeEnum;
import com.healix.core.vitals.mapper.VitalRecordMapper;
import com.healix.core.worktask.service.WorkspaceTaskGenerator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final ObjectProvider<WorkspaceTaskGenerator> workspaceTaskGenerator;

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
        WorkspaceTaskGenerator gen = workspaceTaskGenerator.getIfAvailable();
        if (gen != null) {
            gen.onVitalsWritten(profile.getTenantId(), record.getOrgId(), peopleId, List.of(record));
        }
        return record;
    }

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
