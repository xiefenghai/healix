package com.healix.core.patientcard.mapper;

import com.healix.core.patientcard.domain.MobileOtp;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MobileOtpMapper {

    int insert(MobileOtp row);

    int consume(@Param("id") String id, @Param("consumedAt") LocalDateTime consumedAt);

    int consumeUnspent(
            @Param("tenantId") String tenantId,
            @Param("accountId") String accountId,
            @Param("mobile") String mobile,
            @Param("consumedAt") LocalDateTime consumedAt);

    MobileOtp findLatestOpen(
            @Param("tenantId") String tenantId,
            @Param("accountId") String accountId,
            @Param("mobile") String mobile);

    MobileOtp findLatestAny(
            @Param("tenantId") String tenantId,
            @Param("accountId") String accountId,
            @Param("mobile") String mobile);

    int countSince(
            @Param("tenantId") String tenantId,
            @Param("mobile") String mobile,
            @Param("since") LocalDateTime since);
}
