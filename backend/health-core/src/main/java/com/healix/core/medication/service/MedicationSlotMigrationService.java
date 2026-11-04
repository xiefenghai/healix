package com.healix.core.medication.service;

import com.healix.core.medication.domain.PeopleMedication;
import com.healix.core.medication.domain.PeopleMedicationIntake;
import com.healix.core.medication.mapper.PeopleMedicationIntakeMapper;
import com.healix.core.medication.mapper.PeopleMedicationMapper;
import com.healix.core.medication.support.MedicationSlotRules;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 历史打卡时段迁移（幂等）：单时段定时药的 OTHER 打卡改到推断时段，目标时段当天已有记录则保留原行；
 * PRN 药的 OTHER 打卡改为 PRN 并按当日顺延 dose_seq。多时段药的 OTHER 无法判断归属，保持不动。
 */
@Service
@RequiredArgsConstructor
public class MedicationSlotMigrationService {

    private final PeopleMedicationMapper medicationMapper;
    private final PeopleMedicationIntakeMapper intakeMapper;

    @Transactional
    public int migrateLegacyOther() {
        int moved = 0;
        for (String medicationId : intakeMapper.listMedicationIdsWithLegacyOther()) {
            PeopleMedication med = medicationMapper.findById(medicationId);
            if (med == null) {
                continue;
            }
            boolean prn = MedicationSlotRules.isPrn(med);
            List<String> slots = MedicationSlotRules.slotsOf(med);
            if (!prn && slots.size() != 1) {
                continue;
            }
            for (PeopleMedicationIntake row : intakeMapper.listLegacyOtherByMedication(medicationId)) {
                if (prn) {
                    int seq = intakeMapper.maxDoseSeq(medicationId, row.getIntakeDate(), MedicationSlotRules.PRN) + 1;
                    moved += intakeMapper.updateSlotSeq(row.getId(), MedicationSlotRules.PRN, seq);
                    continue;
                }
                String target = slots.get(0);
                if (intakeMapper.findByMedDateSlot(medicationId, row.getIntakeDate(), target) == null) {
                    moved += intakeMapper.updateSlot(row.getId(), target);
                }
            }
        }
        return moved;
    }
}
