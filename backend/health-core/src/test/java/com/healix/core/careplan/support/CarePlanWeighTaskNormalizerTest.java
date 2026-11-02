package com.healix.core.careplan.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import org.junit.jupiter.api.Test;

class CarePlanWeighTaskNormalizerTest {

    @Test
    void weeklyWeighForcesQwEvenWhenModelSaysTiw() {
        ObjectNode snap = JsonUtils.emptyObject();
        snap.putObject("goals").put("weighFrequency", "WEEKLY");
        ObjectNode exec = JsonUtils.emptyObject();
        ArrayNode tasks = exec.putArray("tasks");
        ObjectNode weigh = tasks.addObject();
        weigh.put("code", "WEIGHT_LOG");
        weigh.put("relatedRef", "WEIGHT");
        weigh.put("title", "每周固定一天晨起空腹称体重");
        weigh.put("frequency", "TIW");

        CarePlanWeighTaskNormalizer.normalize(exec, snap);

        assertThat(weigh.path("frequency").asText()).isEqualTo("QW");
        assertThat(weigh.path("title").asText()).contains("每周");
    }

    @Test
    void dailyWeighLeftAloneWhenNotWeeklyMode() {
        ObjectNode snap = JsonUtils.emptyObject();
        snap.putObject("goals").put("weighFrequency", "DAILY");
        ObjectNode exec = JsonUtils.emptyObject();
        ObjectNode weigh = exec.putArray("tasks").addObject();
        weigh.put("code", "WEIGHT_LOG");
        weigh.put("relatedRef", "WEIGHT");
        weigh.put("title", "记录体重");
        weigh.put("frequency", "QD");

        CarePlanWeighTaskNormalizer.normalize(exec, snap);

        assertThat(weigh.path("frequency").asText()).isEqualTo("QD");
    }
}
