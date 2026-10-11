package com.healix.core.observation.mapper;

import com.healix.core.observation.domain.LabReport;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LabReportMapper {

    int insert(LabReport row);

    int update(LabReport row);

    int softDelete(@Param("id") String id, @Param("staffId") String staffId);

    LabReport findById(@Param("id") String id);

    List<LabReport> listByPeople(@Param("tenantId") String tenantId, @Param("peopleId") String peopleId);
}
