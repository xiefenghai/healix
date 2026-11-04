package com.healix.core.identity.mapper;

import com.healix.core.identity.domain.StaffProfile;
import com.healix.core.ops.dto.StaffProfileListRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StaffProfileMapper {

    /** 最早加入的一份档案。账号跨多个租户时请用 {@link #listByAccountId} 或 {@link #findByAccountAndTenant}。 */
    StaffProfile findByAccountId(@Param("accountId") String accountId);

    List<StaffProfile> listByAccountId(@Param("accountId") String accountId);

    StaffProfile findByAccountAndTenant(
            @Param("accountId") String accountId, @Param("tenantId") String tenantId);

    StaffProfile findById(@Param("id") String id);

    int insert(StaffProfile profile);

    int updateProfile(StaffProfile profile);

    List<StaffProfileListRow> listByTenant(
            @Param("tenantId") String tenantId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("roleCode") String roleCode,
            @Param("orgId") String orgId,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    long countByTenant(
            @Param("tenantId") String tenantId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("roleCode") String roleCode,
            @Param("orgId") String orgId);
}
