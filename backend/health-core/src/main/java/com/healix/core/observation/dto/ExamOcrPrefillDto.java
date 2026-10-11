package com.healix.core.observation.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 检查报告 OCR 预填结果，供人工确认后再落库。
 *
 * @param examType 识别到的检查类型 code；识别不出时为 null，由人工选择
 * @param examTypeName 类型中文名，便于前端直接展示
 * @param findings 已对齐 {@code EXAM_FINDING_FIELDS} 的关键测量
 * @param ignoredFindings 模型给了但不在该类型 schema 内的字段名
 */
public record ExamOcrPrefillDto(
        String examType,
        String examTypeName,
        LocalDateTime examinedAt,
        String conclusion,
        Map<String, Object> findings,
        List<String> ignoredFindings,
        List<String> warnings) {}
