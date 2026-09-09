package com.healix.core.govern.mapper;

import com.healix.core.govern.domain.TenantConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TenantConfigMapper {

    TenantConfig findByTenant(@Param("tenantId") String tenantId);

    int upsert(TenantConfig row);
}
