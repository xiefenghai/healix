package com.healix.core.identity.mapper;

import com.healix.core.identity.domain.OpsAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OpsAccountMapper {

    OpsAccount findByUsername(@Param("username") String username);

    int countAll();

    int insert(OpsAccount account);
}
