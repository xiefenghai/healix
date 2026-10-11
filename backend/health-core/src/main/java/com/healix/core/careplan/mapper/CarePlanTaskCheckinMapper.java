package com.healix.core.careplan.mapper;

import com.healix.core.careplan.domain.CarePlanTaskCheckin;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CarePlanTaskCheckinMapper {

    int insert(CarePlanTaskCheckin row);

    int update(CarePlanTaskCheckin row);

    int softDelete(@Param("id") String id);

    /** 系统打卡是记录派生数据，撤销时物理删除：软删的 gmt_deleted 只到秒，同秒内反复撤销会撞唯一键。 */
    int deleteSystemCheckin(@Param("id") String id, @Param("systemNote") String systemNote);

    CarePlanTaskCheckin findByTaskDateSlot(
            @Param("taskId") String taskId,
            @Param("checkinDate") LocalDate checkinDate,
            @Param("timeSlot") String timeSlot);

    List<CarePlanTaskCheckin> listByPeopleDate(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("checkinDate") LocalDate checkinDate);

    List<CarePlanTaskCheckin> listByPeopleRange(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    List<CarePlanTaskCheckin> listByPeopleVersionRange(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("planVersionId") String planVersionId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    List<CarePlanTaskCheckin> listByTenantPeopleIdsRange(
            @Param("tenantId") String tenantId,
            @Param("peopleIds") List<String> peopleIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
