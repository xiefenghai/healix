package com.healix.core.care.mapper;

import com.healix.core.care.domain.CareTeam;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CareTeamMapper {

    CareTeam findById(@Param("id") String id);

    List<CareTeam> listByOrg(@Param("orgId") String orgId, @Param("keyword") String keyword);

    int insert(CareTeam team);

    int update(CareTeam team);

    int softDelete(
            @Param("id") String id,
            @Param("gmtDeleted") LocalDateTime gmtDeleted,
            @Param("gmtModified") LocalDateTime gmtModified);
}
