package com.healix.core.diet.dto;

import java.util.List;

/**
 * 目标设定页数据：当前目标 + 计算所需的档案值 + 缺失项。
 *
 * @param missing 缺失的计算要素：GENDER / BIRTHDAY / HEIGHT / WEIGHT
 */
public record DietGoalStatusDto(
        DietGoalDto goal,
        String gender,
        Integer age,
        Double heightCm,
        Double weightKg,
        String diseaseKey,
        String diseaseLabel,
        List<String> missing,
        boolean editableByPatient) {}
