package com.healix.core.observation.dto;

import java.util.List;

/**
 * 驾驶舱 OCR 确认入库后的结果摘要。
 *
 * @param kind LAB、EXAM 或 MED
 * @param summary 给会话区展示的纯文本
 */
public record ObservationOcrIngestDto(
        String kind, String reportId, String title, String summary, List<String> warnings) {}
