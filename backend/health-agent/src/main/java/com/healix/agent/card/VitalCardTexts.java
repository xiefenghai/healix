package com.healix.agent.card;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 确认卡摘要。场景词可以随患者修改重写，数字不变。 */
public final class VitalCardTexts {

    private VitalCardTexts() {}

    public static String summary(VitalCardPayload card) {
        String previous = card.getPreviousText() == null ? "" : card.getPreviousText();
        return switch (card.getMetric()) {
            case "BP" -> "记一次"
                    + bpLabel(card.getBpContext())
                    + "血压 "
                    + number(card.getSys())
                    + "/"
                    + number(card.getDia())
                    + "，时间是现在？"
                    + previous;
            case "GLUCOSE" -> "记一次"
                    + mealLabel(card.getMealContext())
                    + "血糖 "
                    + number(card.getValue())
                    + " mmol/L，时间是现在？"
                    + previous;
            case "WEIGHT" -> "记一次体重 "
                    + weightNumber(card)
                    + " kg"
                    + (card.getJinRaw() == null ? "" : "（" + number(card.getJinRaw()) + " 斤）")
                    + "，时间是现在？"
                    + previous;
            default -> throw new IllegalArgumentException("未知指标卡");
        };
    }

    public static String bpLabel(String bpContext) {
        return "CLINIC".equals(bpContext) ? "诊室" : "家庭";
    }

    public static String mealLabel(String mealContext) {
        return switch (mealContext == null ? "" : mealContext) {
            case "FASTING" -> "空腹";
            case "POSTPRANDIAL" -> "餐后";
            default -> "随机";
        };
    }

    public static String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String weightNumber(VitalCardPayload card) {
        if (card.getJinRaw() != null) {
            return card.getValue().setScale(1, RoundingMode.HALF_UP).toPlainString();
        }
        return number(card.getValue());
    }
}
