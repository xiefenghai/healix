package com.healix.core.identity.mapper;

import com.healix.core.identity.domain.StaffOrgBinding;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StaffOrgBindingMapper {

    List<StaffOrgBinding> listByStaff(@Param("staffId") String staffId);

    List<StaffOrgBinding> listByStaffIds(@Param("staffIds") List<String> staffIds);

    List<StaffOrgBinding> listDefaultsByStaffIds(@Param("staffIds") List<String> staffIds);

    int countBinding(@Param("staffId") String staffId, @Param("orgId") String orgId);

    int insert(StaffOrgBinding binding);

    int softDeleteByStaff(
            @Param("staffId") String staffId,
            @Param("gmtDeleted") LocalDateTime gmtDeleted,
            @Param("gmtModified") LocalDateTime gmtModified);

    int softDeleteByStaffAndOrg(
            @Param("staffId") String staffId,
            @Param("orgId") String orgId,
            @Param("gmtDeleted") LocalDateTime gmtDeleted,
            @Param("gmtModified") LocalDateTime gmtModified);

    List<StaffOrgBinding> listByOrg(@Param("orgId") String orgId);
}
