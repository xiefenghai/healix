package com.healix.core.identity.mapper;

import com.healix.core.identity.domain.StaffRoleBinding;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StaffRoleBindingMapper {

    List<StaffRoleBinding> listByStaff(@Param("staffId") String staffId);

    List<StaffRoleBinding> listByStaffIds(@Param("staffIds") List<String> staffIds);

    StaffRoleBinding findTenantAdminByStaff(@Param("staffId") String staffId);

    List<String> listStaffIdsByTenantAndRole(
            @Param("tenantId") String tenantId, @Param("roleCode") String roleCode);

    int insert(StaffRoleBinding binding);

    int softDeleteAssignableByStaff(
            @Param("staffId") String staffId,
            @Param("gmtDeleted") LocalDateTime gmtDeleted,
            @Param("gmtModified") LocalDateTime gmtModified);
}
