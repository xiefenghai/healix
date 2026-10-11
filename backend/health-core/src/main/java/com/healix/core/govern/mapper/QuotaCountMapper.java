package com.healix.core.govern.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 存量类配额的实时口径。
 *
 * <p>患者数/员工数直接 COUNT，不维护累加值：建档是低频操作，而累加值一旦因为
 * 删档、合并、导入回滚而漂移，就会出现「明明没满却建不了档」这种最难解释的故障。
 */
@Mapper
public interface QuotaCountMapper {

    long countPatients(@Param("tenantId") String tenantId);

    long countStaff(@Param("tenantId") String tenantId);
}
