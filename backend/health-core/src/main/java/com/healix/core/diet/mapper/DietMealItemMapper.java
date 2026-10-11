package com.healix.core.diet.mapper;

import com.healix.core.diet.domain.DietMealItem;
import java.time.LocalDate;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DietMealItemMapper {

    int insertBatch(@Param("rows") List<DietMealItem> rows);

    int softDeleteByMeal(@Param("mealId") String mealId);

    List<DietMealItem> listByMealIds(@Param("mealIds") List<String> mealIds);

    /** 近期最常吃（按食物编码 + 单位聚合），供快捷添加。 */
    List<FrequentFoodRow> listFrequent(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("since") LocalDate since,
            @Param("limit") int limit);

    @Getter
    @Setter
    class FrequentFoodRow {
        private String foodCode;
        private String foodName;
        private String unit;
        private Double amount;
        private Long times;
    }
}
