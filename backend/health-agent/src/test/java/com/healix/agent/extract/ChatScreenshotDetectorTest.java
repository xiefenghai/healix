package com.healix.agent.extract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChatScreenshotDetectorTest {

    @Test
    void wechatBubbleWithVitalsIsChatNotLab() {
        String text = """
                客户：今天早上7点半在家量了血压，138/86。7点空腹血糖6.2。体重120斤，腰围82厘米。心率72。
                营养师：平时还抽烟吗？
                客户：烟已经戒了两年。今天早上只抽了两根，不算。空腹血糖参考范围3.9-6.1。
                """;
        assertTrue(ChatScreenshotDetector.isChatRecord(text));
    }

    @Test
    void spokenVitalsWithoutRoleLabelsAreStillChat() {
        String text = "今天早上7点半在家量了血压138/86，空腹血糖6.2，体重120斤，腰围82厘米，心率72。";
        assertTrue(ChatScreenshotDetector.isChatRecord(text));
    }

    @Test
    void formalLabReportStaysAReport() {
        String text = """
                检验报告
                检验科 标本类型 静脉血
                项目名称 结果 参考范围
                空腹静脉血糖 6.2 mmol/L 3.9-6.1
                """;
        assertFalse(ChatScreenshotDetector.isChatRecord(text));
    }
}
