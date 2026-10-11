package com.healix.core.diet.mapper;

import com.healix.core.diet.domain.FoodItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FoodItemMapper {

    int countPlatformBySeedVersion(@Param("seedVersion") String seedVersion);

    /** 平台种子按 code 幂等写入。 */
    int upsertBatch(@Param("rows") List<FoodItem> rows);

    /** 种子中已移除的平台食物置为 DISABLED（历史记录仍可按 code 展示快照）。 */
    int disableStalePlatform(@Param("seedVersion") String seedVersion);

    List<FoodItem> listActivePlatform();
}
