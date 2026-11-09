package com.healix.core.care.mapper;

import com.healix.core.care.domain.CareTeam;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CareTeamMapper {

    CareTeam findById(@Param("id") String id);

    /** 机构内按名称精确匹配（未删除），用于防重名。 */
    CareTeam findByOrgAndName(@Param("orgId") String orgId, @Param("name") String name);

    List<CareTeam> listByOrg(@Param("orgId") String orgId, @Param("keyword") String keyword);

    /** 当前员工作为主责健管师的健管组。 */
    List<CareTeam> listByPrimaryCareManager(
            @Param("orgId") String orgId, @Param("staffId") String staffId);

    int insert(CareTeam team);

    int update(CareTeam team);

    int softDelete(
            @Param("id") String id,
            @Param("gmtDeleted") LocalDateTime gmtDeleted,
            @Param("gmtModified") LocalDateTime gmtModified);
}
