package com.healix.core.archive.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 患者档案完整度（实时计算）。 */
@Getter
@Setter
public class ArchiveCompletenessDto {
    private String peopleId;
    /** 已填字段数 */
    private int filledCount;
    /** 应填字段数（分母） */
    private int totalCount;
    /** 0–100，四舍五入 */
    private int percent;
    private int basicFilledCount;
    private int basicTotalCount;
    private List<DiseaseSection> diseases = new ArrayList<>();

    @Getter
    @Setter
    public static class DiseaseSection {
        private String diseaseCode;
        private int filledCount;
        private int totalCount;
        private int percent;
    }
}
