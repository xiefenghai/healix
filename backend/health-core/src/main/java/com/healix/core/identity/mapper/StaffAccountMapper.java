package com.healix.core.identity.mapper;

import com.healix.core.identity.domain.StaffAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StaffAccountMapper {

    StaffAccount findByUsername(@Param("username") String username);

    StaffAccount findById(@Param("id") String id);

    int insert(StaffAccount account);

    int updateStatusAndPassword(StaffAccount account);
}
