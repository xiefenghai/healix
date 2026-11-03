package com.healix.core.intake.mapper;

import com.healix.core.intake.domain.IntakeAccessLink;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IntakeAccessLinkMapper {

    IntakeAccessLink findById(@Param("id") String id);

    IntakeAccessLink findByToken(@Param("token") String token);

    int insert(IntakeAccessLink link);

    int revokeOpenByPeople(
            @Param("peopleId") String peopleId,
            @Param("orgId") String orgId,
            @Param("gmtModified") LocalDateTime gmtModified);

    int revokeById(@Param("id") String id, @Param("gmtModified") LocalDateTime gmtModified);

    int incrementRedeem(
            @Param("id") String id,
            @Param("lastRedeemAt") LocalDateTime lastRedeemAt,
            @Param("gmtModified") LocalDateTime gmtModified);

    int markExpired(@Param("id") String id, @Param("gmtModified") LocalDateTime gmtModified);
}
