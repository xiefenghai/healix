package com.healix.core.careplan.mapper;

import com.healix.core.careplan.domain.CarePlanVersion;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CarePlanVersionMapper {

    int insert(CarePlanVersion row);

    CarePlanVersion findById(@Param("id") String id);

    List<CarePlanVersion> listByPlanId(@Param("planId") String planId);

    int softDelete(@Param("id") String id);

    Integer maxVersionNo(@Param("planId") String planId);

    List<CarePlanVersion> listByIds(@Param("ids") List<String> ids);

    /** 医生签署；仅 UNSIGNED 可写，重复签署返回 0。 */
    int sign(
            @Param("id") String id,
            @Param("signedByStaffId") String signedByStaffId,
            @Param("signedAt") LocalDateTime signedAt);
}
