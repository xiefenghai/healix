package com.healix.core.revision.mapper;

import com.healix.core.revision.domain.FieldRevisionBatch;
import com.healix.core.revision.domain.FieldRevisionItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FieldRevisionBatchMapper {

    int insert(FieldRevisionBatch batch);

    List<FieldRevisionBatch> listByTarget(
            @Param("tenantId") String tenantId,
            @Param("targetPeopleId") String targetPeopleId,
            @Param("bizType") String bizType,
            @Param("bizKey") String bizKey);

    List<FieldRevisionBatch> listByPeople(
            @Param("tenantId") String tenantId, @Param("targetPeopleId") String targetPeopleId);
}
