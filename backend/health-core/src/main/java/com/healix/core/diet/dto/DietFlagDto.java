package com.healix.core.diet.dto;

/**
 * 规则引擎提示。
 *
 * @param level RED / YELLOW / INFO
 */
public record DietFlagDto(String code, String level, String message) {}
