package com.healix.core.medication.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.common.util.SnowflakeId;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.medication.domain.PeopleMedication;
import com.healix.core.medication.domain.PeopleMedicationIntake;
import com.healix.core.medication.dto.MedicationIntakeViewDto;
import com.healix.core.medication.dto.MedicationViewDto;
import com.healix.core.medication.enums.MedicationIntakeStatusEnum;
import com.healix.core.medication.enums.MedicationSourceEnum;
import com.healix.core.medication.enums.MedicationStatusEnum;
import com.healix.core.medication.enums.MedicationTimeSlotEnum;
import com.healix.core.medication.mapper.PeopleMedicationIntakeMapper;
import com.healix.core.medication.mapper.PeopleMedicationMapper;
import com.healix.core.medication.support.MedicationFrequencySupport;
import com.healix.core.revision.enums.RevisionBizTypeEnum;
import com.healix.core.revision.service.FieldRevisionService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class MedicationService {

    private static final String OPERATOR_STAFF = "STAFF";
    private static final String OPERATOR_PEOPLE = "PEOPLE";

    private final PeopleMedicationMapper medicationMapper;
    private final PeopleMedicationIntakeMapper intakeMapper;
    private final ArchiveAccessService archiveAccessService;
    private final FieldRevisionService fieldRevisionService;

    @Transactional
    public List<MedicationViewDto> list(String tenantId, String orgId, String peopleId, String status) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return listViews(tenantId, peopleId, status);
    }

    /** C 端：当前就诊人用药清单（不校验机构员工权限）。 */
    @Transactional
    public List<MedicationViewDto> listForPatient(String tenantId, String peopleId, String status) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        return listViews(tenantId, peopleId, status);
    }

    /**
     * 列出用药并按停药日/疗程做惰性过期：已过结束日的 ACTIVE 会落库为 STOPPED，再按 status 过滤。
     * 这样「当前用药 / 历史用药」不依赖定时任务。
     */
    private List<MedicationViewDto> listViews(String tenantId, String peopleId, String status) {
        String filter = blankToNull(status);
        // 先拉全量（或至少含 ACTIVE），才能把过期在用药改成 STOPPED；否则按 ACTIVE 查会漏掉应进入历史的记录。
        List<PeopleMedication> rows = medicationMapper.listByPeople(tenantId, peopleId, null);
        for (PeopleMedication row : rows) {
            expireIfNeeded(row);
        }
        return rows.stream()
                .filter(row -> filter == null || filter.equalsIgnoreCase(row.getStatus()))
                .map(this::toView)
                .toList();
    }

    public List<MedicationIntakeViewDto> listIntakesByDateForPatient(
            String tenantId, String peopleId, LocalDate intakeDate) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        LocalDate day = intakeDate != null ? intakeDate : LocalDate.now();
        List<PeopleMedicationIntake> rows = intakeMapper.listByPeopleDate(tenantId, peopleId, day);
        List<MedicationIntakeViewDto> out = new ArrayList<>();
        for (PeopleMedicationIntake row : rows) {
            PeopleMedication med = medicationMapper.findById(row.getMedicationId());
            out.add(toIntakeView(row, med == null ? null : med.getDrugName()));
        }
        return out;
    }

    /** C 端患者自助服药打卡。 */
    @Transactional
    public MedicationIntakeViewDto upsertIntakeByPatient(
            String tenantId, String peopleId, String medicationId, IntakeCommand cmd) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        PeopleMedication med = requireOwned(tenantId, peopleId, medicationId);
        if (cmd.intakeDate() == null) {
            throw new BusinessException("服药日期不能为空");
        }
        String slot = resolveTimeSlot(cmd.timeSlot());
        String status = resolveIntakeStatus(cmd.status());
        PeopleMedicationIntake existing =
                intakeMapper.findByMedDateSlot(medicationId, cmd.intakeDate(), slot);
        if (existing == null) {
            PeopleMedicationIntake row = new PeopleMedicationIntake();
            row.setTenantId(tenantId);
            row.setPeopleId(peopleId);
            row.setMedicationId(medicationId);
            row.setIntakeDate(cmd.intakeDate());
            row.setTimeSlot(slot);
            row.setStatus(status);
            row.setNote(trimToNull(cmd.note(), 200));
            row.setRecordedByStaffId(null);
            EntityMeta.onCreate(row);
            intakeMapper.insert(row);
            return toIntakeView(row, med.getDrugName());
        }
        existing.setStatus(status);
        existing.setNote(trimToNull(cmd.note(), 200));
        EntityMeta.onUpdate(existing);
        intakeMapper.update(existing);
        return toIntakeView(existing, med.getDrugName());
    }

    @Transactional
    public MedicationViewDto get(String tenantId, String orgId, String peopleId, String medicationId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        PeopleMedication row = requireOwned(tenantId, peopleId, medicationId);
        expireIfNeeded(row);
        return toView(row);
    }

    @Transactional
    public MedicationViewDto create(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            MedicationCommand cmd,
            String source,
            String prescriptionGroupId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        validateCommand(cmd);
        PeopleMedication row = new PeopleMedication();
        row.setTenantId(tenantId);
        row.setPeopleId(peopleId);
        row.setOrgId(orgId);
        row.setPrescriptionGroupId(prescriptionGroupId);
        row.setSource(resolveSource(source));
        applyCommand(row, cmd);
        row.setStatus(resolveStatus(row.getStopDate(), cmd.status()));
        row.setCreatedByStaffId(staffId);
        row.setUpdatedByStaffId(staffId);
        EntityMeta.onCreate(row);
        medicationMapper.insert(row);
        String snapshot = toRevisionJson(row);
        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                OPERATOR_STAFF,
                staffId,
                RevisionBizTypeEnum.MEDICATION.name(),
                row.getId(),
                0,
                1,
                orgId,
                "{}",
                snapshot);
        return toView(row);
    }

    /** C 端：患者自助添加用药。 */
    @Transactional
    public MedicationViewDto createByPatient(String tenantId, String peopleId, MedicationCommand cmd) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        validateCommand(cmd);
        PeopleMedication row = new PeopleMedication();
        row.setTenantId(tenantId);
        row.setPeopleId(peopleId);
        row.setOrgId(null);
        row.setPrescriptionGroupId(null);
        row.setSource(MedicationSourceEnum.PATIENT.name());
        applyCommand(row, cmd);
        row.setStatus(resolveStatus(row.getStopDate(), cmd.status()));
        row.setCreatedByStaffId(null);
        row.setUpdatedByStaffId(null);
        EntityMeta.onCreate(row);
        medicationMapper.insert(row);
        String snapshot = toRevisionJson(row);
        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                OPERATOR_PEOPLE,
                peopleId,
                RevisionBizTypeEnum.MEDICATION.name(),
                row.getId(),
                0,
                1,
                null,
                "{}",
                snapshot);
        return toView(row);
    }

    /** C 端：患者修改本人用药。 */
    @Transactional
    public MedicationViewDto updateByPatient(
            String tenantId, String peopleId, String medicationId, MedicationCommand cmd) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        validateCommand(cmd);
        PeopleMedication row = requireOwned(tenantId, peopleId, medicationId);
        String oldJson = toRevisionJson(row);
        applyCommand(row, cmd);
        row.setStatus(resolveStatus(row.getStopDate(), cmd.status()));
        row.setUpdatedByStaffId(null);
        EntityMeta.onUpdate(row);
        medicationMapper.update(row);
        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                OPERATOR_PEOPLE,
                peopleId,
                RevisionBizTypeEnum.MEDICATION.name(),
                medicationId,
                1,
                1,
                null,
                oldJson,
                toRevisionJson(row));
        return toView(row);
    }

    /** 一次开立多条药品（处方），共用 prescription_group_id。 */
    @Transactional
    public List<MedicationViewDto> createPrescription(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            List<MedicationCommand> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException("处方药品不能为空");
        }
        String groupId = SnowflakeId.nextBizId();
        List<MedicationViewDto> out = new ArrayList<>();
        for (MedicationCommand item : items) {
            out.add(create(
                    tenantId,
                    orgId,
                    peopleId,
                    staffId,
                    item,
                    MedicationSourceEnum.PRESCRIPTION.name(),
                    groupId));
        }
        return out;
    }

    @Transactional
    public MedicationViewDto update(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String medicationId,
            MedicationCommand cmd) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        validateCommand(cmd);
        PeopleMedication row = requireOwned(tenantId, peopleId, medicationId);
        String oldJson = toRevisionJson(row);
        applyCommand(row, cmd);
        row.setStatus(resolveStatus(row.getStopDate(), cmd.status()));
        row.setUpdatedByStaffId(staffId);
        EntityMeta.onUpdate(row);
        medicationMapper.update(row);
        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                OPERATOR_STAFF,
                staffId,
                RevisionBizTypeEnum.MEDICATION.name(),
                medicationId,
                1,
                1,
                orgId,
                oldJson,
                toRevisionJson(row));
        return toView(row);
    }

    @Transactional
    public void delete(String tenantId, String orgId, String peopleId, String staffId, String medicationId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        PeopleMedication row = requireOwned(tenantId, peopleId, medicationId);
        String snapshot = toRevisionJson(row);
        medicationMapper.softDelete(medicationId, staffId);
        fieldRevisionService.recordDeleted(
                tenantId,
                peopleId,
                OPERATOR_STAFF,
                staffId,
                RevisionBizTypeEnum.MEDICATION.name(),
                medicationId,
                orgId,
                snapshot);
    }

    public List<MedicationIntakeViewDto> listIntakes(
            String tenantId,
            String orgId,
            String peopleId,
            String medicationId,
            LocalDate from,
            LocalDate to) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        PeopleMedication med = requireOwned(tenantId, peopleId, medicationId);
        return intakeMapper.listByMedication(medicationId, from, to).stream()
                .map(row -> toIntakeView(row, med.getDrugName()))
                .toList();
    }

    public List<MedicationIntakeViewDto> listIntakesByDate(
            String tenantId, String orgId, String peopleId, LocalDate intakeDate) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        LocalDate day = intakeDate != null ? intakeDate : LocalDate.now();
        List<PeopleMedicationIntake> rows = intakeMapper.listByPeopleDate(tenantId, peopleId, day);
        List<MedicationIntakeViewDto> out = new ArrayList<>();
        for (PeopleMedicationIntake row : rows) {
            PeopleMedication med = medicationMapper.findById(row.getMedicationId());
            out.add(toIntakeView(row, med == null ? null : med.getDrugName()));
        }
        return out;
    }

    /** 记录或更新某日某时段依从性（幂等 upsert）。 */
    @Transactional
    public MedicationIntakeViewDto upsertIntake(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String medicationId,
            IntakeCommand cmd) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        PeopleMedication med = requireOwned(tenantId, peopleId, medicationId);
        if (cmd.intakeDate() == null) {
            throw new BusinessException("服药日期不能为空");
        }
        String slot = resolveTimeSlot(cmd.timeSlot());
        String status = resolveIntakeStatus(cmd.status());
        PeopleMedicationIntake existing =
                intakeMapper.findByMedDateSlot(medicationId, cmd.intakeDate(), slot);
        if (existing == null) {
            PeopleMedicationIntake row = new PeopleMedicationIntake();
            row.setTenantId(tenantId);
            row.setPeopleId(peopleId);
            row.setMedicationId(medicationId);
            row.setIntakeDate(cmd.intakeDate());
            row.setTimeSlot(slot);
            row.setStatus(status);
            row.setNote(trimToNull(cmd.note(), 200));
            row.setRecordedByStaffId(staffId);
            EntityMeta.onCreate(row);
            intakeMapper.insert(row);
            fieldRevisionService.recordIfChanged(
                    tenantId,
                    peopleId,
                    OPERATOR_STAFF,
                    staffId,
                    RevisionBizTypeEnum.MEDICATION_INTAKE.name(),
                    medicationId,
                    0,
                    1,
                    orgId,
                    "{}",
                    toIntakeRevisionJson(row, med.getDrugName()));
            return toIntakeView(row, med.getDrugName());
        }
        String oldJson = toIntakeRevisionJson(existing, med.getDrugName());
        existing.setStatus(status);
        existing.setNote(trimToNull(cmd.note(), 200));
        existing.setRecordedByStaffId(staffId);
        EntityMeta.onUpdate(existing);
        intakeMapper.update(existing);
        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                OPERATOR_STAFF,
                staffId,
                RevisionBizTypeEnum.MEDICATION_INTAKE.name(),
                medicationId,
                1,
                1,
                orgId,
                oldJson,
                toIntakeRevisionJson(existing, med.getDrugName()));
        return toIntakeView(existing, med.getDrugName());
    }

    private static String toRevisionJson(PeopleMedication row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("drugName", row.getDrugName());
        m.put("usageMethod", row.getUsageMethod());
        m.put("frequency", row.getFrequency());
        m.put("doseAmount", row.getDoseAmount());
        m.put("doseUnit", row.getDoseUnit());
        m.put("startDate", row.getStartDate() == null ? null : row.getStartDate().toString());
        m.put("stopDate", row.getStopDate() == null ? null : row.getStopDate().toString());
        m.put("timingNote", row.getTimingNote());
        m.put("courseDays", row.getCourseDays());
        m.put("hasAdverseReaction", row.getHasAdverseReaction() != null && row.getHasAdverseReaction() == 1);
        m.put("remark", row.getRemark());
        m.put("status", row.getStatus());
        m.put("source", row.getSource());
        return JsonUtils.toJson(m);
    }

    private static String toIntakeRevisionJson(PeopleMedicationIntake row, String drugName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("drugName", drugName);
        m.put("intakeDate", row.getIntakeDate() == null ? null : row.getIntakeDate().toString());
        m.put("timeSlot", row.getTimeSlot());
        m.put("intakeStatus", row.getStatus());
        m.put("note", row.getNote());
        return JsonUtils.toJson(m);
    }

    private PeopleMedication requireOwned(String tenantId, String peopleId, String medicationId) {
        PeopleMedication row = medicationMapper.findById(medicationId);
        if (row == null) {
            throw new BusinessException(404, "用药记录不存在");
        }
        if (!tenantId.equals(row.getTenantId()) || !peopleId.equals(row.getPeopleId())) {
            throw new BusinessException(403, "无权访问该用药记录");
        }
        return row;
    }

    private void validateCommand(MedicationCommand cmd) {
        if (cmd == null || !StringUtils.hasText(cmd.drugName())) {
            throw new BusinessException("药品名称不能为空");
        }
        if (!StringUtils.hasText(cmd.usageMethod())) {
            throw new BusinessException("药品用法不能为空");
        }
        if (cmd.startDate() != null && cmd.stopDate() != null && cmd.stopDate().isBefore(cmd.startDate())) {
            throw new BusinessException("停药时间不能早于开始服药时间");
        }
        if (cmd.remark() != null && cmd.remark().length() > 150) {
            throw new BusinessException("备注最多 150 字");
        }
    }

    private void applyCommand(PeopleMedication row, MedicationCommand cmd) {
        row.setDrugName(cmd.drugName().trim());
        row.setUsageMethod(cmd.usageMethod().trim());
        row.setFrequency(trimToNull(cmd.frequency(), 128));
        row.setDoseAmount(trimToNull(cmd.doseAmount(), 32));
        row.setDoseUnit(trimToNull(cmd.doseUnit(), 32));
        row.setStartDate(cmd.startDate());
        row.setStopDate(cmd.stopDate());
        row.setTimingNote(trimToNull(cmd.timingNote(), 128));
        row.setCourseDays(cmd.courseDays());
        // 固定疗程未显式给停药日时，按开始日 + 疗程推算结束日（含首尾）
        if (row.getStopDate() == null
                && row.getCourseDays() != null
                && row.getCourseDays() > 0
                && row.getStartDate() != null) {
            row.setStopDate(row.getStartDate().plusDays(row.getCourseDays() - 1L));
        }
        // 旧四时段字段不再由表单写入
        row.setTimeMorning(null);
        row.setTimeNoon(null);
        row.setTimeEvening(null);
        row.setTimeBedtime(null);
        row.setHasAdverseReaction(Boolean.TRUE.equals(cmd.hasAdverseReaction()) ? 1 : 0);
        row.setRemark(trimToNull(cmd.remark(), 150));
    }

    private String resolveSource(String source) {
        if (!StringUtils.hasText(source)) {
            return MedicationSourceEnum.MANUAL.name();
        }
        try {
            return MedicationSourceEnum.valueOf(source.trim().toUpperCase()).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("无效的用药来源");
        }
    }

    /**
     * 停药日/疗程结束日为闭区间最后一天：当天仍算在用，次日及以后为已停用。
     * 显式传入 STOPPED 始终尊重；显式 ACTIVE 若已过结束日则仍落为 STOPPED。
     */
    private String resolveStatus(LocalDate stopDate, String status) {
        LocalDate today = LocalDate.now();
        if (stopDate != null && stopDate.isBefore(today)) {
            return MedicationStatusEnum.STOPPED.name();
        }
        if (StringUtils.hasText(status)) {
            try {
                return MedicationStatusEnum.valueOf(status.trim().toUpperCase()).name();
            } catch (IllegalArgumentException ex) {
                throw new BusinessException("无效的用药状态");
            }
        }
        return MedicationStatusEnum.ACTIVE.name();
    }

    /** 用药结束日：优先 stopDate；否则 startDate + courseDays - 1。 */
    public static LocalDate resolveEndDate(PeopleMedication row) {
        if (row == null) {
            return null;
        }
        if (row.getStopDate() != null) {
            return row.getStopDate();
        }
        if (row.getCourseDays() != null && row.getCourseDays() > 0 && row.getStartDate() != null) {
            return row.getStartDate().plusDays(row.getCourseDays() - 1L);
        }
        return null;
    }

    /** 是否已过用药结束日（结束日当天仍有效）。 */
    public static boolean isPastEndDate(PeopleMedication row, LocalDate today) {
        LocalDate end = resolveEndDate(row);
        return end != null && end.isBefore(today);
    }

    /** ACTIVE 且已过结束日 → 落库 STOPPED（惰性过期）。 */
    private void expireIfNeeded(PeopleMedication row) {
        if (row == null || !MedicationStatusEnum.ACTIVE.name().equals(row.getStatus())) {
            return;
        }
        if (!isPastEndDate(row, LocalDate.now())) {
            return;
        }
        row.setStatus(MedicationStatusEnum.STOPPED.name());
        EntityMeta.onUpdate(row);
        medicationMapper.update(row);
    }

    private String resolveTimeSlot(String timeSlot) {
        if (!StringUtils.hasText(timeSlot)) {
            throw new BusinessException("服药时段不能为空");
        }
        try {
            return MedicationTimeSlotEnum.valueOf(timeSlot.trim().toUpperCase()).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("无效的服药时段");
        }
    }

    private String resolveIntakeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            throw new BusinessException("依从性状态不能为空");
        }
        try {
            return MedicationIntakeStatusEnum.valueOf(status.trim().toUpperCase()).name();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("无效的依从性状态");
        }
    }

    private MedicationViewDto toView(PeopleMedication row) {
        MedicationViewDto dto = new MedicationViewDto();
        dto.setId(row.getId());
        dto.setPeopleId(row.getPeopleId());
        dto.setPrescriptionGroupId(row.getPrescriptionGroupId());
        dto.setSource(row.getSource());
        dto.setDrugName(row.getDrugName());
        dto.setUsageMethod(row.getUsageMethod());
        dto.setFrequency(row.getFrequency());
        dto.setDoseAmount(row.getDoseAmount());
        dto.setDoseUnit(row.getDoseUnit());
        dto.setStartDate(row.getStartDate());
        dto.setStopDate(row.getStopDate());
        dto.setTimingNote(row.getTimingNote());
        dto.setCourseDays(row.getCourseDays());
        dto.setHasAdverseReaction(row.getHasAdverseReaction() != null && row.getHasAdverseReaction() == 1);
        dto.setRemark(row.getRemark());
        dto.setStatus(row.getStatus());
        dto.setPrn(MedicationFrequencySupport.isPrn(row.getFrequency()));
        dto.setDueDoseCount(MedicationFrequencySupport.dosesPerDay(row.getFrequency()));
        dto.setSuggestedSlots(MedicationFrequencySupport.suggestedSlots(row.getFrequency()));
        dto.setGmtCreated(row.getGmtCreated());
        dto.setGmtModified(row.getGmtModified());
        return dto;
    }

    private MedicationIntakeViewDto toIntakeView(PeopleMedicationIntake row, String drugName) {
        MedicationIntakeViewDto dto = new MedicationIntakeViewDto();
        dto.setId(row.getId());
        dto.setMedicationId(row.getMedicationId());
        dto.setDrugName(drugName);
        dto.setIntakeDate(row.getIntakeDate());
        dto.setTimeSlot(row.getTimeSlot());
        dto.setStatus(row.getStatus());
        dto.setNote(row.getNote());
        dto.setGmtCreated(row.getGmtCreated());
        return dto;
    }

    private static String blankToNull(String v) {
        return StringUtils.hasText(v) ? v.trim() : null;
    }

    private static String trimToNull(String v, int max) {
        if (!StringUtils.hasText(v)) {
            return null;
        }
        String t = v.trim();
        if (t.length() > max) {
            throw new BusinessException("字段长度不能超过 " + max);
        }
        return t;
    }

    public record MedicationCommand(
            String drugName,
            String usageMethod,
            String frequency,
            String doseAmount,
            String doseUnit,
            LocalDate startDate,
            LocalDate stopDate,
            String timingNote,
            Integer courseDays,
            Boolean hasAdverseReaction,
            String remark,
            String status) {}

    public record IntakeCommand(LocalDate intakeDate, String timeSlot, String status, String note) {}
}
