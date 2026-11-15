package com.healix.core.revision.mapper;

import com.healix.core.revision.domain.FieldRevisionBatch;
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
            @Param("bizKey") String bizKey,
            @Param("keyword") String keyword,
            @Param("bizTypeHints") List<String> bizTypeHints,
            @Param("bizKeyHints") List<String> bizKeyHints);

    List<FieldRevisionBatch> listByPeople(
            @Param("tenantId") String tenantId,
            @Param("targetPeopleId") String targetPeopleId,
            @Param("keyword") String keyword,
            @Param("bizTypeHints") List<String> bizTypeHints,
            @Param("bizKeyHints") List<String> bizKeyHints);
}
