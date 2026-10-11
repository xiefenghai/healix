package com.healix.core.identity.mapper;

import com.healix.core.identity.domain.OpsAccount;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OpsAccountMapper {

    OpsAccount findByUsername(@Param("username") String username);

    OpsAccount findById(@Param("id") String id);

    List<OpsAccount> listByIds(@Param("ids") List<String> ids);

    int countAll();

    int insert(OpsAccount account);
}
