package com.healix.core.job.mapper;

import com.healix.core.job.domain.SysJobRun;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SysJobRunMapper {

    int insert(SysJobRun row);

    int updateFinish(SysJobRun row);

    List<SysJobRun> listByJobCode(
            @Param("jobCode") String jobCode, @Param("offset") int offset, @Param("pageSize") int pageSize);

    long countByJobCode(@Param("jobCode") String jobCode);
}
