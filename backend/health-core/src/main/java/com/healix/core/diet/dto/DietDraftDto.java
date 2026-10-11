package com.healix.core.diet.dto;

import java.util.List;

/** 拍照 / 文本识别草稿：用户确认克数后才保存为餐次。 */
public record DietDraftDto(String mealType, List<DietMealItemDto> items, List<String> warnings) {}
