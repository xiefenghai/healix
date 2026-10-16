package com.healix.core.medication.service;

import com.healix.core.assessment.service.AssessmentDirtyMarker;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.common.util.SnowflakeId;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.medication.domain.PeopleMedication;
import com.healix.core.medication.domain.PeopleMedicationIntake;
import com.healix.core.medication.dto.MedicationIntakeViewDto;
import com.healix.core.medication.dto.MedicationViewDto;
import com.healix.core.medication.enums.MedicationFeedbackKindEnum;
import com.healix.core.medication.enums.MedicationIntakeStatusEnum;
import com.healix.core.medication.enums.MedicationSourceEnum;
import com.healix.core.medication.enums.MedicationStatusEnum;
import com.healix.core.medication.enums.MedicationStopReasonEnum;
import com.healix.core.medication.enums.MedicationTimeSlotEnum;
import com.healix.core.medication.mapper.PeopleMedicationIntakeMapper;
import com.healix.core.medication.mapper.PeopleMedicationMapper;
import com.healix.core.medication.support.MedicationSlotRules;
import com.healix.core.revision.enums.RevisionBizTypeEnum;
import com.healix.core.revision.service.FieldRevisionService;
import com.healix.core.worktask.service.WorkspaceTaskGenerator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final AssessmentDirtyMarker assessmentDirtyMarker;
    private final PeopleMedicationIntakeMapper intakeMapper;
    private final ArchiveAccessService archiveAccessService;
    private final FieldRevisionService fieldRevisionService;
    private final MedicationAllergyGuard allergyGuard;
    private final MedicationFeedbackService feedbackService;
    private final WorkspaceTaskGenerator workspaceTaskGenerator;

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
        Map<String, Integer> openFeedback = feedbackService.openCountByMedication(tenantId, peopleId);
        return rows.stream()
                .filter(row -> filter == null || filter.equalsIgnoreCase(row.getStatus()))
                .map(row -> {
                    MedicationViewDto dto = toView(row);
                    dto.setOpenFeedbackCount(openFeedback.getOrDefault(row.getId(), 0));
                    return dto;
                })
                .toList();
    }

    /** C 端：单条用药详情。 */
    @Transactional
    public MedicationViewDto getForPatient(String tenantId, String peopleId, String medicationId) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        PeopleMedication row = requireOwned(tenantId, peopleId, medicationId);
        expireIfNeeded(row);
        MedicationViewDto dto = toView(row);
        dto.setOpenFeedbackCount(
                feedbackService.openCountByMedication(tenantId, peopleId).getOrDefault(medicationId, 0));
        return dto;
    }

    public List<MedicationIntakeViewDto> listIntakesByDateForPatient(
            String tenantId, String peopleId, LocalDate intakeDate) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        LocalDate day = intakeDate != null ? intakeDate : MedicationScheduleService.today();
        List<PeopleMedicationIntake> rows = intakeMapper.listByPeopleDate(tenantId, peopleId, day);
        List<MedicationIntakeViewDto> out = new ArrayList<>();
        for (PeopleMedicationIntake row : rows) {
            PeopleMedication med = medicationMapper.findById(row.getMedicationId());
            out.add(toIntakeView(row, med == null ? null : med.getDrugName()));
        }
        return out;
    }

    /** C 端患者自助服药打卡（定时药；今天或昨天）。 */
    @Transactional
    public MedicationIntakeViewDto upsertIntakeByPatient(
            String tenantId, String peopleId, String medicationId, IntakeCommand cmd) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        PeopleMedication med = requireOwned(tenantId, peopleId, medicationId);
        return upsertPatientIntake(med, cmd);
    }

    /** C 端：某时段批量打卡（「全部已服」）。 */
    @Transactional
    public List<MedicationIntakeViewDto> batchIntakeByPatient(
            String tenantId, String peopleId, List<String> medicationIds, IntakeCommand cmd) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        if (medicationIds == null || medicationIds.isEmpty()) {
            throw new BusinessException("请选择药品");
        }
        List<MedicationIntakeViewDto> out = new ArrayList<>();
        for (String id : medicationIds.stream().distinct().toList()) {
            out.add(upsertPatientIntake(requireOwned(tenantId, peopleId, id), cmd));
        }
        return out;
    }

    private MedicationIntakeViewDto upsertPatientIntake(PeopleMedication med, IntakeCommand cmd) {
        if (cmd == null || cmd.intakeDate() == null) {
            throw new BusinessException("服药日期不能为空");
        }
        LocalDate day = cmd.intakeDate();
        if (!MedicationScheduleService.isEditableDay(day)) {
            throw new BusinessException("只能记录今天和昨天的服药");
        }
        if (MedicationSlotRules.isPrn(med)) {
            throw new BusinessException("需要时服用的药请用「记录一次」");
        }
        List<String> slots = MedicationSlotRules.slotsOf(med);
        String slot = resolveTimeSlot(cmd.timeSlot());
        if (!slots.contains(slot)) {
            throw new BusinessException("该药没有「" + MedicationSlotRules.label(slot) + "」时段");
        }
        String status = resolveIntakeStatus(cmd.status());
        LocalDate today = MedicationScheduleService.today();
        boolean backfill = day.isBefore(today);
        LocalDateTime takenAt = null;
        if (MedicationIntakeStatusEnum.TAKEN.name().equals(status)) {
            takenAt = cmd.takenAt() != null ? cmd.takenAt() : (backfill ? null : LocalDateTime.now(JobCronSupport.ZONE));
            if (takenAt != null && !takenAt.toLocalDate().equals(day)) {
                throw new BusinessException("服药时间需在当天");
            }
        }
        PeopleMedicationIntake existing = findSlotIntake(med, day, slot, slots);
        if (existing == null) {
            PeopleMedicationIntake row = new PeopleMedicationIntake();
            row.setTenantId(med.getTenantId());
            row.setPeopleId(med.getPeopleId());
            row.setMedicationId(med.getId());
            row.setIntakeDate(day);
            row.setTimeSlot(slot);
            row.setDoseSeq(1);
            row.setStatus(status);
            row.setTakenAt(takenAt);
            row.setBackfill(backfill ? 1 : 0);
            row.setNote(trimToNull(cmd.note(), 200));
            row.setRecordedByStaffId(null);
            EntityMeta.onCreate(row);
            intakeMapper.insert(row);
            return toIntakeView(row, med.getDrugName());
        }
        existing.setStatus(status);
        existing.setTakenAt(takenAt);
        existing.setBackfill(backfill ? 1 : 0);
        existing.setNote(trimToNull(cmd.note(), 200));
        existing.setRecordedByStaffId(null);
        EntityMeta.onUpdate(existing);
        intakeMapper.update(existing);
        return toIntakeView(existing, med.getDrugName());
    }

    /** 时段打卡行；兼容单时段药历史写在 OTHER 槽的记录（顺手迁到正确时段）。 */
    private PeopleMedicationIntake findSlotIntake(PeopleMedication med, LocalDate day, String slot, List<String> slots) {
        PeopleMedicationIntake row = intakeMapper.findByMedDateSlot(med.getId(), day, slot);
        if (row != null || slots.size() != 1) {
            return row;
        }
        PeopleMedicationIntake legacy = intakeMapper.findByMedDateSlot(med.getId(), day, MedicationSlotRules.OTHER);
        if (legacy != null) {
            intakeMapper.updateSlot(legacy.getId(), slot);
            legacy.setTimeSlot(slot);
        }
        return legacy;
    }

    /** C 端：需要时服用的药记录一次。 */
    @Transactional
    public MedicationIntakeViewDto addPrnDoseByPatient(
            String tenantId, String peopleId, String medicationId, LocalDate date, LocalDateTime takenAt) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        PeopleMedication med = requireOwned(tenantId, peopleId, medicationId);
        if (!MedicationSlotRules.isPrn(med)) {
            throw new BusinessException("该药为定时服用，请按时段打卡");
        }
        LocalDate day = date != null ? date : MedicationScheduleService.today();
        if (!MedicationScheduleService.isEditableDay(day)) {
            throw new BusinessException("只能记录今天和昨天的服药");
        }
        String slot = MedicationSlotRules.PRN;
        if (intakeMapper.countByMedDateSlot(medicationId, day, slot) >= MedicationScheduleService.PRN_DAILY_MAX) {
            throw new BusinessException("今日已记录 " + MedicationScheduleService.PRN_DAILY_MAX + " 次，如仍需服用请先咨询健管师");
        }
        boolean backfill = day.isBefore(MedicationScheduleService.today());
        LocalDateTime at = takenAt != null ? takenAt : (backfill ? null : LocalDateTime.now(JobCronSupport.ZONE));
        if (at != null && !at.toLocalDate().equals(day)) {
            throw new BusinessException("服药时间需在当天");
        }
        PeopleMedicationIntake row = new PeopleMedicationIntake();
        row.setTenantId(tenantId);
        row.setPeopleId(peopleId);
        row.setMedicationId(medicationId);
        row.setIntakeDate(day);
        row.setTimeSlot(slot);
        row.setDoseSeq(intakeMapper.maxDoseSeq(medicationId, day, slot) + 1);
        row.setStatus(MedicationIntakeStatusEnum.TAKEN.name());
        row.setTakenAt(at);
        row.setBackfill(backfill ? 1 : 0);
        EntityMeta.onCreate(row);
        intakeMapper.insert(row);
        return toIntakeView(row, med.getDrugName());
    }

    /** C 端：撤销一次打卡（今天或昨天）。 */
    @Transactional
    public void deleteIntakeByPatient(String tenantId, String peopleId, String medicationId, String intakeId) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        requireOwned(tenantId, peopleId, medicationId);
        PeopleMedicationIntake row = intakeMapper.findById(intakeId);
        if (row == null || !medicationId.equals(row.getMedicationId())) {
            throw new BusinessException(404, "服药记录不存在");
        }
        if (!MedicationScheduleService.isEditableDay(row.getIntakeDate())) {
            throw new BusinessException("只能撤销今天和昨天的记录");
        }
        if (StringUtils.hasText(row.getRecordedByStaffId())) {
            throw new BusinessException("健管师代录的记录不能撤销");
        }
        intakeMapper.softDelete(intakeId);
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
        assessmentDirtyMarker.mark(tenantId, peopleId);
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
        row.setAllergyConflict(conflictOf(row));
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

    /** C 端：患者自助添加用药（待医护核对；命中药物过敏时提醒并通知健管师）。 */
    @Transactional
    public MedicationViewDto createByPatient(String tenantId, String peopleId, MedicationCommand cmd) {
        assessmentDirtyMarker.mark(tenantId, peopleId);
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        PeopleMedication row = insertPatientMedication(tenantId, peopleId, cmd);
        syncVerifyTask(tenantId, peopleId);
        return toView(row);
    }

    /** C 端：拍照识别确认后批量添加。 */
    @Transactional
    public List<MedicationViewDto> createBatchByPatient(String tenantId, String peopleId, List<MedicationCommand> items) {
        assessmentDirtyMarker.mark(tenantId, peopleId);
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        if (items == null || items.isEmpty()) {
            throw new BusinessException("药品不能为空");
        }
        if (items.size() > 10) {
            throw new BusinessException("一次最多添加 10 种药");
        }
        List<MedicationViewDto> out = new ArrayList<>();
        for (MedicationCommand item : items) {
            out.add(toView(insertPatientMedication(tenantId, peopleId, item)));
        }
        syncVerifyTask(tenantId, peopleId);
        return out;
    }

    private PeopleMedication insertPatientMedication(String tenantId, String peopleId, MedicationCommand cmd) {
        validateCommand(cmd);
        PeopleMedication row = new PeopleMedication();
        row.setTenantId(tenantId);
        row.setPeopleId(peopleId);
        row.setOrgId(null);
        row.setPrescriptionGroupId(null);
        row.setSource(MedicationSourceEnum.PATIENT.name());
        applyCommand(row, cmd);
        row.setStatus(resolveStatus(row.getStopDate(), cmd.status()));
        row.setAllergyConflict(conflictOf(row));
        row.setCreatedByStaffId(null);
        row.setUpdatedByStaffId(null);
        EntityMeta.onCreate(row);
        medicationMapper.insert(row);
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
                toRevisionJson(row));
        reportAllergyConflict(row, null);
        return row;
    }

    /**
     * C 端：患者修改本人用药。自添加药可改全部字段；医护开立药只改时段、服药时机与备注。
     */
    @Transactional
    public MedicationViewDto updateByPatient(
            String tenantId, String peopleId, String medicationId, MedicationCommand cmd) {
        assessmentDirtyMarker.mark(tenantId, peopleId);
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        PeopleMedication row = requireOwned(tenantId, peopleId, medicationId);
        String oldJson = toRevisionJson(row);
        String oldConflict = row.getAllergyConflict();
        if (isPatientOwned(row)) {
            validateCommand(cmd);
            boolean adverse = Integer.valueOf(1).equals(row.getHasAdverseReaction());
            applyCommand(row, cmd);
            if (adverse) {
                row.setHasAdverseReaction(1);
            }
            row.setStatus(resolveStatus(row.getStopDate(), cmd.status()));
            row.setAllergyConflict(conflictOf(row));
        } else {
            if (cmd == null) {
                throw new BusinessException("参数不能为空");
            }
            applySchedulingFields(row, cmd);
        }
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
        reportAllergyConflict(row, oldConflict);
        syncVerifyTask(tenantId, peopleId);
        return toView(row);
    }

    /** C 端：删除自添加药；医护开立药只能「我已停药」。 */
    @Transactional
    public void deleteByPatient(String tenantId, String peopleId, String medicationId) {
        assessmentDirtyMarker.mark(tenantId, peopleId);
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        PeopleMedication row = requireOwned(tenantId, peopleId, medicationId);
        if (!isPatientOwned(row)) {
            throw new BusinessException(403, "医护开立的药不能删除，如已停药请点「我已停药」");
        }
        String snapshot = toRevisionJson(row);
        medicationMapper.softDelete(medicationId, null);
        fieldRevisionService.recordDeleted(
                tenantId,
                peopleId,
                OPERATOR_PEOPLE,
                peopleId,
                RevisionBizTypeEnum.MEDICATION.name(),
                medicationId,
                null,
                snapshot);
        syncVerifyTask(tenantId, peopleId);
    }

    /** C 端：我已停药。立即停用并停止提醒，通知健管师。 */
    @Transactional
    public MedicationViewDto stopByPatient(
            String tenantId, String peopleId, String medicationId, String reason, String note) {
        assessmentDirtyMarker.mark(tenantId, peopleId);
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        PeopleMedication row = requireOwned(tenantId, peopleId, medicationId);
        if (MedicationStatusEnum.STOPPED.name().equals(row.getStatus())) {
            throw new BusinessException("该药已停用");
        }
        MedicationStopReasonEnum stopReason;
        try {
            stopReason = MedicationStopReasonEnum.valueOf(reason.trim().toUpperCase());
        } catch (Exception e) {
            throw new BusinessException("请选择停药原因");
        }
        String text = trimToNull(note, 200);
        String oldJson = toRevisionJson(row);
        LocalDate today = MedicationScheduleService.today();
        row.setStatus(MedicationStatusEnum.STOPPED.name());
        row.setStopDate(row.getStartDate() != null && row.getStartDate().isAfter(today) ? row.getStartDate() : today);
        row.setStopReason(stopReason.name());
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
        String content = "原因：" + stopReason.label() + (text == null ? "" : "；" + text);
        feedbackService.record(row, MedicationFeedbackKindEnum.STOPPED, null, null, content);
        syncVerifyTask(tenantId, peopleId);
        return toView(row);
    }

    /** B 端：核对患者自添加的药。 */
    @Transactional
    public MedicationViewDto verify(String tenantId, String orgId, String peopleId, String staffId, String medicationId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        PeopleMedication row = requireOwned(tenantId, peopleId, medicationId);
        if (!isPatientOwned(row)) {
            throw new BusinessException("仅患者自添加的药需要核对");
        }
        if (row.getVerifiedAt() == null) {
            row.setVerifiedAt(LocalDateTime.now(JobCronSupport.ZONE));
            row.setVerifiedByStaffId(staffId);
            row.setUpdatedByStaffId(staffId);
            EntityMeta.onUpdate(row);
            medicationMapper.update(row);
        }
        syncVerifyTask(tenantId, peopleId);
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
        assessmentDirtyMarker.mark(tenantId, peopleId);
        if (items == null || items.isEmpty()) {
            throw new BusinessException("药品不能为空");
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
        assessmentDirtyMarker.mark(tenantId, peopleId);
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        validateCommand(cmd);
        PeopleMedication row = requireOwned(tenantId, peopleId, medicationId);
        String oldJson = toRevisionJson(row);
        applyCommand(row, cmd);
        row.setStatus(resolveStatus(row.getStopDate(), cmd.status()));
        if (MedicationStatusEnum.ACTIVE.name().equals(row.getStatus())) {
            row.setStopReason(null);
        }
        row.setAllergyConflict(conflictOf(row));
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
        if (isPatientOwned(row)) {
            syncVerifyTask(tenantId, peopleId);
        }
        return toView(row);
    }

    @Transactional
    public void delete(String tenantId, String orgId, String peopleId, String staffId, String medicationId) {
        assessmentDirtyMarker.mark(tenantId, peopleId);
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
        if (isPatientOwned(row)) {
            syncVerifyTask(tenantId, peopleId);
        }
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
        LocalDate day = intakeDate != null ? intakeDate : MedicationScheduleService.today();
        List<PeopleMedicationIntake> rows = intakeMapper.listByPeopleDate(tenantId, peopleId, day);
        List<MedicationIntakeViewDto> out = new ArrayList<>();
        for (PeopleMedicationIntake row : rows) {
            PeopleMedication med = medicationMapper.findById(row.getMedicationId());
            out.add(toIntakeView(row, med == null ? null : med.getDrugName()));
        }
        return out;
    }

    /** 记录或更新某日某时段依从性（幂等 upsert，健管师代录）。 */
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
        LocalDateTime takenAt = MedicationIntakeStatusEnum.TAKEN.name().equals(status) ? cmd.takenAt() : null;
        PeopleMedicationIntake existing =
                intakeMapper.findByMedDateSlot(medicationId, cmd.intakeDate(), slot);
        if (existing == null) {
            PeopleMedicationIntake row = new PeopleMedicationIntake();
            row.setTenantId(tenantId);
            row.setPeopleId(peopleId);
            row.setMedicationId(medicationId);
            row.setIntakeDate(cmd.intakeDate());
            row.setTimeSlot(slot);
            row.setDoseSeq(1);
            row.setStatus(status);
            row.setTakenAt(takenAt);
            row.setBackfill(0);
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
        existing.setTakenAt(takenAt != null ? takenAt : existing.getTakenAt());
        if (!MedicationIntakeStatusEnum.TAKEN.name().equals(status)) {
            existing.setTakenAt(null);
        }
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

    /** 保存时比对药物过敏；新出现的冲突写反馈并开 HIGH 任务。 */
    private void reportAllergyConflict(PeopleMedication row, String previousConflict) {
        String conflict = row.getAllergyConflict();
        if (!StringUtils.hasText(conflict) || Objects.equals(conflict, previousConflict)) {
            return;
        }
        feedbackService.record(
                row, MedicationFeedbackKindEnum.ALLERGY_CONFLICT, null, null, "档案过敏：" + conflict);
    }

    private String conflictOf(PeopleMedication row) {
        List<String> hits = allergyGuard.check(row.getPeopleId(), row.getDrugCode(), row.getDrugName());
        if (hits.isEmpty()) {
            return null;
        }
        String joined = String.join("、", hits);
        return joined.length() > 250 ? joined.substring(0, 250) : joined;
    }

    private void syncVerifyTask(String tenantId, String peopleId) {
        List<String> names = medicationMapper.listPendingVerify(tenantId, peopleId).stream()
                .map(PeopleMedication::getDrugName)
                .toList();
        workspaceTaskGenerator.syncMedicationVerify(tenantId, peopleId, names);
    }

    private static boolean isPatientOwned(PeopleMedication row) {
        return MedicationSourceEnum.PATIENT.name().equals(row.getSource());
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
        m.put("slots", row.getSlotsJson());
        m.put("hasAdverseReaction", row.getHasAdverseReaction() != null && row.getHasAdverseReaction() == 1);
        m.put("remark", row.getRemark());
        m.put("status", row.getStatus());
        m.put("stopReason", row.getStopReason());
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
        if (cmd.drugCode() != null) {
            row.setDrugCode(trimToNull(cmd.drugCode(), 64));
        }
        row.setUsageMethod(cmd.usageMethod().trim());
        row.setFrequency(trimToNull(cmd.frequency(), 128));
        row.setDoseAmount(trimToNull(cmd.doseAmount(), 32));
        row.setDoseUnit(trimToNull(cmd.doseUnit(), 32));
        row.setStartDate(cmd.startDate());
        row.setStopDate(cmd.stopDate());
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
        applySchedulingFields(row, cmd);
    }

    /** 作息相关字段：时段、服药时机、备注（患者对医护开立药也可改）。 */
    private void applySchedulingFields(PeopleMedication row, MedicationCommand cmd) {
        row.setTimingNote(trimToNull(cmd.timingNote(), 128));
        row.setRemark(trimToNull(cmd.remark(), 150));
        if (cmd.slots() != null) {
            row.setSlotsJson(MedicationSlotRules.isPrn(row) ? null : MedicationSlotRules.toJson(cmd.slots()));
        }
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
        LocalDate today = MedicationScheduleService.today();
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
        return row == null ? null : MedicationSlotRules.endDateOf(row);
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
        if (!isPastEndDate(row, MedicationScheduleService.today())) {
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
        dto.setDrugCode(row.getDrugCode());
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
        dto.setPrn(MedicationSlotRules.isPrn(row));
        dto.setDueDoseCount(MedicationSlotRules.dueCount(row));
        dto.setSuggestedSlots(MedicationSlotRules.slotsOf(row));
        dto.setSlotsCustom(!MedicationSlotRules.parseSlots(row.getSlotsJson()).isEmpty());
        dto.setPatientEditable(isPatientOwned(row));
        dto.setVerified(isPatientOwned(row) ? row.getVerifiedAt() != null : null);
        dto.setVerifiedAt(row.getVerifiedAt());
        dto.setAllergyConflict(row.getAllergyConflict());
        dto.setStopReason(row.getStopReason());
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
        dto.setDoseSeq(row.getDoseSeq());
        dto.setStatus(row.getStatus());
        dto.setTakenAt(row.getTakenAt());
        dto.setBackfill(Integer.valueOf(1).equals(row.getBackfill()));
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
            String status,
            String drugCode,
            /** 指定服药时段；null=不改，空列表=恢复按频次推导 */
            List<String> slots) {

        public MedicationCommand(
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
                String status) {
            this(drugName, usageMethod, frequency, doseAmount, doseUnit, startDate, stopDate, timingNote,
                    courseDays, hasAdverseReaction, remark, status, null, null);
        }
    }

    public record IntakeCommand(
            LocalDate intakeDate, String timeSlot, String status, String note, LocalDateTime takenAt) {

        public IntakeCommand(LocalDate intakeDate, String timeSlot, String status, String note) {
            this(intakeDate, timeSlot, status, note, null);
        }
    }
}
