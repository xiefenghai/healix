package com.healix.core.medication.mapper;

import com.healix.core.medication.domain.PeopleMedicationIntake;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeopleMedicationIntakeMapper {

    int insert(PeopleMedicationIntake row);

    int update(PeopleMedicationIntake row);

    int softDelete(@Param("id") String id);

    int updateSlot(@Param("id") String id, @Param("timeSlot") String timeSlot);

    int updateSlotSeq(@Param("id") String id, @Param("timeSlot") String timeSlot, @Param("doseSeq") int doseSeq);

    PeopleMedicationIntake findById(@Param("id") String id);

    PeopleMedicationIntake findByMedDateSlot(
            @Param("medicationId") String medicationId,
            @Param("intakeDate") LocalDate intakeDate,
            @Param("timeSlot") String timeSlot);

    int maxDoseSeq(
            @Param("medicationId") String medicationId,
            @Param("intakeDate") LocalDate intakeDate,
            @Param("timeSlot") String timeSlot);

    int countByMedDateSlot(
            @Param("medicationId") String medicationId,
            @Param("intakeDate") LocalDate intakeDate,
            @Param("timeSlot") String timeSlot);

    List<PeopleMedicationIntake> listByMedication(
            @Param("medicationId") String medicationId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    List<PeopleMedicationIntake> listLegacyOtherByMedication(@Param("medicationId") String medicationId);

    List<String> listMedicationIdsWithLegacyOther();

    List<PeopleMedicationIntake> listByPeopleDate(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("intakeDate") LocalDate intakeDate);

    List<PeopleMedicationIntake> listByTenantPeopleIdsDate(
            @Param("tenantId") String tenantId,
            @Param("peopleIds") List<String> peopleIds,
            @Param("intakeDate") LocalDate intakeDate);

    List<PeopleMedicationIntake> listByPeopleRange(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
