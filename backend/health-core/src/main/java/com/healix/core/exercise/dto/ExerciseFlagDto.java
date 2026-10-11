package com.healix.core.exercise.dto;

/**
 * 运动规则提示。
 *
 * @param level RED / YELLOW / INFO
 */
public record ExerciseFlagDto(String code, String level, String message) {}
