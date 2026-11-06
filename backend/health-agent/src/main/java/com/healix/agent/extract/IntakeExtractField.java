package com.healix.agent.extract;

import java.util.List;

/** 当前打开的问卷题目。只用于这一次整理，不入库。 */
public record IntakeExtractField(
        String code,
        String label,
        String type,
        String unit,
        Double min,
        Double max,
        List<ChatExtractOption> options) {}
