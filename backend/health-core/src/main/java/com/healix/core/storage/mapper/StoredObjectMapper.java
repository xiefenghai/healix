package com.healix.core.storage.mapper;

import com.healix.core.storage.domain.StoredObject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StoredObjectMapper {

    int insert(StoredObject row);

    StoredObject findByKey(@Param("objectKey") String objectKey);
}
