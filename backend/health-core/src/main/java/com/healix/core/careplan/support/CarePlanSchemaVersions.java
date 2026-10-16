package com.healix.core.careplan.support;

/**
 * 健康管理方案「规则版本」（产品级）。
 *
 * <p>与患者每次发布的 {@code version_no}（发布序号）无关：人工制定与 AI 生成在同一套
 * 运动/饮食/执行字段与生成规则下，都标记为同一 schema 版本（如 V1）。
 * 仅当字段结构或生成规则变更时，由开发上调 {@link #CURRENT}。
 */
public final class CarePlanSchemaVersions {

    /**
     * 当前产品规则版本；改运动/饮食方案字段或生成规则时 +1。
     *
     * <p>V2：运动任务 payload（targetMin / intensity）、exercise.targets、weeklyPlan 改为每天。
     * <p>V3：执行任务类别 WATER + targetMl；sampleDay 每餐支持 {text, swapHint}。
     * <p>V4：SLEEP/MOOD 任务；diet.sampleWeek；机构食谱库应用。
     * <p>V5：五类方案类型；块/任务 rationale；体重监测自动打卡。
     */
    public static final int CURRENT = 5;

    private CarePlanSchemaVersions() {}

    public static String label(Integer schemaVersion) {
        int v = schemaVersion == null || schemaVersion <= 0 ? CURRENT : schemaVersion;
        return "V" + v;
    }
}
