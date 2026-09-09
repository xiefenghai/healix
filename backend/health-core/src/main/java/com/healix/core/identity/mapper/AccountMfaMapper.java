package com.healix.core.identity.mapper;

import com.healix.core.identity.domain.AccountMfa;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AccountMfaMapper {

    AccountMfa find(@Param("accountType") String accountType, @Param("accountId") String accountId);

    int upsert(AccountMfa row);

    int enable(
            @Param("accountType") String accountType,
            @Param("accountId") String accountId,
            @Param("enabledAt") LocalDateTime enabledAt);

    int softDelete(
            @Param("accountType") String accountType,
            @Param("accountId") String accountId,
            @Param("gmtModified") LocalDateTime gmtModified);
}
