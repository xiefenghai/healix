package com.healix.agent.extract;

import java.util.Locale;

/**
 * 丢进对话框的图如果是聊天截图，不要按化验单只留一项血糖。
 * 正式检验单、检查单、处方仍走原来的单据识别。
 */
public final class ChatScreenshotDetector {

    private ChatScreenshotDetector() {}

    public static boolean isChatRecord(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String compact = text.replaceAll("\\s+", "");
        if (hits(compact, "检验报告", "化验单", "检验科", "标本类型", "样本号", "检查所见", "影像诊断", "处方") >= 2) {
            return false;
        }
        if (containsSpeaker(compact)) {
            return true;
        }
        int vital = hits(compact, "血压", "血糖", "体重", "腰围", "心率");
        int talk = hits(compact, "今天", "早上", "平时", "戒了", "在家", "量了", "测了");
        return vital >= 2 && talk >= 1;
    }

    private static boolean containsSpeaker(String compact) {
        return compact.contains("客户:")
                || compact.contains("客户：")
                || compact.contains("营养师:")
                || compact.contains("营养师：")
                || compact.contains("健管师:")
                || compact.contains("健管师：")
                || compact.contains("患者:")
                || compact.contains("患者：");
    }

    private static int hits(String text, String... keys) {
        String upper = text.toUpperCase(Locale.ROOT);
        int count = 0;
        for (String key : keys) {
            if (upper.contains(key.toUpperCase(Locale.ROOT))) {
                count++;
            }
        }
        return count;
    }
}
