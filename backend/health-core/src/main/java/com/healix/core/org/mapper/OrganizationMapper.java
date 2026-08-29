package com.healix.core.org.mapper;

import com.healix.core.org.domain.Organization;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrganizationMapper {

    Organization findById(@Param("id") String id);

    List<Organization> listByTenant(@Param("tenantId") String tenantId);

    int insert(Organization org);

    int update(Organization org);
}
