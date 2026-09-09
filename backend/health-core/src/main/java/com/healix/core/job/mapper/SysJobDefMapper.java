package com.healix.core.job.mapper;

import com.healix.core.job.domain.SysJobDef;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SysJobDefMapper {

    int insert(SysJobDef row);

    int updateConfig(SysJobDef row);

    int updateFireState(SysJobDef row);

    SysJobDef findByJobCode(@Param("jobCode") String jobCode);

    List<SysJobDef> listAll();

    List<SysJobDef> listDue(@Param("now") LocalDateTime now);
}
