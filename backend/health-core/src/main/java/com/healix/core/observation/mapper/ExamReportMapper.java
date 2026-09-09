package com.healix.core.observation.mapper;

import com.healix.core.observation.domain.ExamReport;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ExamReportMapper {

    int insert(ExamReport row);

    int update(ExamReport row);

    int softDelete(@Param("id") String id, @Param("staffId") String staffId);

    ExamReport findById(@Param("id") String id);

    List<ExamReport> listByPeople(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("examType") String examType);
}
