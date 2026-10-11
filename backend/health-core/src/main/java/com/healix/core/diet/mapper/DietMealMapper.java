package com.healix.core.diet.mapper;

import com.healix.core.diet.domain.DietMeal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DietMealMapper {

    int insert(DietMeal row);

    int update(DietMeal row);

    int updateComment(DietMeal row);

    int softDelete(@Param("id") String id);

    DietMeal findById(@Param("id") String id);

    List<DietMeal> listByPeopleRange(
            @Param("tenantId") String tenantId,
            @Param("peopleId") String peopleId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
