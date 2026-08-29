package com.healix.core.org.mapper;

import com.healix.core.org.domain.OrgInviteCode;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrgInviteCodeMapper {

    OrgInviteCode findByCode(@Param("code") String code);

    int insert(OrgInviteCode invite);

    int refresh(
            @Param("id") String id,
            @Param("code") String code,
            @Param("expireAt") LocalDateTime expireAt,
            @Param("gmtModified") LocalDateTime gmtModified);
}
