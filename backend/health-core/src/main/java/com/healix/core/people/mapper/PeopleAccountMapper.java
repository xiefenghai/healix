package com.healix.core.people.mapper;

import com.healix.core.people.domain.PeopleAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeopleAccountMapper {

    PeopleAccount findByTenantAndUsername(@Param("tenantId") String tenantId, @Param("username") String username);

    /** 平台级按手机号查找（C 账号无租户）。 */
    PeopleAccount findByUsername(@Param("username") String username);

    PeopleAccount findById(@Param("id") String id);

    int insert(PeopleAccount account);
}
