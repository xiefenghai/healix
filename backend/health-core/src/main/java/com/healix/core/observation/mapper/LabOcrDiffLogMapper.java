package com.healix.core.observation.mapper;

import com.healix.core.observation.domain.LabOcrDiffLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LabOcrDiffLogMapper {
    int insert(LabOcrDiffLog row);
}
