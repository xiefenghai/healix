package com.healix.core.tenant.mapper;

import com.healix.core.ops.dto.TenantListItem;
import com.healix.core.tenant.domain.Tenant;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TenantMapper {

    Tenant findById(@Param("id") String id);

    Tenant findByCode(@Param("code") String code);

    int insert(Tenant tenant);

    int updateStatus(
            @Param("id") String id, @Param("status") String status, @Param("gmtModified") LocalDateTime gmtModified);

    List<TenantListItem> list(
            @Param("keyword") String keyword, @Param("offset") int offset, @Param("pageSize") int pageSize);

    long count(@Param("keyword") String keyword);

    boolean hasTenantAdmin(@Param("tenantId") String tenantId);
}
