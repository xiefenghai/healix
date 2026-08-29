package com.healix.core.vitals.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.vitals.domain.VitalRecord;
import com.healix.core.vitals.enums.MetricTypeEnum;
import com.healix.core.vitals.mapper.VitalRecordMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class VitalService {

    private final VitalRecordMapper vitalRecordMapper;
    private final PeopleProfileMapper peopleProfileMapper;

    @Transactional
    public VitalRecord record(
            String peopleId,
            String metricType,
            BigDecimal value,
            String unit,
            LocalDateTime recordedAt,
            String source) {
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
        EntityMeta.onCreate(record);
        record.setRecordedAt(recordedAt != null ? recordedAt : record.getGmtCreated());
        vitalRecordMapper.insert(record);
        return record;
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
