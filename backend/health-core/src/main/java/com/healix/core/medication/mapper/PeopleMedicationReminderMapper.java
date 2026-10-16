package com.healix.core.medication.mapper;

import com.healix.core.medication.domain.PeopleMedicationReminder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeopleMedicationReminderMapper {

    int insert(PeopleMedicationReminder row);

    int update(PeopleMedicationReminder row);

    PeopleMedicationReminder findByPeople(@Param("tenantId") String tenantId, @Param("peopleId") String peopleId);

    List<PeopleMedicationReminder> listByPeopleIds(
            @Param("tenantId") String tenantId, @Param("peopleIds") List<String> peopleIds);

    /** 有在用定时药且已绑定 C 账号的患者。 */
    List<String> listReminderCandidates(@Param("tenantId") String tenantId);

    /** 发送去重：已存在返回 0。 */
    int insertLogIgnore(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("remindDate") LocalDate remindDate,
            @Param("timeSlot") String timeSlot,
            @Param("seq") int seq,
            @Param("sentAt") LocalDateTime sentAt);

    int deleteLogsBefore(@Param("before") LocalDate before);
}
