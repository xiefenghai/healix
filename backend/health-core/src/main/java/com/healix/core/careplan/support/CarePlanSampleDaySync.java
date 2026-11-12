package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 以 {@code sampleWeek.MON} 为准覆盖 {@code sampleDay}，避免示例日与周一示例餐互相矛盾。
 */
public final class CarePlanSampleDaySync {

    private CarePlanSampleDaySync() {}

    /** @return 是否写入了 sampleDay */
    public static boolean syncFromMonday(ObjectNode diet) {
        if (diet == null) {
            return false;
        }
        JsonNode week = diet.get("sampleWeek");
        if (week == null || !week.isObject()) {
            return false;
        }
        JsonNode mon = week.get("MON");
        if (mon == null || mon.isNull() || !mon.isObject()) {
            return false;
        }
        if (mon.isEmpty()) {
            return false;
        }
        diet.set("sampleDay", mon.deepCopy());
        return true;
    }
}
