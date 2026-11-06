package com.healix.agent.extract;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatExtractRow {
    private String id;
    /** VITAL / ARCHIVE / INTAKE */
    private String target;
    private String code;
    private String label;
    private BigDecimal value;
    private BigDecimal systolic;
    private BigDecimal diastolic;
    private String unit;
    private String sourceUnit;
    private BigDecimal sourceValue;
    private String measuredAt;
    private String mealContext;
    private String bpContext;
    private String archiveValue;
    private String currentDisplay;
    private boolean textField;
    private boolean derived;
    private boolean quoteTruncated;
    private String quote;
    /** WRITTEN / DISCARDED。空着表示还没处理。 */
    private String outcome;
    /** READY 或阻塞原因码 */
    private String status;
    private String statusText;
    private List<ChatExtractOption> options = new ArrayList<>();
    /** 问卷题型：ENUM / MULTI / NUMBER / TEXT / DATE / TIME / YEAR */
    private String intakeType;
    private Double intakeMin;
    private Double intakeMax;
    private boolean multi;
}
