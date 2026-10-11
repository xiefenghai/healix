package com.healix.core.exercise.dto;

import java.util.List;
import java.util.Map;

public record ExerciseTypeDto(
        String code,
        String name,
        List<String> aliases,
        String category,
        String defaultIntensity,
        Map<String, Double> met,
        List<String> tags) {}
