package com.healix.core.recipe.mapper;

import com.healix.core.recipe.domain.OrgRecipe;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrgRecipeMapper {
    int insert(OrgRecipe row);

    int update(OrgRecipe row);

    int softDelete(@Param("id") String id);

    OrgRecipe findById(@Param("id") String id);

    List<OrgRecipe> listByOrg(
            @Param("tenantId") String tenantId,
            @Param("orgId") String orgId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("limit") int limit);

    long countByOrg(
            @Param("tenantId") String tenantId,
            @Param("orgId") String orgId,
            @Param("keyword") String keyword,
            @Param("status") String status);
}
