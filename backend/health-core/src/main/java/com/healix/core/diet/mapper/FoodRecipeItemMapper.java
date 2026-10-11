package com.healix.core.diet.mapper;

import com.healix.core.diet.domain.FoodRecipeItem;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FoodRecipeItemMapper {

    int deleteByDishCodes(@Param("dishCodes") List<String> dishCodes);

    int insertBatch(@Param("rows") List<FoodRecipeItem> rows);

    List<FoodRecipeItem> listAll();
}
