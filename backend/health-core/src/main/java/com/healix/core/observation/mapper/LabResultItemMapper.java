package com.healix.core.observation.mapper;

import com.healix.core.observation.domain.LabResultItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LabResultItemMapper {

    int insert(LabResultItem row);

    int softDeleteByReportId(@Param("reportId") String reportId);

    List<LabResultItem> listByReportId(@Param("reportId") String reportId);
}
