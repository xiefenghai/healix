package com.healix.core.medication.mapper;

import com.healix.core.medication.domain.PeopleMedication;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeopleMedicationMapper {

    int insert(PeopleMedication row);

    int update(PeopleMedication row);

    int softDelete(@Param("id") String id, @Param("staffId") String staffId);

    PeopleMedication findById(@Param("id") String id);

    List<PeopleMedication> listByPeople(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("status") String status);

    List<PeopleMedication> listActiveByTenantAndPeopleIds(
            @Param("tenantId") String tenantId, @Param("peopleIds") List<String> peopleIds);

    /** 将启用租户中已过结束日的 ACTIVE 用药批量改为 STOPPED，返回本批更新行数。 */
    int expireDueForActiveTenants(@Param("today") java.time.LocalDate today, @Param("limit") int limit);
}
