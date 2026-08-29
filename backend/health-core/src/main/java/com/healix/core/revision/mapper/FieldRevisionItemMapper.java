package com.healix.core.revision.mapper;

import com.healix.core.revision.domain.FieldRevisionItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FieldRevisionItemMapper {

    int insert(FieldRevisionItem item);

    List<FieldRevisionItem> listByBatchId(@Param("batchId") String batchId);
}
