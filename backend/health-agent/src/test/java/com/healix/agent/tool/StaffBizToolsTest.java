package com.healix.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class StaffBizToolsTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 8, 35);

    @Test
    void parsesMorningAsTen() {
        assertEquals(LocalDateTime.of(2026, 10, 13, 10, 0),
                StaffBizTools.parsePlannedAt("创建随访，今天上午电话", NOW));
    }

    @Test
    void parsesExplicitClock() {
        assertEquals(LocalDateTime.of(2026, 10, 14, 15, 30),
                StaffBizTools.parsePlannedAt("明天 15:30 回访", NOW));
        assertEquals(LocalDateTime.of(2026, 10, 15, 15, 30),
                StaffBizTools.parsePlannedAt("后天下午3点半打电话", NOW));
    }

    @Test
    void dayWithoutTimeDefaultsToTen() {
        assertEquals(LocalDateTime.of(2026, 10, 14, 10, 0),
                StaffBizTools.parsePlannedAt("明天回访一下", NOW));
    }

    @Test
    void noTimeWordsOrPastTimeReturnsNull() {
        assertNull(StaffBizTools.parsePlannedAt("创建随访", NOW));
        assertNull(StaffBizTools.parsePlannedAt("今天早上 7 点回访", NOW));
        assertNull(StaffBizTools.parsePlannedAt("连续3天没打卡，建随访", NOW));
    }

    @Test
    void detectsOrgInsightIntents() {
        assertTrue(StaffBizTools.wantsAdherenceRank("这周谁打卡掉队最多？"));
        assertTrue(StaffBizTools.wantsPlanEnding("还有谁的方案快到期了？"));
        assertFalse(StaffBizTools.wantsPlanEnding("今天先管谁"));
    }
}
