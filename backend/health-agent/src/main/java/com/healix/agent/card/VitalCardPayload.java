package com.healix.agent.card;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** 确认卡上的指标载荷。数字在出卡时写定，确认时只读这份。 */
@Getter
@Setter
public class VitalCardPayload {
    /** BP / GLUCOSE / WEIGHT */
    private String metric;
    private BigDecimal sys;
    private BigDecimal dia;
    /** HOME / CLINIC */
    private String bpContext;
    private BigDecimal value;
    /** FASTING / POSTPRANDIAL / RANDOM */
    private String mealContext;
    /** 患者原话里的斤，没有则为空 */
    private BigDecimal jinRaw;
    /** 今天已有的同一种读数，可空 */
    private String previousText;
}
