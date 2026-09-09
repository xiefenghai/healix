package com.healix.core.govern.mapper;

import com.healix.core.govern.domain.FeatureFlag;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FeatureFlagMapper {

    FeatureFlag find(@Param("tenantId") String tenantId, @Param("flagKey") String flagKey);

    /** tenantId 传空串取平台级。 */
    List<FeatureFlag> listByTenant(@Param("tenantId") String tenantId);

    int insert(FeatureFlag row);

    int update(
            @Param("tenantId") String tenantId,
            @Param("flagKey") String flagKey,
            @Param("enabled") boolean enabled,
            @Param("configJson") String configJson,
            @Param("gmtModified") LocalDateTime gmtModified);
}
