package com.healix.core.observation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.common.util.SnowflakeId;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.observation.dto.MetricLatestSlotDto;
import com.healix.core.observation.dto.MetricViewDto;
import com.healix.core.observation.enums.HealthDataSourceEnum;
import com.healix.core.revision.enums.RevisionBizTypeEnum;
import com.healix.core.revision.service.FieldRevisionService;
import com.healix.core.vitals.domain.VitalRecord;
import com.healix.core.vitals.enums.MetricTypeEnum;
import com.healix.core.vitals.enums.VitalSourceEnum;
import com.healix.core.vitals.mapper.VitalRecordMapper;
import com.healix.core.worktask.service.WorkspaceTaskGenerator;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class MetricService {

    private static final String OPERATOR_STAFF = "STAFF";
    private static final String OPERATOR_PEOPLE = "PEOPLE";
    private static final Set<String> KNOWN_TYPES = Set.of(
            MetricTypeEnum.BLOOD_PRESSURE_SYS.name(),
            MetricTypeEnum.BLOOD_PRESSURE_DIA.name(),
            MetricTypeEnum.BLOOD_GLUCOSE.name(),
            MetricTypeEnum.HEIGHT.name(),
            MetricTypeEnum.WEIGHT.name(),
            MetricTypeEnum.WAIST.name(),
            MetricTypeEnum.HEART_RATE.name(),
            MetricTypeEnum.TEMPERATURE.name(),
            MetricTypeEnum.STEPS.name(),
            MetricTypeEnum.SLEEP_HOURS.name());

    private final VitalRecordMapper vitalRecordMapper;
    private final ArchiveAccessService archiveAccessService;
    private final FieldRevisionService fieldRevisionService;
    private final ObjectProvider<WorkspaceTaskGenerator> workspaceTaskGenerator;

    public List<MetricViewDto> list(
            String tenantId,
            String orgId,
            String peopleId,
            String metricType,
            LocalDateTime from,
            LocalDateTime to,
            Integer limit) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        int lim = limit == null || limit <= 0 ? 100 : Math.min(limit, 500);
        return vitalRecordMapper.listByPeople(tenantId, peopleId, blankToNull(metricType), from, to, lim).stream()
                .map(this::toView)
                .toList();
    }

    /** C 端：患者查看本人指标历史（趋势图）。 */
    public List<MetricViewDto> listForPatient(
            String tenantId,
            String peopleId,
            String metricType,
            LocalDateTime from,
            LocalDateTime to,
            Integer limit) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        int lim = limit == null || limit <= 0 ? 100 : Math.min(limit, 500);
        return vitalRecordMapper.listByPeople(tenantId, peopleId, blankToNull(metricType), from, to, lim).stream()
                .map(this::toView)
                .toList();
    }

    public List<MetricLatestSlotDto> latest(String tenantId, String orgId, String peopleId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return buildLatestSlots(tenantId, peopleId);
    }

    /** C 端：患者查看本人各指标最近一次记录。 */
    public List<MetricLatestSlotDto> latestForPatient(String tenantId, String peopleId) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        return buildLatestSlots(tenantId, peopleId);
    }

    private List<MetricLatestSlotDto> buildLatestSlots(String tenantId, String peopleId) {
        List<VitalRecord> recent = vitalRecordMapper.listRecentForLatest(tenantId, peopleId, 200);
        Map<String, MetricLatestSlotDto> slots = new LinkedHashMap<>();
        for (VitalRecord row : recent) {
            String slotKey = buildSlotKey(row);
            if (slots.containsKey(slotKey)) {
                continue;
            }
            Map<String, Object> extra = parseExtra(row.getExtraJson());
            slots.put(
                    slotKey,
                    new MetricLatestSlotDto(
                            slotKey,
                            row.getMetricType(),
                            stringExtra(extra, "bpContext"),
                            stringExtra(extra, "mealContext"),
                            row.getValue(),
                            row.getUnit(),
                            row.getRecordedAt(),
                            row.getId(),
                            row.getGroupId(),
                            extra));
        }
        return new ArrayList<>(slots.values());
    }

    @Transactional
    public MetricViewDto create(
            String tenantId, String orgId, String peopleId, String staffId, MetricCommand cmd) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        VitalRecord row = buildRow(tenantId, orgId, peopleId, staffId, cmd, null);
        EntityMeta.onCreate(row);
        if (row.getRecordedAt() == null) {
            row.setRecordedAt(row.getGmtCreated());
        }
        vitalRecordMapper.insert(row);
        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                OPERATOR_STAFF,
                staffId,
                RevisionBizTypeEnum.METRIC.name(),
                row.getId(),
                0,
                1,
                orgId,
                "{}",
                toRevisionJson(row));
        notifyWorkspaceTasks(tenantId, orgId, peopleId, List.of(row));
        return toView(row);
    }

    /** 成组录入（血压成对、身高体重同组等）。 */
    @Transactional
    public List<MetricViewDto> createBatch(
            String tenantId, String orgId, String peopleId, String staffId, List<MetricCommand> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException("成组指标不能为空");
        }
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        String groupId = SnowflakeId.nextBizId();
        LocalDateTime recordedAt = items.stream()
                .map(MetricCommand::recordedAt)
                .filter(t -> t != null)
                .findFirst()
                .orElse(LocalDateTime.now());
        String note = items.stream().map(MetricCommand::note).filter(StringUtils::hasText).findFirst().orElse(null);
        List<MetricViewDto> out = new ArrayList<>();
        List<VitalRecord> written = new ArrayList<>();
        for (MetricCommand item : items) {
            MetricCommand normalized = new MetricCommand(
                    item.metricType(),
                    item.value(),
                    item.unit(),
                    item.recordedAt() != null ? item.recordedAt() : recordedAt,
                    item.bpContext(),
                    item.mealContext(),
                    note != null ? note : item.note(),
                    item.extra());
            VitalRecord row = buildRow(tenantId, orgId, peopleId, staffId, normalized, groupId);
            EntityMeta.onCreate(row);
            if (row.getRecordedAt() == null) {
                row.setRecordedAt(row.getGmtCreated());
            }
            vitalRecordMapper.insert(row);
            fieldRevisionService.recordIfChanged(
                    tenantId,
                    peopleId,
                    OPERATOR_STAFF,
                    staffId,
                    RevisionBizTypeEnum.METRIC.name(),
                    groupId,
                    0,
                    1,
                    orgId,
                    "{}",
                    toRevisionJson(row));
            written.add(row);
            out.add(toView(row));
        }
        notifyWorkspaceTasks(tenantId, orgId, peopleId, written);
        return out;
    }

    @Transactional
    public MetricViewDto update(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String metricId,
            MetricCommand cmd) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        VitalRecord row = requireOwned(tenantId, peopleId, metricId);
        String oldJson = toRevisionJson(row);
        applyCommand(row, cmd);
        row.setOrgId(orgId);
        row.setRecordedByStaffId(staffId);
        row.setSource(VitalSourceEnum.STAFF.name());
        EntityMeta.onUpdate(row);
        vitalRecordMapper.update(row);
        String bizKey = StringUtils.hasText(row.getGroupId()) ? row.getGroupId() : row.getId();
        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                OPERATOR_STAFF,
                staffId,
                RevisionBizTypeEnum.METRIC.name(),
                bizKey,
                1,
                1,
                orgId,
                oldJson,
                toRevisionJson(row));
        return toView(row);
    }

    /** C 端：用户修改自己上报的指标。 */
    @Transactional
    public MetricViewDto updateForPatient(
            String tenantId, String peopleId, String metricId, MetricCommand cmd) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        VitalRecord row = requireOwned(tenantId, peopleId, metricId);
        assertPatientOwned(row.getSource());
        String oldJson = toRevisionJson(row);
        applyCommand(row, cmd);
        EntityMeta.onUpdate(row);
        vitalRecordMapper.update(row);
        String bizKey = StringUtils.hasText(row.getGroupId()) ? row.getGroupId() : row.getId();
        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                OPERATOR_PEOPLE,
                peopleId,
                RevisionBizTypeEnum.METRIC.name(),
                bizKey,
                1,
                1,
                null,
                oldJson,
                toRevisionJson(row));
        return toView(row);
    }

    /** C 端：用户删除自己上报的指标；血压成对可按 group 删除。 */
    @Transactional
    public void deleteForPatient(String tenantId, String peopleId, String metricId, boolean deleteGroup) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        VitalRecord row = requireOwned(tenantId, peopleId, metricId);
        assertPatientOwned(row.getSource());
        if (deleteGroup && StringUtils.hasText(row.getGroupId())) {
            List<VitalRecord> groupRows = vitalRecordMapper.listByGroupId(row.getGroupId());
            for (VitalRecord g : groupRows) {
                assertPatientOwned(g.getSource());
            }
        }
        String snapshot = toRevisionJson(row);
        String bizKey = StringUtils.hasText(row.getGroupId()) ? row.getGroupId() : row.getId();
        if (deleteGroup && StringUtils.hasText(row.getGroupId())) {
            vitalRecordMapper.softDeleteByGroupId(row.getGroupId());
        } else {
            vitalRecordMapper.softDelete(metricId);
        }
        fieldRevisionService.recordDeleted(
                tenantId,
                peopleId,
                OPERATOR_PEOPLE,
                peopleId,
                RevisionBizTypeEnum.METRIC.name(),
                bizKey,
                null,
                snapshot);
    }

    private void assertPatientOwned(String source) {
        if (!HealthDataSourceEnum.isPatientOwned(source)) {
            throw new BusinessException("仅可修改自己上报的数据");
        }
    }

    @Transactional
    public void delete(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String metricId,
            boolean deleteGroup) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        VitalRecord row = requireOwned(tenantId, peopleId, metricId);
        String snapshot = toRevisionJson(row);
        String bizKey = StringUtils.hasText(row.getGroupId()) ? row.getGroupId() : row.getId();
        if (deleteGroup && StringUtils.hasText(row.getGroupId())) {
            vitalRecordMapper.softDeleteByGroupId(row.getGroupId());
        } else {
            vitalRecordMapper.softDelete(metricId);
        }
        fieldRevisionService.recordDeleted(
                tenantId,
                peopleId,
                OPERATOR_STAFF,
                staffId,
                RevisionBizTypeEnum.METRIC.name(),
                bizKey,
                orgId,
                snapshot);
    }

    private VitalRecord buildRow(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            MetricCommand cmd,
            String groupId) {
        validateCommand(cmd);
        VitalRecord row = new VitalRecord();
        row.setTenantId(tenantId);
        row.setPeopleId(peopleId);
        row.setOrgId(orgId);
        row.setSource(VitalSourceEnum.STAFF.name());
        row.setGroupId(groupId);
        row.setRecordedByStaffId(staffId);
        applyCommand(row, cmd);
        return row;
    }

    private void applyCommand(VitalRecord row, MetricCommand cmd) {
        validateCommand(cmd);
        row.setMetricType(cmd.metricType().trim());
        row.setValue(cmd.value());
        row.setUnit(blankToNull(cmd.unit()));
        row.setRecordedAt(cmd.recordedAt());
        row.setNote(blankToNull(cmd.note()));
        row.setExtraJson(buildExtraJson(cmd));
    }

    private void validateCommand(MetricCommand cmd) {
        if (cmd == null || !StringUtils.hasText(cmd.metricType())) {
            throw new BusinessException("指标类型不能为空");
        }
        if (!KNOWN_TYPES.contains(cmd.metricType().trim())) {
            throw new BusinessException("不支持的指标类型: " + cmd.metricType());
        }
        if (cmd.value() == null) {
            throw new BusinessException("指标值不能为空");
        }
    }

    private String buildExtraJson(MetricCommand cmd) {
        Map<String, Object> extra = new HashMap<>();
        if (cmd.extra() != null) {
            extra.putAll(cmd.extra());
        }
        if (StringUtils.hasText(cmd.bpContext())) {
            extra.put("bpContext", cmd.bpContext().trim().toUpperCase());
        }
        if (StringUtils.hasText(cmd.mealContext())) {
            extra.put("mealContext", cmd.mealContext().trim().toUpperCase());
        }
        if (extra.isEmpty()) {
            return null;
        }
        return JsonUtils.toJson(extra);
    }

    private VitalRecord requireOwned(String tenantId, String peopleId, String metricId) {
        VitalRecord row = vitalRecordMapper.findById(metricId);
        if (row == null
                || !tenantId.equals(row.getTenantId())
                || !peopleId.equals(row.getPeopleId())) {
            throw new BusinessException("指标记录不存在");
        }
        return row;
    }

    private MetricViewDto toView(VitalRecord row) {
        return new MetricViewDto(
                row.getId(),
                row.getPeopleId(),
                row.getOrgId(),
                row.getMetricType(),
                row.getValue(),
                row.getUnit(),
                row.getRecordedAt(),
                row.getSource(),
                row.getGroupId(),
                row.getNote(),
                row.getRecordedByStaffId(),
                parseExtra(row.getExtraJson()),
                row.getGmtCreated());
    }

    private String toRevisionJson(VitalRecord row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metricType", row.getMetricType());
        m.put("value", row.getValue());
        m.put("unit", row.getUnit());
        m.put("recordedAt", row.getRecordedAt() == null ? null : row.getRecordedAt().toString());
        m.put("source", row.getSource());
        m.put("groupId", row.getGroupId());
        m.put("note", row.getNote());
        m.put("extra", parseExtra(row.getExtraJson()));
        return JsonUtils.toJson(m);
    }

    private static String buildSlotKey(VitalRecord row) {
        Map<String, Object> extra = parseExtra(row.getExtraJson());
        String type = row.getMetricType();
        if (MetricTypeEnum.BLOOD_PRESSURE_SYS.matches(type)
                || MetricTypeEnum.BLOOD_PRESSURE_DIA.matches(type)) {
            String bp = stringExtra(extra, "bpContext");
            if (!StringUtils.hasText(bp)) {
                bp = "CLINIC";
            }
            return type + ":" + bp;
        }
        if (MetricTypeEnum.BLOOD_GLUCOSE.matches(type)) {
            String meal = stringExtra(extra, "mealContext");
            if (StringUtils.hasText(meal)) {
                return type + ":" + meal;
            }
        }
        return type;
    }

    private static Map<String, Object> parseExtra(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        Map<String, Object> map = JsonUtils.fromJson(json, new TypeReference<>() {});
        return map == null ? Map.of() : map;
    }

    private static String stringExtra(Map<String, Object> extra, String key) {
        Object v = extra.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    private void notifyWorkspaceTasks(String tenantId, String orgId, String peopleId, List<VitalRecord> written) {
        WorkspaceTaskGenerator gen = workspaceTaskGenerator.getIfAvailable();
        if (gen != null) {
            gen.onVitalsWritten(tenantId, orgId, peopleId, written);
        }
    }

    public record MetricCommand(
            String metricType,
            BigDecimal value,
            String unit,
            LocalDateTime recordedAt,
            String bpContext,
            String mealContext,
            String note,
            Map<String, Object> extra) {}
}
