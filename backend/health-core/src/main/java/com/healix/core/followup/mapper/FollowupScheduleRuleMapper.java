package com.healix.core.followup.mapper;

import com.healix.core.followup.domain.FollowupScheduleRule;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FollowupScheduleRuleMapper {

    int insert(FollowupScheduleRule row);

    int update(FollowupScheduleRule row);

    int softDelete(@Param("id") String id);

    FollowupScheduleRule findById(@Param("id") String id);

    FollowupScheduleRule findByScope(
            @Param("tenantId") String tenantId, @Param("orgId") String orgId, @Param("diseaseCode") String diseaseCode);

    List<FollowupScheduleRule> listByTenant(@Param("tenantId") String tenantId);
}
