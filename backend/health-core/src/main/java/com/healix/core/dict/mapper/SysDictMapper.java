package com.healix.core.dict.mapper;

import com.healix.core.dict.domain.SysDict;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SysDictMapper {

    int countByTenant(@Param("tenantId") String tenantId);

    List<SysDict> listByTypeAndParent(
            @Param("tenantId") String tenantId,
            @Param("dictType") String dictType,
            @Param("parentCode") String parentCode);

    List<SysDict> listByType(@Param("tenantId") String tenantId, @Param("dictType") String dictType);

    int insert(SysDict row);

    int updateDescAndSort(SysDict row);
}
