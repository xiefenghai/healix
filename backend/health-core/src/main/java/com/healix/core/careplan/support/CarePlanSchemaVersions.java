package com.healix.core.careplan.support;

/**
 * 健康管理方案「规则版本」（产品级）。
 *
 * <p>与患者每次发布的 {@code version_no}（发布序号）无关：人工制定与 AI 生成在同一套
 * 运动/饮食/执行字段与生成规则下，都标记为同一 schema 版本（如 V1）。
 * 仅当字段结构或生成规则变更时，由开发上调 {@link #CURRENT}。
 */
public final class CarePlanSchemaVersions {

    /** 当前产品规则版本；改运动/饮食方案字段或生成规则时 +1。 */
    public static final int CURRENT = 1;

    private CarePlanSchemaVersions() {}

    public static String label(Integer schemaVersion) {
        int v = schemaVersion == null || schemaVersion <= 0 ? CURRENT : schemaVersion;
        return "V" + v;
    }
}
