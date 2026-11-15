package com.healix.core.careplan.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import org.junit.jupiter.api.Test;

class CarePlanRevisionIntentTest {

    @Test
    void detectsNoCheckinAndNoExerciseIntents() {
        assertTrue(CarePlanRevisionIntent.wantsNoCheckins("用户不想打卡"));
        assertTrue(CarePlanRevisionIntent.wantsNoCheckins("请取消执行计划"));
        assertFalse(CarePlanRevisionIntent.wantsNoCheckins("减少打卡提醒频率到每天一次"));

        assertTrue(CarePlanRevisionIntent.wantsNoExercisePlan("客户不想要有运动计划"));
        assertTrue(CarePlanRevisionIntent.wantsNoExercisePlan("不要运动计划"));
        assertFalse(CarePlanRevisionIntent.wantsNoExercisePlan("不想剧烈运动，改成散步"));
    }

    @Test
    void clearsCheckinsAndSetsOmitFlag() {
        ObjectNode exec = JsonUtils.emptyObject();
        ArrayNode tasks = exec.putArray("tasks");
        ObjectNode t = tasks.addObject();
        t.put("code", "SLEEP_LOG");
        t.put("title", "记录昨夜睡眠");

        CarePlanRevisionIntent.apply("用户不想打卡", null, null, exec);

        assertTrue(exec.path(CarePlanRevisionIntent.OMIT_DEFAULT_CHECKINS).asBoolean());
        assertEquals(0, exec.path("tasks").size());
    }

    @Test
    void clearsExerciseWeeklyItems() {
        ObjectNode exercise = JsonUtils.emptyObject();
        ArrayNode week = exercise.putArray("weeklyPlan");
        ObjectNode mon = week.addObject();
        mon.put("day", "MON");
        ArrayNode items = mon.putArray("items");
        ObjectNode item = items.addObject();
        item.put("type", "WALK");
        item.put("durationMin", 30);
        exercise.put("goal", "每周中等强度150分钟");

        CarePlanRevisionIntent.apply("客户不想要有运动计划", exercise, null, null);

        assertEquals(7, exercise.path("weeklyPlan").size());
        for (var day : exercise.path("weeklyPlan")) {
            assertEquals(0, day.path("items").size());
        }
        assertTrue(exercise.path("goal").asText().contains("暂不"));
    }

    @Test
    void omitFlagSkipsSleepMoodBackfill() {
        ObjectNode exec = JsonUtils.emptyObject();
        exec.put(CarePlanRevisionIntent.OMIT_DEFAULT_CHECKINS, true);
        exec.putArray("tasks");

        CarePlanExecutionTaskNormalizer.normalize(exec, null);

        assertEquals(0, exec.path("tasks").size());
    }

    @Test
    void parseTaskCapFromKeepTwoToThree() {
        assertEquals(3, CarePlanRevisionIntent.parseTaskCap("执行计划太多了 保留2-3项即可"));
        assertEquals(3, CarePlanRevisionIntent.parseTaskCap("打卡任务精简到3条"));
        assertEquals(2, CarePlanRevisionIntent.parseTaskCap("执行任务只要2项"));
        assertEquals(null, CarePlanRevisionIntent.parseTaskCap("早餐保留2-3个鸡蛋"));
    }

    @Test
    void applyTrimsTasksToCapAndBlocksBackfill() {
        ObjectNode exec = JsonUtils.emptyObject();
        ArrayNode tasks = exec.putArray("tasks");
        tasks.addObject().put("category", "EXERCISE").put("title", "餐后步行");
        tasks.addObject().put("category", "DIET").put("title", "记录三餐");
        tasks.addObject().put("category", "WATER").put("title", "足量饮水");
        tasks.addObject().put("category", "SLEEP").put("code", "SLEEP_LOG").put("title", "睡眠");
        tasks.addObject().put("category", "MOOD").put("code", "MOOD_LOG").put("title", "心情");

        CarePlanRevisionIntent.apply("执行计划太多了 保留2-3项即可", null, null, exec);
        CarePlanExecutionTaskNormalizer.normalize(exec, null);

        assertEquals(3, exec.path(CarePlanRevisionIntent.TASK_CAP).asInt());
        assertEquals(3, exec.path("tasks").size());
        assertTrue(exec.path(CarePlanRevisionIntent.OMIT_DEFAULT_CHECKINS).asBoolean());
        boolean hasSleepOrMood = false;
        for (var t : exec.path("tasks")) {
            String cat = t.path("category").asText();
            if ("SLEEP".equals(cat) || "MOOD".equals(cat)) {
                hasSleepOrMood = true;
            }
        }
        assertFalse(hasSleepOrMood);
    }
}
