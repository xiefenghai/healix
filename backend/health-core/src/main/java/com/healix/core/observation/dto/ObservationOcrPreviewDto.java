package com.healix.core.observation.dto;

import com.healix.core.medication.dto.MedOcrPrefillDto;
import java.util.List;

/**
 * 驾驶舱 OCR 识别预览：只返回结构化结果，不落库。
 *
 * <p>{@code kind} 为 LAB、EXAM 或 MED；对应 {@code lab}/{@code exam}/{@code med} 其一非空。
 */
public record ObservationOcrPreviewDto(
        String kind,
        String title,
        List<String> warnings,
        LabOcrPrefillDto lab,
        ExamOcrPrefillDto exam,
        MedOcrPrefillDto med,
        String imageKey) {

    public ObservationOcrPreviewDto(
            String kind,
            String title,
            List<String> warnings,
            LabOcrPrefillDto lab,
            ExamOcrPrefillDto exam,
            MedOcrPrefillDto med) {
        this(kind, title, warnings, lab, exam, med, null);
    }

    public ObservationOcrPreviewDto withImageKey(String imageKey) {
        return new ObservationOcrPreviewDto(
                kind,
                title,
                warnings,
                lab == null ? null : lab.withImageKey(imageKey),
                exam == null ? null : exam.withImageKey(imageKey),
                med == null ? null : med.withImageKey(imageKey),
                imageKey);
    }
}
