package com.healix.core.observation.mapper;

import com.healix.core.observation.domain.LabResultItem;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LabResultItemMapper {

    int insert(LabResultItem row);

    int softDeleteByReportId(@Param("reportId") String reportId);

    List<LabResultItem> listByReportId(@Param("reportId") String reportId);

    /** 按人取指定检验码最近数值（已按时间倒序；调用方按 code 取首条）。 */
    List<LabLatestNumeric> listLatestNumericByPeople(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("itemCodes") List<String> itemCodes);

    @Getter
    @Setter
    class LabLatestNumeric {
        private String itemCode;
        private BigDecimal valueNum;
        private String unit;
        private LocalDateTime recordedAt;
    }
}
