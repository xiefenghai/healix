package com.healix.core.govern.mapper;

import com.healix.core.govern.domain.TenantQuotaUsage;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TenantQuotaUsageMapper {

    TenantQuotaUsage find(
            @Param("tenantId") String tenantId,
            @Param("quotaKey") String quotaKey,
            @Param("periodKey") String periodKey);

    List<TenantQuotaUsage> listByTenant(@Param("tenantId") String tenantId);

    int insert(TenantQuotaUsage row);

    /** 原子自增用量；返回受影响行数，0 表示行不存在需先 insert。 */
    int addUsed(
            @Param("tenantId") String tenantId,
            @Param("quotaKey") String quotaKey,
            @Param("periodKey") String periodKey,
            @Param("delta") long delta,
            @Param("gmtModified") LocalDateTime gmtModified);

    int updateUsed(
            @Param("tenantId") String tenantId,
            @Param("quotaKey") String quotaKey,
            @Param("periodKey") String periodKey,
            @Param("usedValue") long usedValue,
            @Param("gmtModified") LocalDateTime gmtModified);

    /** 上限置空表示不限量。 */
    int updateLimit(
            @Param("tenantId") String tenantId,
            @Param("quotaKey") String quotaKey,
            @Param("periodKey") String periodKey,
            @Param("limitValue") Long limitValue,
            @Param("gmtModified") LocalDateTime gmtModified);
}
